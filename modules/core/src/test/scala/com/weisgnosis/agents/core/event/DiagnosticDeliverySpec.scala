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
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.agent.AgentRunUpdate
import com.weisgnosis.agents.core.error.AgentError.EventDeliveryFailure
import com.weisgnosis.agents.core.json.ExactJson
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object DiagnosticDeliverySpec extends ZIOSpecDefault:
  private val event: AgentEvent = AgentEvent.TextBlockDeltaEvent("escaped \" Unicode 🌍", 0)
  private enum DeliveryCase:
    case Raw, RawObserved, BareStream, ProjectedStream

  private def drain(
      read: UIO[Option[AgentEvent]],
      retained: List[AgentEvent] = Nil
  ): UIO[Chunk[AgentEvent]] =
    read.flatMap:
      case Some(event) => drain(read, event :: retained)
      case None        => ZIO.succeed(Chunk.fromIterable(retained.reverse))

  def spec: Spec[TestEnvironment, Any] = suite("DiagnosticDeliverySpec")(
    suite("concurrent FIFO delivery preserves every occurrence through raw reads and streams")(
      DeliveryCase.values.toList.map { scenario =>
        test(scenario.toString) {
          val expected = Chunk
            .fromIterable(0 until 2000)
            .map(index => AgentEvent.TextBlockDeltaEvent(index.toString, 0))
          ZIO
            .foreach(0 until 20): _ =>
              ZIO.scoped:
                for
                  budget <- ZIO.fromEither(
                    EventDeliveryBudget.fromLimits(expected.size + 8, 2L * 1024 * 1024, 4096)
                  )
                  delivery <- DiagnosticDelivery.make(budget)
                  reading  <- Promise.make[Nothing, Unit]
                  trace <-
                    if scenario == DeliveryCase.RawObserved then
                      Ref.make(Vector.empty[(AgentEvent, EventAdmission)]).map(Some(_))
                    else ZIO.none
                  publish = trace match
                    case None => delivery.publisher.publish
                    case Some(recorded) =>
                      (event: AgentEvent) =>
                        delivery.publisher
                          .publish(event)
                          .tap(admission => recorded.update(_ :+ (event -> admission)))
                  producer <- ZIO
                    .foreachDiscard(expected.zipWithIndex) { case (event, index) =>
                      publish(event) *> ZIO.when(index == 0)(reading.await)
                    }
                    .ensuring(delivery.finish)
                    .forkScoped
                  read = delivery.take.tap(value => ZIO.when(value.nonEmpty)(reading.succeed(())))
                  actual <- scenario match
                    case DeliveryCase.Raw | DeliveryCase.RawObserved => drain(read)
                    case DeliveryCase.BareStream =>
                      ZStream
                        .repeatZIOOption(read.flatMap:
                          case Some(event) => ZIO.succeed(event)
                          case None        => ZIO.fail(None))
                        .runCollect
                    case DeliveryCase.ProjectedStream =>
                      (ZStream
                        .repeatZIOOption(read.flatMap:
                          case Some(event) => ZIO.succeed(AgentRunUpdate.Event(event))
                          case None        => ZIO.fail(None))
                        ++ ZStream.succeed(AgentRunUpdate.Result(()))).collect {
                        case AgentRunUpdate.Event(event) => event
                      }.runCollect
                  _        <- producer.join
                  failure  <- delivery.failure
                  recorded <- ZIO.foreach(trace)(_.get)
                  producerExact = recorded.forall(entries =>
                    entries.map(_._1) == expected.toVector && entries.forall(
                      _._2 == EventAdmission.Accepted
                    )
                  )
                  producerCount = recorded.map(_.size)
                  producerMismatch = recorded.flatMap(
                    _.zipWithIndex
                      .find { case ((value, admission), index) =>
                        !expected
                          .lift(index)
                          .contains(value) || admission != EventAdmission.Accepted
                      }
                      .map { case (entry, index) => (index, entry, expected.lift(index)) }
                  )
                  exactMatch    = actual == expected
                  actualCount   = actual.size
                  expectedCount = expected.size
                  firstMismatch = actual.zipWithIndex
                    .find { case (value, index) =>
                      !expected.lift(index).contains(value)
                    }
                    .map { case (value, index) => (index, value, expected.lift(index)) }
                yield assertTrue(
                  exactMatch,
                  actualCount == expectedCount,
                  firstMismatch.isEmpty,
                  producerExact,
                  producerCount.forall(_ == expectedCount),
                  producerMismatch.isEmpty,
                  failure.isEmpty
                )
            .map(_.foldLeft(assertTrue(true))(_ && _))
        }
      }*
    ),
    test("host limits and decoded limits reject invalid configurations") {
      val invalid = List((0, 10L, 1L), (1, 0L, 1L), (1, 10L, 0L), (1, 10L, 11L))
      assertTrue(
        invalid.forall { case (count, queued, single) =>
          EventDeliveryBudget.fromLimits(count, queued, single).isLeft
        },
        """{"maxEvents":0,"maxQueuedBytes":10,"maxEventBytes":1}"""
          .fromJson[EventDeliveryBudget]
          .isLeft,
        EventDeliveryBudget.default.toJson
          .fromJson[EventDeliveryBudget]
          .exists(_.maxQueuedBytes == EventDeliveryBudget.default.maxQueuedBytes)
      )
    },
    test("parallel publications cannot oversubscribe count and first rejection closes admission") {
      for
        budget   <- ZIO.fromEither(EventDeliveryBudget.fromLimits(8, 100000L, 10000L))
        delivery <- DiagnosticDelivery.make(budget)
        statuses <- ZIO.foreachPar(1 to 64)(_ => delivery.publisher.publish(event))
        failure  <- delivery.failure
        _        <- delivery.finish
        prefix   <- ZIO.collectAll(List.fill(9)(delivery.take))
        late     <- delivery.publisher.publish(event)
      yield assertTrue(
        statuses.count(_ == EventAdmission.Accepted) == 8,
        statuses.count {
          case EventAdmission.Rejected(_) => true
          case _                          => false
        } == 1,
        failure.exists(_.reason == EventDeliveryFailure.CapacityExhausted),
        prefix.flatten == List.fill(8)(event),
        prefix.lastOption.contains(None),
        late == EventAdmission.Closed
      )
    },
    test("exact encoded UTF-8 reservations are released on a single dequeue") {
      for
        json <- ZIO.fromEither(ExactJson.encode(event))
        bytes = json.getBytes(StandardCharsets.UTF_8).length.toLong
        budget   <- ZIO.fromEither(EventDeliveryBudget.fromLimits(2, bytes, bytes))
        delivery <- DiagnosticDelivery.make(budget)
        first    <- delivery.publisher.publish(event)
        taken    <- delivery.take
        second   <- delivery.publisher.publish(event)
        overflow <- delivery.publisher.publish(event)
        _        <- delivery.finish
        retained <- delivery.take
        end      <- delivery.take
      yield assertTrue(
        first == EventAdmission.Accepted,
        taken.contains(event),
        second == EventAdmission.Accepted,
        overflow match
          case EventAdmission.Rejected(reason) =>
            reason.reason == EventDeliveryFailure.CapacityExhausted
          case _ => false,
        retained.contains(event),
        end.isEmpty
      )
    },
    test(
      "source transformation is charged before admission and individual limits reject oversize events"
    ) {
      for
        json <- ZIO.fromEither(ExactJson.encode(event))
        bytes = json.getBytes(StandardCharsets.UTF_8).length.toLong
        budget   <- ZIO.fromEither(EventDeliveryBudget.fromLimits(2, bytes * 2, bytes))
        delivery <- DiagnosticDelivery.make(budget)
        result   <- delivery.publisher.contramap(_.withSource("forwarded")).publish(event)
        _        <- delivery.finish
        end      <- delivery.take
      yield assertTrue(
        result match
          case EventAdmission.Rejected(reason) =>
            reason.reason == EventDeliveryFailure.CapacityExhausted
          case _ => false,
        end.isEmpty
      )
    },
    test(
      "invalid Unicode rejects diagnostics without replacing the first failure or accepted FIFO prefix"
    ) {
      for
        delivery     <- DiagnosticDelivery.make(EventDeliveryBudget.default)
        accepted     <- delivery.publisher.publish(event)
        rejected     <- delivery.publisher.publish(AgentEvent.ErrorEvent("\uD800"))
        first        <- delivery.failure
        closed       <- delivery.publisher.publish(event)
        _            <- delivery.finish
        retained     <- delivery.take
        end          <- delivery.take
        finalFailure <- delivery.failure
      yield assertTrue(
        accepted == EventAdmission.Accepted,
        rejected match
          case EventAdmission.Rejected(reason) =>
            reason.reason == EventDeliveryFailure.EncodingFailure
          case _ => false,
        first == finalFailure,
        closed == EventAdmission.Closed,
        retained.contains(event),
        end.isEmpty
      )
    },
    test("encoding validates Unicode in nested values and object keys before UTF-8 conversion") {
      val invalid: List[AgentEvent] = List(
        AgentEvent.CustomEvent("nested", Json.Obj("value" -> Json.Arr(Json.Str("\uDFFF")))),
        AgentEvent.CustomEvent("key", Json.Obj("\uD800" -> Json.Str("value")))
      )
      ZIO
        .foreach(invalid)(value =>
          DiagnosticDelivery.make(EventDeliveryBudget.default).flatMap(_.publisher.publish(value))
        )
        .map(statuses =>
          assertTrue(statuses.forall {
            case EventAdmission.Rejected(reason) =>
              reason.reason == EventDeliveryFailure.EncodingFailure
            case _ => false
          })
        )
    },
    test("abandonment clears the backlog and wakes a waiting reader without a forwarding worker") {
      ZIO.scoped {
        for
          delivery  <- DiagnosticDelivery.make(EventDeliveryBudget.default)
          reader    <- delivery.take.forkScoped
          _         <- delivery.abandon
          end       <- reader.join
          closed    <- delivery.publisher.publish(event)
          discarded <- EventPublisher.discard.publish(event)
          filled    <- DiagnosticDelivery.make(EventDeliveryBudget.default)
          _         <- filled.publisher.publish(event)
          _         <- filled.abandon
          cleared   <- filled.take
        yield assertTrue(
          end.isEmpty,
          cleared.isEmpty,
          closed == EventAdmission.Closed,
          discarded == EventAdmission.Closed
        )
      }
    }
  )
