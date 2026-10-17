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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.test.*

object ScriptedLlmClientSpec extends ZIOSpecDefault:
  private val answer: TurnOutput = TurnOutput.FinalText(Msg.assistant("answer"))
  private val config: LlmConfig = LlmConfig(
    "scripted",
    endpoint = "https://private.invalid",
    apiKey = Some("private-key"),
    maxTokens = Some(42),
    temperaturePolicy = TemperaturePolicy.Omit
  )

  private def request(client: LlmClient, id: String): ZIO[Scope, AgentError, TurnOutput] =
    client.streamTurn(
      Chunk(Msg.user(id)),
      Chunk.empty,
      config,
      EventPublisher.discard,
      ModelStreamContext("reply", id)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ScriptedLlmClientSpec")(
    test("exhaustion is recorded and fails instead of producing a successful answer") {
      for
        client    <- ScriptedLlmClient.make(Chunk(answer), 3)
        unused    <- client.verifyConsumed.either
        first     <- ZIO.scoped(request(client, "first"))
        exhausted <- ZIO.scoped(request(client, "extra")).either
        records   <- client.requests
        remaining <- client.remaining
        _         <- client.verifyConsumed
      yield assertTrue(
        first == answer,
        unused.isLeft,
        exhausted == Left(
          AgentError.InvalidConfiguration("Script exhausted: unexpected model request")
        ),
        remaining == 0,
        records.map(_.context.requestId) == Chunk("first", "extra"),
        records.forall(_.input.model == "scripted"),
        records.forall(_.input.settings.maxTokens.contains(42)),
        records.forall(_.input.settings.configuredTemperature.isEmpty),
        !records.toString.contains("private-key"),
        !records.toString.contains("private.invalid")
      )
    },
    test("recording overflow neither consumes the next response nor silently truncates admission") {
      for
        client    <- ScriptedLlmClient.make(Chunk(answer, answer), 1)
        _         <- ZIO.scoped(request(client, "first"))
        overflow  <- ZIO.scoped(request(client, "overflow")).either
        records   <- client.requests
        remaining <- client.remaining
        unused    <- client.verifyConsumed.either
      yield assertTrue(
        overflow == Left(
          AgentError.InvalidConfiguration("Script request recording limit reached (1)")
        ),
        records.map(_.context.requestId) == Chunk("first"),
        remaining == 1,
        unused.isLeft
      )
    },
    test("invalid recording capacities fail through the typed channel") {
      ZIO
        .foreach(Chunk(0, -1))(capacity =>
          ScriptedLlmClient.make(Chunk.empty, capacity).either.map(_.isLeft)
        )
        .map(results => assertTrue(results.forall(identity)))
    },
    test("response effects are lazy and preserve complete causes without replay") {
      val failure = AgentError.InvalidConfiguration("scripted failure")
      val defect  = new IllegalStateException("scripted defect")
      val cause   = Cause.fail(failure) ++ Cause.die(defect)
      for
        executions <- Ref.make(0)
        client <- ScriptedLlmClient.makeEffectful(
          Chunk(executions.update(_ + 1) *> ZIO.failCause(cause)),
          2
        )
        before <- executions.get
        built = request(client, "failed")
        beforeRun <- executions.get
        result    <- ZIO.scoped(built).exit
        _         <- ZIO.scoped(request(client, "extra")).either
        after     <- executions.get
        records   <- client.requests
        _         <- client.verifyConsumed
      yield assertTrue(
        before == 0,
        beforeRun == 0,
        after == 1,
        result == Exit.failCause(cause),
        records.size == 2
      )
    },
    test("interrupting a response joins its caller-owned cleanup and consumes its reservation") {
      ZIO.scoped {
        for
          entered        <- Promise.make[Nothing, Unit]
          cleanupStarted <- Promise.make[Nothing, Unit]
          release        <- Promise.make[Nothing, Unit]
          cleaned        <- Ref.make(false)
          client <- ScriptedLlmClient.makeEffectful(
            Chunk(
              ZIO.acquireRelease(ZIO.unit)(_ =>
                cleanupStarted.succeed(()) *> release.await *> cleaned.set(true)
              ) *> entered.succeed(()) *> ZIO.never,
              ZIO.succeed(answer)
            ),
            2
          )
          worker <- ZIO.uninterruptibleMask { restore =>
            for
              fiber <- restore(ZIO.scoped(request(client, "cancelled"))).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          }
          _ <- entered.await
          cancelling <- ZIO.uninterruptibleMask { restore =>
            for
              fiber <- restore(worker.interrupt).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          }
          _           <- cleanupStarted.await
          held        <- cancelling.poll
          before      <- cleaned.get
          remaining   <- client.remaining
          _           <- release.succeed(())
          interrupted <- cancelling.join
          after       <- cleaned.get
          next        <- ZIO.scoped(request(client, "next"))
          records     <- client.requests
          _           <- client.verifyConsumed
        yield assertTrue(
          held.isEmpty,
          !before,
          after,
          remaining == 1,
          interrupted.isInterrupted,
          interrupted.causeOption.exists(cause => cause.failures.isEmpty && cause.defects.isEmpty),
          next == answer,
          records.map(_.context.requestId) == Chunk("cancelled", "next")
        )
      }
    },
    test("concurrent requests reserve distinct responses before either completes") {
      ZIO.scoped {
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          client <- ScriptedLlmClient.makeEffectful(
            Chunk(
              entered.succeed(()).unit *> release.await.as(answer),
              ZIO.succeed(TurnOutput.FinalText(Msg.assistant("second")))
            ),
            2
          )
          first       <- request(client, "first").forkScoped
          _           <- entered.await
          second      <- request(client, "second")
          records     <- client.requests
          _           <- release.succeed(())
          firstOutput <- first.join
          _           <- client.verifyConsumed
        yield assertTrue(
          firstOutput == answer,
          second.message.text == "second",
          records.map(_.context.requestId) == Chunk("first", "second")
        )
      }
    }
  ) @@ TestAspect.timeout(20.seconds)
