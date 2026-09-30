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
package com.weisgnosis.agents.harness.permission

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.ast.Json
import zio.test.*

object PermissionGateSpec extends ZIOSpecDefault:
  private enum Stop:
    case Failure, Defect, Cancellation

  def spec: Spec[TestEnvironment, Any] = suite("PermissionGateSpec")(
    test("composed gates retain optional votes and close each scope before evaluating the next") {
      val cases = Chunk(
        (None, None, None),
        (None, Some(PermissionBehavior.Allow), Some(PermissionBehavior.Allow)),
        (Some(PermissionBehavior.Allow), None, Some(PermissionBehavior.Allow)),
        (
          Some(PermissionBehavior.Deny),
          Some(PermissionBehavior.Ask),
          Some(PermissionBehavior.Deny)
        ),
        (Some(PermissionBehavior.Allow), Some(PermissionBehavior.Ask), Some(PermissionBehavior.Ask))
      )
      ZIO
        .foreach(cases) { case (firstVote, secondVote, expected) =>
          for
            events <- Ref.make(Chunk.empty[String])
            first = PermissionGate((name, input) =>
              ZIO
                .acquireRelease(events.update(_ :+ s"first:$name:$input"))(_ =>
                  events.update(_ :+ "first released")
                )
                .as(firstVote)
            )
            second = PermissionGate((name, input) =>
              ZIO
                .acquireRelease(events.update(_ :+ s"second:$name:$input"))(_ =>
                  events.update(_ :+ "second released")
                )
                .as(secondVote)
            )
            combined = first.and(second).evaluate("tool", Json.Obj())
            before <- events.get
            result <- ZIO.scoped(
              combined.flatMap(vote => events.get.map(observed => (vote, observed)))
            )
          yield assertTrue(
            before.isEmpty,
            result._1 == expected,
            result._2 == Chunk(
              s"first:tool:${Json.Obj()}",
              "first released",
              s"second:tool:${Json.Obj()}",
              "second released"
            )
          )
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "composed gate failure defect and cancellation join cleanup without evaluating the second gate"
    ) {
      val failure = AgentError.PermissionDenied("tool", "gate unavailable")
      val defect  = new IllegalStateException("gate defect")
      ZIO
        .foreach(Chunk(Stop.Failure, Stop.Defect, Stop.Cancellation)) { stop =>
          ZIO.scoped:
            for
              entered     <- Promise.make[Nothing, Unit]
              cleaning    <- Promise.make[Nothing, Unit]
              release     <- Promise.make[Nothing, Unit]
              secondCalls <- Ref.make(0)
              closed      <- Ref.make(0)
              first = PermissionGate((_, _) =>
                ZIO.acquireRelease(entered.succeed(()))(_ =>
                  cleaning.succeed(()) *> release.await *> closed.update(_ + 1)
                ) *> (stop match
                  case Stop.Failure      => ZIO.fail(failure)
                  case Stop.Defect       => ZIO.die(defect)
                  case Stop.Cancellation => ZIO.never)
              )
              second = PermissionGate((_, _) =>
                secondCalls.update(_ + 1).as(Some(PermissionBehavior.Allow))
              )
              evaluating <- ZIO.scoped(first.and(second).evaluate("tool", Json.Obj())).forkScoped
              _          <- entered.await
              finishing <- (stop match
                case Stop.Cancellation => evaluating.interrupt
                case _                 => evaluating.await
              ).forkScoped
              _        <- cleaning.await
              pending  <- finishing.poll
              _        <- release.succeed(())
              result   <- finishing.join
              calls    <- secondCalls.get
              releases <- closed.get
            yield assertTrue(
              pending.isEmpty,
              calls == 0,
              releases == 1,
              stop match
                case Stop.Failure      => result.causeOption.exists(_.failures.contains(failure))
                case Stop.Defect       => result.causeOption.exists(_.defects.contains(defect))
                case Stop.Cancellation => result.isInterrupted
            )
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test("effectful gates are lazy and abstention makes no policy decision") {
      for
        calls <- Ref.make(0)
        gate   = PermissionGate((_, _) => calls.update(_ + 1).as(Some(PermissionBehavior.Ask)))
        effect = gate.evaluate("tool", Json.Obj())
        before    <- calls.get
        decision  <- ZIO.scoped(effect)
        after     <- calls.get
        abstained <- ZIO.scoped(PermissionGate.abstain.evaluate("tool", Json.Obj()))
      yield assertTrue(
        before == 0,
        after == 1,
        decision.contains(PermissionBehavior.Ask),
        abstained.isEmpty
      )
    },
    test("all independent gate combinations follow Deny > Ask > Passthrough > Allow") {
      val order = Chunk(
        PermissionBehavior.Allow,
        PermissionBehavior.Passthrough,
        PermissionBehavior.Ask,
        PermissionBehavior.Deny
      )
      val pairs =
        for first <- order.zipWithIndex; second <- order.zipWithIndex
        yield PermissionBehavior.combine(first._1, second._1) == (if first._2 >= second._2 then
                                                                    first._1
                                                                  else second._1)
      assertTrue(pairs.forall(identity))
    },
    test("deny and ask policies take precedence over allow rules in either order") {
      val input = Json.Obj("command" -> Json.Str("git status"))
      val allow = PermissionRule("shell", PermissionBehavior.Allow)
      val ask   = PermissionRule("shell", PermissionBehavior.Ask)
      val deny  = PermissionRule("shell", PermissionBehavior.Deny)
      assertTrue(
        PermissionEngine(Chunk(allow, ask, deny))
          .evaluate("shell", input) == PermissionBehavior.Deny,
        PermissionEngine(Chunk(deny, allow, ask))
          .evaluate("shell", input) == PermissionBehavior.Deny,
        PermissionEngine(Chunk(allow, ask)).evaluate("shell", input) == PermissionBehavior.Ask
      )
    },
    test("tool and interceptor safety decisions cannot be bypassed by allow rules") {
      val engine = PermissionEngine(Chunk(PermissionRule("*", PermissionBehavior.Allow)))
      assertTrue(
        engine.evaluate(
          "shell",
          Json.Obj(),
          Some(PermissionBehavior.Deny),
          Some(PermissionBehavior.Allow)
        ) == PermissionBehavior.Deny,
        engine.evaluate(
          "shell",
          Json.Obj(),
          Some(PermissionBehavior.Allow),
          Some(PermissionBehavior.Ask)
        ) == PermissionBehavior.Ask
      )
    },
    test("content restrictions match arguments rather than granting the entire tool") {
      val engine =
        PermissionEngine(Chunk(PermissionRule("shell", PermissionBehavior.Allow, Some("git *"))))
      assertTrue(
        engine.evaluate(
          "shell",
          Json.Obj("command" -> Json.Str("git status"))
        ) == PermissionBehavior.Allow,
        engine
          .evaluate("shell", Json.Obj("command" -> Json.Str("rm file"))) == PermissionBehavior.Ask,
        engine.evaluate("other", Json.Str("git status")) == PermissionBehavior.Ask,
        engine.evaluate(
          "shell",
          Json.Obj("command" -> Json.Str("rm file"), "note" -> Json.Str("git status"))
        ) == PermissionBehavior.Ask
      )
    },
    test("wildcard matching handles empty strings, punctuation, and nested arguments") {
      val rule = PermissionRule(
        "*",
        PermissionBehavior.Allow,
        Some("a?[*"),
        argumentPath = Chunk("nested", "command")
      )
      assertTrue(
        rule.matches("tool", Json.Obj("nested" -> Json.Obj("command" -> Json.Str("ab[tail")))),
        !rule.matches("tool", Json.Str("a[tail")),
        PermissionRule("*", PermissionBehavior.Allow, Some("*")).matches("tool", Json.Str(""))
      )
    },
    test("an unconfigured engine asks for confirmation") {
      assertTrue(PermissionEngine().evaluate("unknown", Json.Obj()) == PermissionBehavior.Ask)
    }
  )
