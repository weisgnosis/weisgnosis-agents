/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolCallState}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Complete ordered output items remain usable only with their original model and projection. */
private[client] object ResponsesNativeReplay:
  def attach(message: Msg, model: String, items: Chunk[Json.Obj]): Msg =
    message.withMetadata(
      Msg.MetadataResponsesNative,
      Json.Obj(
        "version" -> Json.Num(1),
        "model"   -> Json.Str(model),
        "items"   -> Json.Arr(items)
      )
    )

  def content(message: Msg, model: String): Either[AgentError, Option[Chunk[Json.Obj]]] =
    message.metadata.get(Msg.MetadataResponsesNative) match
      case None => Right(None)
      case Some(value) =>
        for
          _ <- JsonBoundary.validate(value).left.map(AgentError.InvalidMessage(_))
          _ <- ensure(JsonBoundary.validUnicode(value), "Malformed Unicode in Responses replay")
          envelope <- obj(value)
          _ <- ensure(
            envelope.get("version").contains(Json.Num(1)),
            "Unsupported Responses replay version"
          )
          storedModel <- string(envelope, "model")
          _           <- ensure(storedModel == model, "Responses replay belongs to another model")
          raw         <- array(envelope, "items")
          items       <- traverse(raw)(obj)
          canonical   <- canonicalBlocks(items)
          _ <- ensure(
            normalize(canonical) == normalize(message.content),
            "Responses replay differs from canonical content"
          )
        yield Some(items)

  def canonicalBlocks(items: Chunk[Json.Obj]): Either[AgentError, Chunk[ContentBlock]] =
    for
      _ <- traverse(items)(item =>
        ensure(
          item.get("status").forall(_ == Json.Str("completed")),
          "Responses replay contains an unfinished item"
        )
      )
      blocks <- traverse(items)(canonicalItem)
      ids = blocks.flatten.collect { case call: ContentBlock.ToolUse => call.id }
      _       <- ensure(ids.distinct.size == ids.size, "Duplicate Responses call_id")
      itemIds <- traverse(items)(string(_, "id"))
      _       <- ensure(itemIds.distinct.size == itemIds.size, "Duplicate Responses item ID")
      hasRefusal = items.exists(
        _.get("content")
          .flatMap(_.asArray)
          .exists(
            _.exists(value => value.asObject.exists(_.get("type").contains(Json.Str("refusal"))))
          )
      )
      _ <- ensure(!hasRefusal || ids.isEmpty, "Responses refusal cannot authorize function calls")
    yield blocks.flatten

  def canonicalItem(item: Json.Obj): Either[AgentError, Chunk[ContentBlock]] =
    for
      _ <- string(item, "id").flatMap(value =>
        ensure(value.nonEmpty, "Responses item ID must not be empty")
      )
      kind <- string(item, "type")
      blocks <- kind match
        case "message" =>
          for
            role  <- string(item, "role")
            _     <- ensure(role == "assistant", "Responses output message must be assistant")
            parts <- array(item, "content")
            values <- traverse(parts)(value =>
              obj(value).flatMap(part =>
                string(part, "type").flatMap:
                  case "output_text" => string(part, "text").map(ContentBlock.Text(_))
                  case "refusal"     => string(part, "refusal").map(ContentBlock.Text(_))
                  case _             => Left(invalid("Unsupported Responses message content"))
              )
            )
          yield values
        case "reasoning" =>
          for
            summaries <- array(item, "summary")
            text <- traverse(summaries)(value =>
              obj(value).flatMap(part =>
                ensure(
                  part.get("type").contains(Json.Str("summary_text")),
                  "Unsupported reasoning summary"
                ).flatMap(_ => string(part, "text"))
              )
            )
            _ <- item.get("encrypted_content") match
              case None | Some(Json.Null) | Some(_: Json.Str) => Right(())
              case _ => Left(invalid("Responses encrypted reasoning must be a string"))
            _ <- item.get("content") match
              case None => Right(())
              case Some(value) =>
                value.asArray
                  .toRight(invalid("Invalid reasoning content"))
                  .flatMap(parts =>
                    traverse(parts)(part =>
                      obj(part).flatMap(value =>
                        ensure(
                          value.get("type").contains(Json.Str("reasoning_text")),
                          "Unsupported reasoning content"
                        ).flatMap(_ => string(value, "text"))
                      )
                    ).map(_ => ())
                  )
          yield Chunk(
            ContentBlock.Thinking(
              text.mkString("\n"),
              Map(Msg.MetadataResponsesReasoning -> Json.Bool(true))
            )
          )
        case "function_call" =>
          for
            id   <- string(item, "call_id")
            name <- string(item, "name")
            _ <- ensure(
              id.nonEmpty && name.matches("[A-Za-z0-9_-]{1,64}"),
              "Invalid Responses function identity"
            )
            raw <- string(item, "arguments")
            arguments <- JsonBoundary
              .decode(if raw.isEmpty then "{}" else raw)
              .left
              .map(AgentError.InvalidMessage(_))
            _ <- ensure(
              arguments.isInstanceOf[Json.Obj],
              "Responses function arguments must be an object"
            )
          yield Chunk(ContentBlock.ToolUse(id, name, arguments))
        case _ => Left(invalid("Unsupported Responses output item"))
    yield blocks

  private def normalize(blocks: Chunk[ContentBlock]): Chunk[ContentBlock] = blocks.map:
    case call: ContentBlock.ToolUse => call.copy(content = None, state = ToolCallState.Pending)
    case other                      => other

  def obj(value: Json): Either[AgentError, Json.Obj] =
    value.asObject.toRight(invalid("Expected Responses object"))
  def string(value: Json.Obj, key: String): Either[AgentError, String] =
    value.get(key).flatMap(_.asString).toRight(invalid(s"Missing Responses string: $key"))
  def array(value: Json.Obj, key: String): Either[AgentError, Chunk[Json]] =
    value.get(key).flatMap(_.asArray).toRight(invalid(s"Missing Responses array: $key"))
  def integer(value: Json.Obj, key: String): Either[AgentError, Int] =
    value
      .get(key)
      .toRight(invalid(s"Missing Responses integer: $key"))
      .flatMap(number =>
        number
          .as[Int]
          .left
          .map(invalid)
          .flatMap(count => ensure(count >= 0, "Negative Responses index/count").map(_ => count))
      )
  def ensure(condition: Boolean, message: String): Either[AgentError, Unit] =
    Either.cond(condition, (), invalid(message))
  def invalid(message: String): AgentError.InvalidMessage = AgentError.InvalidMessage(message)
  def traverse[A, B](
      values: Chunk[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Chunk[B]] =
    values.foldLeft[Either[AgentError, Chunk[B]]](Right(Chunk.empty))((previous, value) =>
      for
        accumulated <- previous
        next        <- f(value)
      yield accumulated :+ next
    )
