/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.Toolkit
import com.weisgnosis.agents.harness.questions.*
import zio.*
import zio.json.*

/** A scripted host supplies a preference after restoring the original external-tool checkpoint. */
object OfflineUserQuestions extends ZIOAppDefault:
  def run: ZIO[Any, Throwable, Unit] = ZIO.scoped:
    for
      params <- ZIO.fromEither(
        AskUserParams.make(
          Chunk(
            UserQuestion(
              "release_notes",
              "Who should the release notes address?",
              "Audience",
              Chunk(
                QuestionOption("developers", "Developers"),
                QuestionOption("operators", "Operators")
              )
            )
          )
        )
      )
      questions <- ZIO.fromEither(AskUserTool.make())
      asking <- ScriptedLlmClient.make(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(
              Chunk(
                ContentBlock.ToolUse("audience", questions.binding.definition.name, params.json)
              )
            )
          )
        ),
        maxRecordedRequests = 1
      )
      original   <- ReActAgent.make(client = asking, toolkit = Toolkit.single(questions.binding))
      checkpoint <- original.run(Msg.user("Prepare release notes."))
      saved <- ZIO.fromEither(
        checkpoint.toJson.fromJson[AgentResult].left.map(AgentError.InvalidMessage(_))
      )
      host = QuestionHostBinding("release-session", "saved-checkpoint-1")
      pending <- ZIO.fromEither(questions.pending(saved, 0, host))
      _       <- Console.printLine(pending.params.questions.map(_.text).mkString("\n"))
      answered <- ZIO.fromEither(
        questions.answer(
          saved,
          host,
          pending.key,
          Chunk(
            UserAnswer("release_notes", AnswerValue.Selected(Chunk("developers")))
          )
        )
      )
      continuing <- ScriptedLlmClient.make(
        Chunk(
          TurnOutput.FinalText(
            Msg.assistant(
              "Release notes for developers: include the API migration example."
            )
          )
        ),
        maxRecordedRequests = 1
      )
      restored <- ReActAgent.make(client = continuing, toolkit = Toolkit.single(questions.binding))
      _        <- restored.restore(saved)
      result   <- restored.resume(Chunk(answered))
      _        <- asking.verifyConsumed *> continuing.verifyConsumed
      _        <- Console.printLine(result.reply.text)
    yield ()
