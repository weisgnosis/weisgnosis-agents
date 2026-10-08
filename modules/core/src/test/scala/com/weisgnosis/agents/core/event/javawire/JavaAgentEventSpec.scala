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
package com.weisgnosis.agents.core.event.javawire

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, AgentEventType}
import com.weisgnosis.agents.core.json.JsonBoundary
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

private[javawire] object JavaEventFixtures:
  final case class Golden(name: String, kind: String, input: Json, output: Json) derives JsonCodec
  final private case class Document(pin: String, fixtures: Chunk[Golden]) derives JsonCodec

  def read: IO[AgentError, Chunk[Golden]] = ZIO.scoped:
    ZIO
      .fromAutoCloseable(
        ZIO
          .attempt(Option(getClass.getResourceAsStream("/java-event-wire-fixtures.json")))
          .flatMap(value =>
            ZIO
              .fromOption(value)
              .orElseFail(AgentError.InvalidConfiguration("Missing pinned Java event fixtures"))
          )
      )
      .flatMap(stream =>
        ZIO.attemptBlocking(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
      )
      .flatMap(text =>
        ZIO.fromEither(text.fromJson[Document]).mapError(AgentError.InvalidConfiguration(_))
      )
      .flatMap(value =>
        ZIO
          .fail(AgentError.InvalidConfiguration("Unexpected fixture source pin"))
          .unless(value.pin == "f98a685fdc4589f81e626e1686ac2c63d14a2304")
          .as(value.fixtures)
      )
      .mapError(error =>
        AgentError.InvalidConfiguration(
          Option(error.getMessage).getOrElse("Java fixture read failed")
        )
      )

  def same(actual: Either[String, Json], expected: Json): Boolean =
    actual.exists(JsonBoundary.sameRepresentation(expected, _))

object JavaAgentEventSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("JavaAgentEventSpec")(
    test("all pinned Java event DTOs preserve actual Jackson output, including nested payloads"):
      JavaEventFixtures.read.map: fixtures =>
        val events = fixtures.filter(_.kind == "event")
        val tags = events
          .flatMap(_.output match
            case Json.Obj(fields) => fields.collect { case ("type", Json.Str(tag)) => tag }
            case _                => Chunk.empty)
          .toSet
        val checked = events.map: fixture =>
          val encoded = JavaAgentEvent
            .decode(JsonBoundary.render(fixture.output))
            .flatMap(JavaAgentEvent.encode)
            .flatMap(value =>
              JsonBoundary.decode(value).left.map(AgentError.DeserializationError(_))
            )
          (fixture.name, encoded.exists(JsonBoundary.sameRepresentation(fixture.output, _)))
        val failedNames = checked.collect { case (name, false) => name }
        assertTrue(
          events.size == 34,
          tags == Chunk.fromArray(AgentEventType.values).map(_.wireName).toSet,
          failedNames.isEmpty
        )
    ,
    test(
      "missing identity, ambiguous JSON, invalid nested payloads and unknown types fail explicitly"
    ):
      val invalid = Chunk(
        "{\"type\":\"AGENT_END\",\"createdAt\":\"time\"}",
        """{"type":"AGENT_END","id":" ","createdAt":"time"}""",
        "{\"type\":\"UNKNOWN\",\"id\":\"id\",\"createdAt\":\"time\"}",
        "{\"type\":\"AGENT_END\",\"id\":\"one\",\"id\":\"two\",\"createdAt\":\"time\"}",
        "{\"type\":\"AGENT_END\",\"id\":\"id\",\"createdAt\":\"time\"} false",
        "{\"type\":\"REQUIRE_USER_CONFIRM\",\"id\":\"id\",\"createdAt\":\"time\",\"toolCalls\":[{\"type\":\"text\",\"text\":\"wrong\"}]}",
        "{\"type\":\"REQUEST_STOP\",\"id\":\"id\",\"createdAt\":\"time\",\"generateReason\":\"unknown\"}"
      )
      val emptyDataEncode = JavaEventEnvelope
        .make("id", "time")
        .flatMap(envelope =>
          JavaAgentEvent.encode(
            JavaAgentEvent(
              envelope,
              JavaEventPayload.ToolResultDataDelta(
                Some("reply"),
                Some("call"),
                Some("tool"),
                Some(JavaContentBlock.Data(JavaContentSource.Url("https://example.invalid"), ""))
              )
            )
          )
        )
      val invalidUnicode = JavaEventEnvelope.make("id", "time", Some("\ud800"))
      assertTrue(
        invalid.forall(JavaAgentEvent.decode(_).isLeft),
        invalidUnicode.isLeft,
        emptyDataEncode.isLeft
      )
    ,
    test("explicit construction is repeatable and never changes canonical Scala event JSON"):
      val result =
        for
          envelope <- JavaEventEnvelope.make(
            "caller-id",
            "caller-time",
            metadata = Map("decimal" -> Json.Num(BigDecimal("-9223372036854775808")))
          )
          event = JavaAgentEvent(
            envelope,
            JavaEventPayload.TextBlockDelta(Some("reply"), Some("block"), Some("fragment"))
          )
          first   <- JavaAgentEvent.encode(event)
          second  <- JavaAgentEvent.encode(event)
          decoded <- JavaAgentEvent.decode(first)
        yield first == second && decoded.envelope.id == "caller-id" &&
          decoded.envelope.createdAt == "caller-time" &&
          decoded.envelope.metadata == envelope.metadata && first.contains("-9223372036854775808")
      val exactValues = Map(
        "minimum"  -> Json.Num(BigDecimal("-9223372036854775808")),
        "neighbor" -> Json.Num(BigDecimal("-9223372036854775808.000000000000000001"))
      )
      val nestedExact = for
        envelope <- JavaEventEnvelope.make("id", "time")
        encoded <- JavaAgentEvent.encode(
          JavaAgentEvent(envelope, JavaEventPayload.Custom(Some("numbers"), exactValues))
        )
        decoded <- JavaAgentEvent.decode(encoded)
      yield decoded.payload == JavaEventPayload.Custom(Some("numbers"), exactValues)
      val canonical = AgentEvent.TextBlockDeltaEvent("fragment", 0).toJsonAST
      val canonicalPreserved = canonical.exists:
        case Json.Obj(fields) =>
          fields.contains("event" -> Json.Str("text_block_delta")) &&
          !fields.exists(_._1 == "type")
        case _ => false
      assertTrue(result == Right(true), nestedExact == Right(true), canonicalPreserved)
  )
