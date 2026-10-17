/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.harness.questions

import com.weisgnosis.agents.core.agent.{
  AgentResult,
  PendingActing,
  PendingActingStage,
  SuppliedToolResult
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  ToolBinding,
  ToolDef,
  ToolExecutionResult,
  ToolResultPayload,
  ToolSchema
}
import zio.Chunk
import zio.json.*

/** External preference collection. Existing tool authorization remains authoritative. */
final class AskUserTool private (val binding: ToolBinding.External):
  def pending(
      checkpoint: AgentResult,
      ordinal: Int,
      host: QuestionHostBinding
  ): Either[AgentError, PendingUserQuestions] =
    for
      _ <- check(
        AskUserParams.text(host.sessionId, 2000) && AskUserParams.text(host.checkpointId, 2000),
        "Invalid host question correlation"
      )
      acting <- PendingActing.validate(checkpoint)
      _ <- acting.stage match
        case PendingActingStage.AwaitingResults(_, _, _) => Right(())
        case _ =>
          Left(AgentError.InvalidConfiguration("Questions require an awaiting-results checkpoint"))
      calls <- PendingActing.pendingCalls(checkpoint)
      _ <- calls
        .find(value => value.callOrdinal == ordinal && value.call.name == binding.definition.name)
        .toRight(AgentError.InvalidConfiguration("Question occurrence is not pending"))
      external <- acting.externalRequests
        .find(_.callOrdinal == ordinal)
        .toRight(
          AgentError.InvalidConfiguration(
            "Question occurrence has not been admitted for external execution"
          )
        )
      params <- external.input.as[AskUserParams].left.map(AgentError.InvalidConfiguration(_))
    yield PendingUserQuestions(
      QuestionRequestKey(
        host,
        acting.context.replyId,
        acting.context.requestId,
        ordinal,
        params.json
      ),
      params
    )

  /** Rechecks the current checkpoint; hosts own authenticated delivery and atomic replay claims. */
  def answer(
      checkpoint: AgentResult,
      host: QuestionHostBinding,
      key: QuestionRequestKey,
      answers: Chunk[UserAnswer]
  ): Either[AgentError, SuppliedToolResult] =
    for
      current <- pending(checkpoint, key.callOrdinal, host)
      _       <- check(current.key == key, "Stale or foreign question response")
      questions = current.params.questions
      _ <- check(
        answers.size == questions.size && answers
          .map(_.questionId)
          .distinct
          .size == answers.size && answers.map(_.questionId).toSet == questions.map(_.id).toSet,
        "Every question requires exactly one matching answer"
      )
      resolved <- questions.foldLeft[Either[AgentError, Chunk[AskUserTool.AnsweredQuestion]]](
        Right(Chunk.empty)
      )((collected, question) =>
        for
          values <- collected
          supplied <- answers
            .find(_.questionId == question.id)
            .toRight(AgentError.InvalidConfiguration("Missing question answer"))
          readable <- describe(question, supplied.value)
        yield values :+ AskUserTool.AnsweredQuestion(
          question.id,
          question.text,
          supplied.value,
          readable
        )
      )
      structured <- resolved.toJsonAST.left.map(AgentError.InvalidConfiguration(_))
      payload = ToolResultPayload(
        Chunk(
          ContentBlock.Text(
            resolved.map(value => s"${value.question}: ${value.answer}").mkString("\n")
          )
        ),
        Map(AskUserTool.AnswersMetadata -> structured)
      )
      result = ToolExecutionResult(payload, ToolResultState.Success)
      encoded <- result.toJsonAST.left.map(AgentError.InvalidConfiguration(_))
      _ <- check(
        AskUserParams.boundedJson(encoded),
        "User answers exceed 65536 UTF-8 bytes or contain invalid JSON"
      )
    yield SuppliedToolResult(key.callOrdinal, result)

  private def describe(question: UserQuestion, value: AnswerValue): Either[AgentError, String] =
    value match
      case AnswerValue.Other(text) =>
        check(
          AskUserParams.text(text, 8000),
          "Free-text answers must be nonblank valid Unicode within 8000 characters"
        ).map(_ => text)
      case AnswerValue.Selected(ids) =>
        val allowed = question.options.map(_.id).toSet
        for _ <- check(
            ids.nonEmpty && ids.distinct.size == ids.size && ids.forall(
              allowed.contains
            ) && (question.mode == QuestionMode.Multiple || ids.size == 1),
            "Invalid option selection for question mode"
          )
        yield ids.flatMap(id => question.options.find(_.id == id).map(_.label)).mkString(", ")

  private def check(
      valid: Boolean,
      message: String
  ): Either[AgentError.InvalidConfiguration, Unit] =
    Either.cond(valid, (), AgentError.InvalidConfiguration(message))

object AskUserTool:
  val AnswersMetadata: String = "weisgnosis_user_answers"

  final private[questions] case class AnsweredQuestion(
      questionId: String,
      question: String,
      value: AnswerValue,
      answer: String
  ) derives JsonCodec

  def make(name: String = "AskUser"): Either[AgentError.InvalidConfiguration, AskUserTool] =
    ToolBinding
      .external[AskUserParams](
        ToolDef(
          name,
          "Ask the user a bounded batch of preference questions. This does not authorize other tools.",
          summon[ToolSchema[AskUserParams.Wire]].jsonSchema,
          isConcurrencySafe = true,
          isReadOnly = true
        )
      )
      .map(new AskUserTool(_))
