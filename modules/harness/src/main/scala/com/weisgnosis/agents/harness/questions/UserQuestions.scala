/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.harness.questions

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.tool.ToolSchema
import java.nio.charset.StandardCharsets
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

enum QuestionMode derives JsonCodec:
  case Single, Multiple

final case class QuestionOption(id: String, label: String, description: String = "")
    derives JsonCodec

final case class UserQuestion(
    id: String,
    text: String,
    header: String,
    options: Chunk[QuestionOption],
    mode: QuestionMode = QuestionMode.Single,
    context: Option[String] = None,
    preview: Option[String] = None
) derives JsonCodec

/** Version-one question batches have the same checked construction and decoding boundary. */
final class AskUserParams private (val questions: Chunk[UserQuestion], val json: Json)

object AskUserParams:
  final private[questions] case class Wire(version: Int, questions: Chunk[UserQuestion])
      derives JsonCodec,
        ToolSchema

  private[questions] val maximumBytes: Int = 65536

  private[questions] def boundedJson(value: Json): Boolean =
    JsonBoundary.validate(value).isRight && JsonBoundary.validUnicode(value) &&
      JsonBoundary.render(value).getBytes(StandardCharsets.UTF_8).length <= maximumBytes

  private[questions] def text(value: String, limit: Int, nonblank: Boolean = true): Boolean =
    value.length <= limit && (!nonblank || !value.isBlank) && JsonBoundary.validUnicode(
      Json.Str(value)
    )

  private def identifier(value: String): Boolean = value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")

  def make(questions: Chunk[UserQuestion]): Either[AgentError.InvalidConfiguration, AskUserParams] =
    val valid = questions.nonEmpty && questions.size <= 4 &&
      questions.map(_.id).distinct.size == questions.size &&
      questions.map(_.text.trim).distinct.size == questions.size &&
      questions.forall(question =>
        identifier(question.id) && text(question.text, 2000) && text(question.header, 12) &&
          question.options.size >= 2 && question.options.size <= 4 &&
          question.options.map(_.id).distinct.size == question.options.size &&
          question.options.map(_.label.trim).distinct.size == question.options.size &&
          question.options.forall(option =>
            identifier(option.id) && text(option.label, 200) && text(
              option.description,
              2000,
              false
            )
          ) &&
          question.context.forall(text(_, 8000, false)) && question.preview.forall(
            text(_, 8000, false)
          ) &&
          (question.mode != QuestionMode.Multiple || question.preview.isEmpty)
      )
    for
      _ <- Either.cond(
        valid,
        (),
        AgentError.InvalidConfiguration(
          "Invalid question batch: check identities, choices, text bounds and preview mode"
        )
      )
      raw <- Wire(1, questions).toJsonAST.left.map(AgentError.InvalidConfiguration(_))
      _ <- Either.cond(
        boundedJson(raw),
        (),
        AgentError.InvalidConfiguration(
          "Question batch exceeds 65536 UTF-8 bytes or contains invalid JSON"
        )
      )
    yield new AskUserParams(questions, raw)

  given JsonCodec[AskUserParams] = JsonCodec[Json].transformOrFail(
    raw =>
      for
        _       <- JsonBoundary.validate(raw)
        wire    <- raw.as[Wire]
        _       <- Either.cond(wire.version == 1, (), "Unsupported question batch version")
        checked <- make(wire.questions).left.map(_.message)
        _ <- Either.cond(
          JsonBoundary.sameRepresentation(raw, checked.json),
          (),
          "Question batch must use its canonical version-one fields"
        )
      yield checked,
    _.json
  )

enum AnswerValue derives JsonCodec:
  case Selected(optionIds: Chunk[String])
  case Other(text: String)

final case class UserAnswer(questionId: String, value: AnswerValue) derives JsonCodec

/** Correlation data supplied by the host; it is neither authentication nor an execution claim. */
final case class QuestionHostBinding(sessionId: String, checkpointId: String) derives JsonCodec

final case class QuestionRequestKey(
    host: QuestionHostBinding,
    replyId: String,
    requestId: String,
    callOrdinal: Int,
    questionBatch: Json
) derives JsonCodec

final case class PendingUserQuestions(key: QuestionRequestKey, params: AskUserParams)
