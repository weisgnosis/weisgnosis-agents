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

import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentReturnDirectSpec extends ZIOSpecDefault:
  private enum Batch:
    case Direct, Default, Mixed, Error, Denied, Inline, Control, Empty, EmptyText, PartiallyEmpty,
      Stop
  private enum Entry:
    case Live, Restored, Supplied, Partial
  private enum Cleanup:
    case Complete, Cancel

  final private case class Answer(value: Int) derives JsonCodec
  private def call(name: String): ContentBlock.ToolUse =
    ContentBlock.ToolUse("same", name, Json.Obj())
  private def tool(
      name: String,
      mode: ToolReturnMode,
      run: UIO[ToolExecutionResult]
  ): Tool[Any, Json, ToolExecutionResult] = new Tool[Any, Json, ToolExecutionResult]:
    def definition: ToolDef = ToolDef(name, "Return an observation", Json.Obj(), returnMode = mode)
    def decodeInput(raw: Json): Either[String, Json]                            = Right(raw)
    def execute(input: Json): UIO[ToolExecutionResult]                          = run
    def encodeOutput(result: ToolExecutionResult): String                       = result.output
    override def encodeResult(result: ToolExecutionResult): ToolExecutionResult = result

  private val pause: PostReasoningHook = PostReasoningHook((_, message) =>
    ZIO.succeed(
      if message.toolUses.nonEmpty then ReasoningDecision.Suspend("host work")
      else ReasoningDecision.Keep
    )
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent return direct")(
    test("only all-successful local direct batches return ordered processed presentation blocks"):
      ZIO
        .foreach(Chunk.fromArray(Batch.values)): batch =>
          ZIO.scoped:
            for
              secondProcessed <- Promise.make[Nothing, Unit]
              calls           <- Ref.make(0)
              image = ContentBlock.Image.url("https://example.test/image.png")
              firstOutput: Chunk[ContentBlock] = batch match
                case Batch.Empty | Batch.PartiallyEmpty => Chunk.empty
                case Batch.EmptyText                    => Chunk(ContentBlock.Text(""))
                case Batch.Control                      => Chunk(call("injected"))
                case _                                  => Chunk(ContentBlock.Text("first"), image)
              secondOutput: Chunk[ContentBlock] = batch match
                case Batch.Empty     => Chunk.empty
                case Batch.EmptyText => Chunk(ContentBlock.Text(""))
                case _               => Chunk(ContentBlock.Text("second"))
              mode =
                if batch == Batch.Default then ToolReturnMode.Continue else ToolReturnMode.Direct
              tools = Toolkit(
                Map(
                  "first" -> tool(
                    "first",
                    mode,
                    secondProcessed.await.as(
                      ToolExecutionResult(ToolResultPayload(firstOutput), ToolResultState.Success)
                    )
                  ),
                  "second" -> tool(
                    "second",
                    if batch == Batch.Mixed then ToolReturnMode.Continue else mode,
                    ZIO.succeed(
                      ToolExecutionResult(
                        ToolResultPayload(secondOutput),
                        if batch == Batch.Error then ToolResultState.Error
                        else ToolResultState.Success
                      )
                    )
                  )
                )
              )
              inline: Chunk[ContentBlock] =
                if batch == Batch.Inline then
                  Chunk(
                    call("remote").withServerTool(true),
                    ContentBlock.ToolResult.text("same", "remote", "remote")
                  )
                else Chunk.empty
              message = Msg.assistant(Chunk(call("first"), call("second")) ++ inline)
              agent <- ReActAgent.make(
                config = AgentConfig(toolExecution = ToolExecution.Parallel),
                client = LlmClient.mockEffectful((_, _, _) =>
                  calls
                    .getAndUpdate(_ + 1)
                    .map(index =>
                      if index == 0 then TurnOutput.ToolCalls(message)
                      else TurnOutput.FinalText(Msg.assistant("summary"))
                    )
                ),
                toolkit = tools,
                toolAuthorizer = ToolAuthorizer(request =>
                  ZIO.succeed(
                    if batch == Batch.Denied && request.call.name == "first" then
                      ToolAuthorization.Denied("denied")
                    else ToolAuthorization.Approved(request.call.input)
                  )
                ),
                toolResultProcessor = ToolResultProcessor.deciding(result =>
                  val payload = ToolResultPayload(result.output.map:
                    case ContentBlock.Text(value) if value.nonEmpty =>
                      ContentBlock.Text(s"redacted-$value")
                    case value => value)
                  ZIO.when(result.name.contains("second"))(secondProcessed.succeed(())) *>
                    ZIO.succeed(
                      if batch == Batch.Stop then ToolResultDecision.Stop(payload, "stop")
                      else ToolResultDecision.Continue(payload)
                    )
                )
              )
              updates <- agent
                .streamWithResult(Msg.user("run"))
                .runCollect
                .ensuring(secondProcessed.succeed(()).unit)
              result <- ZIO
                .fromOption(updates.collect { case AgentRunUpdate.Result(value) =>
                  value
                }.headOption)
                .orElseFail(AgentError.InvalidMessage("Missing result"))
              count <- calls.get
              events = updates.collect { case AgentRunUpdate.Event(value) => value }
              expected: Option[Chunk[ContentBlock]] = batch match
                case Batch.Direct =>
                  Some(
                    Chunk(
                      ContentBlock.Text("redacted-first"),
                      image,
                      ContentBlock.Text("redacted-second")
                    )
                  )
                case Batch.Empty     => Some(Chunk(ContentBlock.Text("(no output)")))
                case Batch.EmptyText => Some(Chunk(ContentBlock.Text(""), ContentBlock.Text("")))
                case Batch.PartiallyEmpty => Some(Chunk(ContentBlock.Text("redacted-second")))
                case _                    => None
              directAssertion = expected.fold(
                assertTrue(
                  result.reply.generationReason != GenerateReason.ToolReturnDirect,
                  count == (if batch == Batch.Stop then 1 else 2),
                  result.state == (if batch == Batch.Stop then AgentRunState.Stopped("stop")
                                   else AgentRunState.Completed)
                )
              )(content =>
                assertTrue(
                  result.reply.content == content,
                  result.reply.generationReason == GenerateReason.ToolReturnDirect,
                  result.state == AgentRunState.Completed,
                  result.history.lastOption.contains(result.reply),
                  result.reply.toolUses.isEmpty,
                  count == 1,
                  events.count(_.isInstanceOf[AgentEvent.AgentResultEvent]) == 1,
                  !events.exists(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent])
                )
              )
            yield directAssertion && assertTrue(
              result.history
                .flatMap(_.toolResults)
                .count(_.name.exists(Set("first", "second"))) == 2,
              result.history
                .flatMap(_.toolResults)
                .exists(_.output == secondOutput.map:
                  case ContentBlock.Text(value) if value.nonEmpty =>
                    ContentBlock.Text(s"redacted-$value")
                  case value => value),
              events.count(
                _.isInstanceOf[AgentEvent.ToolResultEndEvent]
              ) == (if batch == Batch.Inline then 3 else 2),
              events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "live restored supplied and partially approved batches preserve direct authority and occurrence order"
    ):
      ZIO
        .foreach(Chunk.fromArray(Entry.values)): entry =>
          for
            executions <- Ref.make(Chunk.empty[String])
            calls      <- Ref.make(0)
            permit     <- Ref.make(false)
            processed  <- Ref.make(Chunk.empty[String])
            tools = Toolkit(
              Chunk("first", "second")
                .map(name =>
                  name -> tool(
                    name,
                    ToolReturnMode.Direct,
                    executions.update(_ :+ name).as(ToolExecutionResult(name))
                  )
                )
                .toMap
            )
            client = LlmClient.mockEffectful((_, _, _) =>
              calls
                .update(_ + 1)
                .as(
                  TurnOutput.ToolCalls(Msg.assistant(Chunk(call("first"), call("second"))))
                )
            )
            authorizer = ToolAuthorizer(request =>
              permit.get.map(allowed =>
                if entry == Entry.Partial && request.call.name == "first" && !allowed then
                  ToolAuthorization.Deferred("approve")
                else ToolAuthorization.Approved(request.call.input)
              )
            )
            processor = ToolResultProcessor(result =>
              processed
                .update(_ :+ result.text)
                .as(
                  ToolResultPayload(Chunk(ContentBlock.Text(s"processed-${result.text}")))
                )
            )
            original <- ReActAgent.make(
              client = client,
              toolkit = tools,
              toolAuthorizer = authorizer,
              toolResultProcessor = processor,
              postReasoningHook = if entry == Entry.Partial then PostReasoningHook.keep else pause
            )
            checkpoint <- original.run(Msg.user("run"))
            _          <- permit.set(true)
            augmented <-
              if entry == Entry.Supplied then
                ZIO.fromEither(
                  PendingActing.withResults(
                    checkpoint,
                    Chunk(
                      SuppliedToolResult(1, ToolExecutionResult("second")),
                      SuppliedToolResult(0, ToolExecutionResult("first"))
                    )
                  )
                )
              else ZIO.succeed(checkpoint)
            decoded <- ZIO.fromEither(augmented.toJson.fromJson[AgentResult])
            result <-
              if entry == Entry.Live then original.resume()
              else
                for
                  restored <- ReActAgent.make(
                    client = client,
                    toolkit = tools,
                    toolAuthorizer = authorizer,
                    toolResultProcessor = processor
                  )
                  _      <- restored.restore(decoded)
                  result <- restored.resume()
                yield result
            rejectedHost <- ReActAgent.make(
              client = client,
              toolkit = Toolkit(
                tools.tools.map((name, value) =>
                  name -> tool(
                    name,
                    ToolReturnMode.Continue,
                    ZIO.succeed(ToolExecutionResult(value.definition.name))
                  )
                )
              )
            )
            rejected <- rejectedHost.restore(decoded).exit
            empty    <- rejectedHost.history
            count    <- calls.get
            done     <- executions.get
            seen     <- processed.get
          yield assertTrue(
            checkpoint.isSuspended,
            result.reply.content == Chunk(
              ContentBlock.Text("processed-first"),
              ContentBlock.Text("processed-second")
            ),
            result.reply.generationReason == GenerateReason.ToolReturnDirect,
            result.history.flatMap(_.toolResults).size == 2,
            result.state == AgentRunState.Completed,
            count == 1,
            done.sorted == (if entry == Entry.Supplied then Chunk.empty
                            else Chunk("first", "second")),
            seen.sorted == Chunk("first", "second"),
            rejected.isFailure && empty.isEmpty
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "processor cleanup and cancellation settle before direct completion while structured modes retain authority"
    ):
      val defect = new IllegalStateException("processor cleanup")
      val cleanup = ZIO.foreach(Chunk.fromArray(Cleanup.values)): mode =>
        ZIO.scoped:
          for
            cleaning            <- Promise.make[Nothing, Unit]
            release             <- Promise.make[Nothing, Unit]
            cancellationReached <- Promise.make[Nothing, Unit]
            awaitCancellation = ZIO.descriptor
              .zip(release.isDone)
              .flatMap:
                case (descriptor, released) =>
                  if descriptor.interrupters.nonEmpty then cancellationReached.succeed(()).as(true)
                  else ZIO.yieldNow.as(released)
              .repeatUntil(identity)
              .unit
            hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
            subscription <- hub.subscribe
            destination  <- ToolEventPort.open(hub)
            agent <- ReActAgent.make(
              client = LlmClient.mockFunction((_, _) =>
                TurnOutput.ToolCalls(Msg.assistant(Chunk(call("first"))))
              ),
              toolkit = Toolkit(
                Map(
                  "first" -> tool(
                    "first",
                    ToolReturnMode.Direct,
                    ZIO.succeed(ToolExecutionResult("raw"))
                  )
                )
              ),
              toolResultProcessor = ToolResultProcessor(result =>
                ZIO
                  .addFinalizer(
                    cleaning.succeed(()) *>
                      (if mode == Cleanup.Cancel then awaitCancellation
                       else ZIO.unit) *> release.await *>
                      (if mode == Cleanup.Cancel then ZIO.die(defect) else ZIO.unit)
                  )
                  .as(ToolResultPayload.fromResult(result))
              )
            )
            checked <- (for
              running     <- agent.runForwarding(Msg.user("run"), destination, "direct").forkScoped
              _           <- cleaning.await
              held        <- running.poll
              during      <- agent.history
              interrupter <- ZIO.fiberId
              _ <- ZIO.when(mode == Cleanup.Cancel)(
                running.interruptAsFork(interrupter) *> cancellationReached.await
              )
              _       <- release.succeed(())
              exit    <- running.await
              history <- agent.history
              events  <- subscription.takeAll
            yield assertTrue(
              held.isEmpty,
              !during.exists(_.generationReason == GenerateReason.ToolReturnDirect)
            ) &&
              (mode match
                case Cleanup.Complete =>
                  assertTrue(exit match
                    case Exit.Success(result) =>
                      result.reply.generationReason == GenerateReason.ToolReturnDirect
                    case Exit.Failure(_) => false)
                case Cleanup.Cancel =>
                  assertTrue(
                    exit.isInterrupted,
                    exit.causeOption.exists(_.defects == List(defect)),
                    !history.exists(_.generationReason == GenerateReason.ToolReturnDirect),
                    !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent])
                  ))).ensuring(release.succeed(()).unit)
          yield checked
      val structured = ZIO.foreach(
        Chunk(
          StructuredOutputCapability.Unsupported,
          StructuredOutputCapability.Native(NativeToolSupport.Supported)
        )
      ): capability =>
        for
          calls <- Ref.make(0)
          agent <- ReActAgent.make(
            client = LlmClient.mockEffectful((_, _, _) =>
              calls
                .getAndUpdate(_ + 1)
                .map:
                  case 0 => TurnOutput.ToolCalls(Msg.assistant(Chunk(call("first"))))
                  case _ =>
                    capability match
                      case StructuredOutputCapability.Unsupported =>
                        TurnOutput.ToolCalls(
                          Msg.assistant(
                            Chunk(
                              ContentBlock.ToolUse(
                                "capture",
                                "generate_response",
                                Json.Obj("response" -> Json.Obj("value" -> Json.Num(7)))
                              )
                            )
                          )
                        )
                      case _ => TurnOutput.FinalText(Msg.assistant("{\"value\":7}"))
            ),
            llmConfig = LlmConfig("structured", structuredOutputCapability = capability),
            toolkit = Toolkit(
              Map(
                "first" -> tool(
                  "first",
                  ToolReturnMode.Direct,
                  ZIO.succeed(ToolExecutionResult("observation"))
                )
              )
            )
          )
          result <- agent
            .runStructured[Answer](Chunk(Msg.user("run")), Json.Obj("type" -> Json.Str("object")))
          count <- calls.get
        yield assertTrue(
          result._2 == Answer(7),
          count == 2,
          !result._1.history.exists(_.generationReason == GenerateReason.ToolReturnDirect)
        )
      cleanup.zipWith(structured)(_ ++ _).map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(15.seconds)
