/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.harness.questions

import com.weisgnosis.agents.core.agent.{
  AgentResult,
  AgentRunState,
  PendingActing,
  ReActAgent,
  SuppliedToolResult
}
import com.weisgnosis.agents.core.client.{ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object AskUserToolSpec extends ZIOSpecDefault:
  private val question: UserQuestion = UserQuestion(
    "color",
    "Which color should the report use?",
    "Color",
    Chunk(QuestionOption("blue", "Blue"), QuestionOption("green", "Green"))
  )
  private val host: QuestionHostBinding = QuestionHostBinding("session-one", "checkpoint-one")
  private val selected: Chunk[UserAnswer] = Chunk(
    UserAnswer("color", AnswerValue.Selected(Chunk("blue")))
  )

  private def call(input: Json, name: String = "AskUser"): ContentBlock.ToolUse =
    ContentBlock.ToolUse("repeated-call-id", name, input)

  private def supplied(
      value: Either[AgentError, SuppliedToolResult]
  ): IO[AgentError, SuppliedToolResult] = ZIO.fromEither(value)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AskUserToolSpec")(
    test("checked construction and decoding enforce identities, choices and payload bounds") {
      val invalid = Chunk(
        Chunk.empty,
        Chunk.fill(5)(question),
        Chunk(question, question.copy(text = "Different text")),
        Chunk(question, question.copy(id = "other")),
        Chunk(question.copy(id = "not an id")),
        Chunk(question.copy(id = "x" * 65)),
        Chunk(question.copy(text = " ")),
        Chunk(question.copy(text = "x" * 2001)),
        Chunk(question.copy(header = "x" * 13)),
        Chunk(question.copy(options = question.options.take(1))),
        Chunk(question.copy(options = Chunk.fill(5)(QuestionOption("x", "X")))),
        Chunk(question.copy(options = question.options.map(_.copy(id = "same")))),
        Chunk(question.copy(options = question.options.map(_.copy(label = "same")))),
        Chunk(question.copy(options = question.options.map(_.copy(label = "x" * 201)))),
        Chunk(question.copy(options = question.options.map(_.copy(description = "x" * 2001)))),
        Chunk(question.copy(context = Some("x" * 8001))),
        Chunk(question.copy(preview = Some("x" * 8001))),
        Chunk(question.copy(mode = QuestionMode.Multiple, preview = Some("preview"))),
        Chunk(question.copy(text = "\uD800")),
        Chunk.fromIterable(
          (1 to 4).map(index =>
            question.copy(
              id = s"q$index",
              text = s"Question $index",
              context = Some("x" * 8000),
              preview = Some("x" * 8000),
              options = Chunk.fromIterable(
                (1 to 4).map(option => QuestionOption(s"o$option", s"Option $option", "x" * 2000))
              )
            )
          )
        )
      )
      for
        params      <- ZIO.fromEither(AskUserParams.make(Chunk(question)))
        roundtrip   <- ZIO.fromEither(params.toJson.fromJson[AskUserParams])
        invalidWire <- ZIO.fromEither(AskUserParams.Wire(2, params.questions).toJsonAST)
      yield assertTrue(
        invalid.forall(AskUserParams.make(_).isLeft),
        roundtrip.questions == params.questions,
        invalidWire.as[AskUserParams].isLeft,
        Json
          .Obj("version" -> Json.Num(1), "version" -> Json.Num(1), "questions" -> Json.Arr())
          .as[AskUserParams]
          .isLeft
      )
    },
    test("restored repeated call IDs accept occurrence answers without asking the model again") {
      for
        params <- ZIO.fromEither(AskUserParams.make(Chunk(question)))
        tool   <- ZIO.fromEither(AskUserTool.make())
        other <- ZIO.fromEither(
          ToolBinding.external[Json](ToolDef("other", "Other host work", Json.Obj()))
        )
        toolkit <- ZIO.fromEither(Toolkit.single(tool.binding).register(other))
        initialClient <- ScriptedLlmClient.make(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(call(params.json), call(params.json), call(Json.Obj(), "other")))
            )
          ),
          1
        )
        initial     <- ReActAgent.make(client = initialClient, toolkit = toolkit)
        checkpoint  <- initial.run(Msg.user("Ask two preference batches and perform host work."))
        restored    <- ZIO.fromEither(checkpoint.toJson.fromJson[AgentResult])
        first       <- ZIO.fromEither(tool.pending(restored, 0, host))
        second      <- ZIO.fromEither(tool.pending(restored, 1, host))
        firstAnswer <- supplied(tool.answer(restored, host, first.key, selected))
        resumedClient <- ScriptedLlmClient.make(
          Chunk(TurnOutput.FinalText(Msg.assistant("Blue report prepared."))),
          1
        )
        fresh                    <- ReActAgent.make(client = resumedClient, toolkit = toolkit)
        _                        <- fresh.restore(restored)
        partial                  <- fresh.resume(Chunk(firstAnswer))
        requestsBeforeCompletion <- resumedClient.requests
        secondAnswer             <- supplied(tool.answer(partial, host, second.key, selected))
        pendingCalls             <- ZIO.fromEither(PendingActing.pendingCalls(partial))
        completed <- fresh.resume(
          Chunk(
            secondAnswer,
            SuppliedToolResult(2, ToolExecutionResult("Other host work completed"))
          )
        )
        finalRequests <- resumedClient.requests
        _             <- initialClient.verifyConsumed *> resumedClient.verifyConsumed
      yield assertTrue(
        checkpoint.isSuspended,
        checkpoint.history.flatMap(_.toolUses).size == 3,
        checkpoint.history.flatMap(_.toolResults).isEmpty,
        first.key != second.key,
        firstAnswer.callOrdinal == 0,
        secondAnswer.callOrdinal == 1,
        partial.isSuspended,
        pendingCalls.map(_.callOrdinal) == Chunk(1, 2),
        requestsBeforeCompletion.isEmpty,
        tool.answer(partial, host, first.key, selected).isLeft,
        completed.state == AgentRunState.Completed,
        completed.history.flatMap(_.toolResults).size == 3,
        finalRequests.size == 1,
        finalRequests.forall(
          _.input.history
            .flatMap(_.toolResults)
            .count(_.metadata.contains(AskUserTool.AnswersMetadata)) == 2
        ),
        firstAnswer.result.output.contains("Blue"),
        firstAnswer.result.payload.metadata
          .get(AskUserTool.AnswersMetadata)
          .exists(_.toJson.contains("blue"))
      )
    },
    test("invalid and denied question calls never become host handoffs") {
      ZIO
        .foreach(Chunk(true, false))(invalid =>
          for
            params    <- ZIO.fromEither(AskUserParams.make(Chunk(question)))
            tool      <- ZIO.fromEither(AskUserTool.make())
            decisions <- Ref.make(0)
            client <- ScriptedLlmClient.make(
              Chunk(
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    Chunk(
                      call(
                        if invalid then
                          Json.Obj("version" -> Json.Num(1), "questions" -> Json.Arr())
                        else params.json
                      )
                    )
                  )
                ),
                TurnOutput.FinalText(Msg.assistant("No question was handed off."))
              ),
              2
            )
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit.single(tool.binding),
              toolAuthorizer = ToolAuthorizer(_ =>
                decisions.update(_ + 1).as(ToolAuthorization.Denied("Host policy denies questions"))
              )
            )
            result <- agent.run(Msg.user("Ask a question."))
            count  <- decisions.get
            _      <- client.verifyConsumed
          yield assertTrue(
            tool.binding.isReadOnly,
            tool.binding.isConcurrencySafe,
            result.state == AgentRunState.Completed,
            tool.pending(result, 0, host).isLeft,
            result.history.flatMap(_.toolResults).map(_.state) == Chunk(
              if invalid then ToolResultState.Error else ToolResultState.Denied
            ),
            count == (if invalid then 0 else 1)
          )
        )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "answer validation rejects stale, foreign, missing and invalid choices without changing checkpoint"
    ) {
      for
        params <- ZIO.fromEither(
          AskUserParams.make(
            Chunk(
              question,
              question.copy(id = "features", text = "Which features?", mode = QuestionMode.Multiple)
            )
          )
        )
        tool <- ZIO.fromEither(AskUserTool.make())
        client <- ScriptedLlmClient.make(
          Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call(params.json))))),
          1
        )
        agent      <- ReActAgent.make(client = client, toolkit = Toolkit.single(tool.binding))
        checkpoint <- agent.run(Msg.user("Collect preferences."))
        waiting    <- ZIO.fromEither(tool.pending(checkpoint, 0, host))
        invalid = Chunk(
          Chunk.empty,
          selected,
          selected ++ selected,
          selected :+ UserAnswer("unknown", AnswerValue.Other("answer")),
          Chunk(
            UserAnswer("color", AnswerValue.Selected(Chunk("blue", "green"))),
            UserAnswer("features", AnswerValue.Other("text"))
          ),
          Chunk(
            UserAnswer("color", AnswerValue.Selected(Chunk("missing"))),
            UserAnswer("features", AnswerValue.Other("text"))
          ),
          selected :+ UserAnswer("features", AnswerValue.Selected(Chunk.empty)),
          selected :+ UserAnswer("features", AnswerValue.Selected(Chunk("blue", "blue"))),
          selected :+ UserAnswer("features", AnswerValue.Other(" ")),
          selected :+ UserAnswer("features", AnswerValue.Other("x" * 8001))
        )
        validMultiple <- supplied(
          tool.answer(
            checkpoint,
            host,
            waiting.key,
            selected :+ UserAnswer("features", AnswerValue.Selected(Chunk("blue", "green")))
          )
        )
        validOther <- supplied(
          tool.answer(
            checkpoint,
            host,
            waiting.key,
            selected :+ UserAnswer("features", AnswerValue.Other("Use the accessible preset."))
          )
        )
        history <- agent.history
      yield assertTrue(
        invalid.forall(answers => tool.answer(checkpoint, host, waiting.key, answers).isLeft),
        tool.answer(checkpoint, host.copy(checkpointId = "foreign"), waiting.key, selected).isLeft,
        tool.answer(checkpoint, host, waiting.key.copy(requestId = "stale"), selected).isLeft,
        tool
          .answer(checkpoint, host, waiting.key.copy(questionBatch = Json.Obj()), selected)
          .isLeft,
        validMultiple.result.output.contains("Blue, Green"),
        validOther.result.output.contains("Use the accessible preset."),
        history == checkpoint.history,
        checkpoint.history.flatMap(_.toolResults).isEmpty
      )
    }
  )
