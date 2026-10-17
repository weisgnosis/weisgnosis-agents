/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorization, ToolAuthorizer, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object StructuredRepairSpec extends ZIOSpecDefault:
  final case class Answer(value: Int) derives JsonCodec
  final case class OptionalAnswer(note: Option[String]) derives JsonCodec

  private val input: Chunk[Msg]              = Chunk(Msg.user("Return the checked answer"))
  private val repair: StructuredRepairPolicy = StructuredRepairPolicy.OneOutputOnlyRequest
  private val properties: Json = Json.Obj(
    "value" -> Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(0))
  )
  private val schema: Json = Json.Obj(
    "type"                 -> Json.Str("object"),
    "properties"           -> properties,
    "required"             -> Json.Arr(Json.Str("value")),
    "additionalProperties" -> Json.Bool(false)
  )
  private val optionalValueSchema: Json = Json.Obj(
    "type"                 -> Json.Str("object"),
    "properties"           -> properties,
    "additionalProperties" -> Json.Bool(false)
  )

  private def text(value: String, tokens: Int = 0): TurnOutput =
    TurnOutput.FinalText(
      Msg.assistant(
        content = Chunk(ContentBlock.Text(value)),
        usage = Some(ChatUsage(inputTokens = tokens, outputTokens = 1)),
        name = None
      )
    )

  private def calls(values: ContentBlock.ToolUse*): TurnOutput =
    TurnOutput.ToolCalls(Msg.assistant(content = Chunk.fromIterable(values), name = None))

  private def synthetic(id: String, payload: Json): TurnOutput =
    calls(ContentBlock.ToolUse(id, StructuredOutput.ToolName, Json.Obj("response" -> payload)))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Output-only structured repair")(
      suite("native candidates")(
        List(
          ("malformed JSON", "not json", schema),
          ("schema violation", "{\"value\":-1}", schema),
          ("DTO decoding failure", "{}", optionalValueSchema)
        ).map { case (label, invalid, requestedSchema) =>
          test(label) {
            for
              model <- ScriptedLlmClient.make(Chunk(text(invalid, 2), text("{\"value\":1}", 3)), 3)
              completions <- Ref.make(0)
              agent <- ReActAgent.make(
                client = model,
                completionObserver = TurnCompletionObserver(_ => completions.update(_ + 1))
              )
              result <- agent
                .runStructured[Answer](input, requestedSchema, StructuredMode.Native, repair)
              recorded <- model.requests
              observed <- completions.get
              _        <- model.verifyConsumed
            yield assertTrue(
              result._2 == Answer(1),
              recorded.size == 2,
              recorded.lastOption.exists(_.input.tools.isEmpty),
              recorded.lastOption.exists(_.input.history.exists(_.text == invalid)),
              result._1.history.exists(_.text == invalid),
              result._1.history.lastOption.contains(result._1.reply),
              result._1.usage.exists(_.totalTokens == Right(7)),
              observed == 1
            )
          }
        }*
      ),
      test("valid first candidate consumes no correction request") {
        for
          model    <- ScriptedLlmClient.make(Chunk(text("{\"value\":1}")), 2)
          agent    <- ReActAgent.make(client = model)
          result   <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair)
          requests <- model.requests
          _        <- model.verifyConsumed
        yield assertTrue(result._2 == Answer(1), requests.size == 1)
      },
      test("completed application tool is retained and never replayed during repair") {
        for
          executions <- Ref.make(0)
          tool = Tool.make(ToolDef("read_evidence", "Read evidence", Json.Obj()))(_ =>
            executions.update(_ + 1).as("evidence is ready")
          )
          toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
          model <- ScriptedLlmClient.make(
            Chunk(
              calls(ContentBlock.ToolUse("evidence", "read_evidence", Json.Obj())),
              text("{\"value\":-1}"),
              text("{\"value\":1}")
            ),
            4
          )
          agent    <- ReActAgent.make(client = model, toolkit = toolkit)
          result   <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair)
          recorded <- model.requests
          count    <- executions.get
          _        <- model.verifyConsumed
        yield assertTrue(
          count == 1,
          recorded.size == 3,
          recorded.lastOption.exists(_.input.tools.isEmpty),
          recorded.lastOption.exists(
            _.input.history.flatMap(_.toolResults).exists(_.id == "evidence")
          ),
          result._1.history.flatMap(_.toolUses).map(_.id) == Chunk("evidence"),
          result._1.history.flatMap(_.toolResults).map(_.id) == Chunk("evidence"),
          result._2 == Answer(1)
        )
      },
      test("synthetic correction validates the exact optional-null candidate") {
        val optionalSchema = Json.Obj(
          "type"                 -> Json.Str("object"),
          "properties"           -> Json.Obj("note" -> Json.Obj("type" -> Json.Str("string"))),
          "additionalProperties" -> Json.Bool(false)
        )
        for
          model <- ScriptedLlmClient.make(
            Chunk(
              synthetic("first", Json.Obj("note" -> Json.Null)),
              synthetic("corrected", Json.Obj("note" -> Json.Str("checked")))
            ),
            3
          )
          agent <- ReActAgent.make(client = model)
          result <- agent.runStructured[OptionalAnswer](
            input,
            optionalSchema,
            StructuredMode.SyntheticTool,
            repair
          )
          requests <- model.requests
          _        <- model.verifyConsumed
        yield assertTrue(
          requests.size == 2,
          requests.lastOption.exists(_.input.tools.map(_.name) == Chunk(StructuredOutput.ToolName)),
          result._2 == OptionalAnswer(Some("checked")),
          !result._1.history.flatMap(_.toolUses).exists(_.name == StructuredOutput.ToolName)
        )
      },
      test("invalid correction fails without a third model request") {
        for
          model <- ScriptedLlmClient.make(Chunk(text("{\"value\":-1}"), text("{\"value\":-2}")), 4)
          agent <- ReActAgent.make(client = model)
          outcome  <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair).exit
          requests <- model.requests
          _        <- model.verifyConsumed
        yield assertTrue(outcome.isFailure, requests.size == 2)
      },
      test("fail-fast remains the default") {
        for
          model    <- ScriptedLlmClient.make(Chunk(text("{\"value\":-1}")), 2)
          agent    <- ReActAgent.make(client = model)
          outcome  <- agent.runStructured[Answer](input, schema, StructuredMode.Native).exit
          requests <- model.requests
        yield assertTrue(outcome.isFailure, requests.size == 1)
      },
      test("invalid host schema fails before a model request") {
        for
          model        <- ScriptedLlmClient.make(Chunk(text("{\"value\":1}")), 2)
          preparations <- Ref.make(0)
          agent <- ReActAgent.make(
            client = model,
            invocationPreparer =
              InvocationPreparer((_, value) => preparations.update(_ + 1).as(value))
          )
          outcome <- agent
            .runStructured[Answer](
              input,
              Json.Obj("type" -> Json.Str("imaginary")),
              StructuredMode.Native,
              repair
            )
            .exit
          requests <- model.requests
          count    <- preparations.get
        yield assertTrue(outcome.isFailure, requests.isEmpty, count == 0)
      },
      test("Auto repair is rejected before invocation callbacks and model requests") {
        for
          model        <- ScriptedLlmClient.make(Chunk(text("{\"value\":1}")), 2)
          preparations <- Ref.make(0)
          agent <- ReActAgent.make(
            client = model,
            invocationPreparer =
              InvocationPreparer((_, value) => preparations.update(_ + 1).as(value))
          )
          outcome  <- agent.runStructured[Answer](input, schema, StructuredMode.Auto, repair).exit
          requests <- model.requests
          count    <- preparations.get
        yield assertTrue(outcome.isFailure, requests.isEmpty, count == 0)
      },
      suite("unexpected correction calls are closed without dispatch")(
        List(false, true).map { claimedServer =>
          test(s"claimed server marker: $claimedServer") {
            for
              executions     <- Ref.make(0)
              authorizations <- Ref.make(0)
              tool = Tool.make(ToolDef("mutate", "A guarded mutation", Json.Obj()))(_ =>
                executions.update(_ + 1).as("done")
              )
              toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
              model <- ScriptedLlmClient.make(
                Chunk(
                  text("{\"value\":-1}"),
                  calls(
                    ContentBlock
                      .ToolUse("unwanted", "mutate", Json.Obj())
                      .withServerTool(claimedServer)
                  )
                ),
                3
              )
              agent <- ReActAgent.make(
                client = model,
                toolkit = toolkit,
                toolAuthorizer = ToolAuthorizer(request =>
                  authorizations.update(_ + 1).as(ToolAuthorization.Approved(request.call.input))
                )
              )
              outcome <- agent
                .runStructured[Answer](input, schema, StructuredMode.Native, repair)
                .exit
              history            <- agent.history
              requests           <- model.requests
              count              <- executions.get
              authorizationCount <- authorizations.get
            yield assertTrue(
              outcome.isFailure,
              count == 0,
              authorizationCount == 0,
              requests.size == 2,
              history.flatMap(_.toolUses).map(_.id) == Chunk("unwanted"),
              history
                .flatMap(_.toolResults)
                .exists(result => result.id == "unwanted" && result.state == ToolResultState.Error),
              ToolHistory.inspect(history).isComplete
            )
          }
        }*
      ),
      suite("correction inline results preserve provider occurrence authority")(
        List(false, true).map { claimedServer =>
          test(s"inline success with server marker: $claimedServer") {
            val inline = ContentBlock.ToolResult.text("inline", "mutate", "provider observation")
            val correction = TurnOutput.ToolCalls(
              Msg.assistant(
                content = Chunk(
                  ContentBlock
                    .ToolUse("inline", "mutate", Json.Obj())
                    .withServerTool(claimedServer),
                  inline
                ),
                name = None
              )
            )
            for
              executions     <- Ref.make(0)
              authorizations <- Ref.make(0)
              tool = Tool.make(ToolDef("mutate", "An application mutation", Json.Obj()))(_ =>
                executions.update(_ + 1).as("must not run")
              )
              toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
              model   <- ScriptedLlmClient.make(Chunk(text("{\"value\":-1}"), correction), 3)
              agent <- ReActAgent.make(
                client = model,
                toolkit = toolkit,
                toolAuthorizer = ToolAuthorizer(request =>
                  authorizations.update(_ + 1).as(ToolAuthorization.Approved(request.call.input))
                )
              )
              outcome <- agent
                .runStructured[Answer](input, schema, StructuredMode.Native, repair)
                .exit
              history            <- agent.history
              executionCount     <- executions.get
              authorizationCount <- authorizations.get
              requests           <- model.requests
            yield assertTrue(
              outcome.isFailure,
              executionCount == 0,
              authorizationCount == 0,
              requests.size == 2,
              history.exists(_.text == "{\"value\":-1}"),
              history.flatMap(_.toolResults) == (if claimedServer then Chunk(inline)
                                                 else Chunk.empty),
              history.flatMap(_.toolUses).map(_.id) == (if claimedServer then Chunk("inline")
                                                        else Chunk.empty),
              ToolHistory.inspect(history).isComplete
            )
          }
        }*
      ),
      test("opt-in synthetic schema violation takes one output-only correction") {
        for
          executions <- Ref.make(0)
          tool = Tool.make(ToolDef("read_evidence", "Read application evidence", Json.Obj()))(_ =>
            executions.update(_ + 1).as("must not run")
          )
          toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
          model <- ScriptedLlmClient.make(
            Chunk(
              synthetic("invalid", Json.Obj("value" -> Json.Num(-1))),
              synthetic("corrected", Json.Obj("value" -> Json.Num(1)))
            ),
            3
          )
          agent  <- ReActAgent.make(client = model, toolkit = toolkit)
          result <- agent.runStructured[Answer](input, schema, StructuredMode.SyntheticTool, repair)
          requests <- model.requests
          count    <- executions.get
          _        <- model.verifyConsumed
        yield assertTrue(
          result._2 == Answer(1),
          count == 0,
          requests.size == 2,
          requests.headOption.exists(
            _.input.tools
              .find(_.name == StructuredOutput.ToolName)
              .exists(_.parametersSchema == StructuredOutput.buildParametersSchema(schema))
          ),
          requests.lastOption.exists(_.input.tools.map(_.name) == Chunk(StructuredOutput.ToolName)),
          !result._1.history.flatMap(_.toolUses).exists(_.name == StructuredOutput.ToolName)
        )
      },
      test("correction passes through the host reasoning replacement policy") {
        for
          model <- ScriptedLlmClient.make(Chunk(text("{\"value\":-1}"), text("{\"value\":1}")), 3)
          decisions <- Ref.make(0)
          hook = PostReasoningHook((_, _) =>
            decisions
              .updateAndGet(_ + 1)
              .map(count =>
                if count == 2 then ReasoningDecision.Replace(Msg.assistant("{\"value\":2}"))
                else ReasoningDecision.Keep
              )
          )
          agent    <- ReActAgent.make(client = model, postReasoningHook = hook)
          result   <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair)
          count    <- decisions.get
          requests <- model.requests
        yield assertTrue(result._2 == Answer(2), count == 2, requests.size == 2)
      },
      test("discarding a correction fails without making a third request") {
        for
          model <- ScriptedLlmClient.make(Chunk(text("{\"value\":-1}"), text("{\"value\":1}")), 3)
          decisions <- Ref.make(0)
          hook = PostReasoningHook((_, _) =>
            decisions
              .updateAndGet(_ + 1)
              .map(count =>
                if count == 2 then ReasoningDecision.Discard else ReasoningDecision.Keep
              )
          )
          agent    <- ReActAgent.make(client = model, postReasoningHook = hook)
          outcome  <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair).exit
          count    <- decisions.get
          requests <- model.requests
        yield assertTrue(outcome.isFailure, count == 2, requests.size == 2)
      },
      test("synthetic correction preserves prior business thinking and usage") {
        val optionalSchema = Json.Obj(
          "type"                 -> Json.Str("object"),
          "properties"           -> Json.Obj("note" -> Json.Obj("type" -> Json.Str("string"))),
          "additionalProperties" -> Json.Bool(false)
        )
        val businessThinking = ContentBlock.Thinking("Independent business reasoning")
        val businessUsage    = ChatUsage(inputTokens = 11, outputTokens = 7)
        val businessReply = Msg
          .assistant(
            content = Chunk(
              businessThinking,
              ContentBlock.ToolUse("business", "read_evidence", Json.Obj())
            ),
            usage = Some(businessUsage),
            name = None
          )
          .withMetadata("business-marker", Json.Str("retain"))
        for
          executions <- Ref.make(0)
          tool = Tool.make(ToolDef("read_evidence", "Read evidence", Json.Obj()))(_ =>
            executions.update(_ + 1).as("evidence is ready")
          )
          toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
          model <- ScriptedLlmClient.make(
            Chunk(
              TurnOutput.ToolCalls(businessReply),
              synthetic("invalid", Json.Obj("note" -> Json.Null)),
              synthetic("corrected", Json.Obj("note" -> Json.Str("checked")))
            ),
            4
          )
          agent <- ReActAgent.make(client = model, toolkit = toolkit)
          result <- agent.runStructured[OptionalAnswer](
            input,
            optionalSchema,
            StructuredMode.SyntheticTool,
            repair
          )
          count <- executions.get
          retained = result._1.history.find(_.toolUses.exists(_.id == "business"))
          _ <- model.verifyConsumed
        yield assertTrue(
          result._2 == OptionalAnswer(Some("checked")),
          count == 1,
          retained.exists(_.content.contains(businessThinking)),
          retained.exists(_.usage.contains(businessUsage)),
          retained.exists(_.metadata.get("business-marker").contains(Json.Str("retain"))),
          result._1.history.flatMap(_.toolResults).map(_.id) == Chunk("business")
        )
      },
      test("stream publishes one start, one validated result, one end and one completion") {
        for
          model <- ScriptedLlmClient.make(Chunk(text("{\"value\":-1}"), text("{\"value\":1}")), 3)
          completions <- Ref.make(0)
          agent <- ReActAgent.make(
            client = model,
            completionObserver = TurnCompletionObserver(_ => completions.update(_ + 1))
          )
          updates <- agent
            .streamStructuredWithResult[Answer](input, schema, StructuredMode.Native, repair)
            .runCollect
          events  = updates.collect { case AgentRunUpdate.Event(event) => event }
          results = updates.collect { case AgentRunUpdate.Result(value) => value }
          observed <- completions.get
        yield assertTrue(
          events.count(_.isInstanceOf[AgentEvent.AgentStartEvent]) == 1,
          events.count(_.isInstanceOf[AgentEvent.AgentResultEvent]) == 1,
          events.count(_.isInstanceOf[AgentEvent.AgentEndEvent]) == 1,
          results.size == 1,
          results.headOption.exists(_._2 == Answer(1)),
          observed == 1
        )
      }
    )
