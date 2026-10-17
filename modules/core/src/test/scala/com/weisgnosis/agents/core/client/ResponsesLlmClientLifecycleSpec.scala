/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{AgentRunState, ReActAgent}
import com.weisgnosis.agents.core.model.{Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, Toolkit}
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.{Body, Headers, Response, Status}
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object ResponsesLlmClientLifecycleSpec extends ZIOSpecDefault:
  import ResponsesFixtures.*

  private def bytes(text: String): ZStream[Any, Nothing, Byte] =
    ZStream.fromIterable(text.getBytes(StandardCharsets.UTF_8))

  private def encoded(input: Chunk[Json.Obj], named: Boolean = true): String =
    frames(input)
      .map(frame =>
        (if named then s"event: ${frame.event.getOrElse("")}\n"
         else "") + s"data: ${frame.data}\n\n"
      )
      .mkString

  private def response(body: ZStream[Any, Throwable, Byte]): Response =
    Response(
      status = Status.Ok,
      headers = Headers("Content-Type" -> "text/event-stream"),
      body = Body.fromStreamChunked(body)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ResponsesLlmClientLifecycleSpec")(
    test("interruption joins HTTP cleanup, reconciles partial calls and fences another request"):
      ZIO.scoped:
        for
          blocked  <- Promise.make[Nothing, Unit]
          cleaning <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          requests <- Ref.make(0)
          executed <- Ref.make(0)
          prefix = Chunk(
            created,
            added(0, reasoning("reasoning-partial", "cipher")),
            added(1, function("item-partial", "call-partial")),
            event(
              "response.function_call_arguments.delta",
              "output_index" -> Json.Num(1),
              "item_id"      -> Json.Str("item-partial"),
              "delta"        -> Json.Str("{\"value\":")
            )
          )
          http = HttpClientTestSupport.scripted(_ =>
            requests.update(_ + 1) *> ZIO
              .acquireRelease(ZIO.unit)(_ => cleaning.succeed(()) *> release.await)
              .as(
                response(
                  bytes(encoded(prefix)) ++ ZStream
                    .fromZIO(blocked.succeed(()))
                    .drain ++ ZStream.never
                )
              )
          )
          client <- ResponsesLlmClient.make(http)
          tool = Tool.fromFunction[Json, String](
            "read_evidence",
            "Read evidence",
            Json.Obj(),
            isReadOnly = true
          )(_ => executed.update(_ + 1).as("not executed"))
          agent <- ReActAgent.make(
            client = client,
            llmConfig = config,
            toolkit = Toolkit.single(tool)
          )
          controller <- ZIO.fiberId
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(agent.run(Msg.user("Review evidence."))).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _          <- blocked.await
          _          <- running.interruptAsFork(controller)
          _          <- cleaning.await
          held       <- running.poll
          _          <- release.succeed(())
          cancelled  <- running.await
          history    <- agent.history
          next       <- agent.run(Msg.user("Continue.")).either
          dispatches <- requests.get
          executions <- executed.get
        yield assertTrue(
          held.isEmpty,
          cancelled.causeOption.exists(_.interruptors.contains(controller)),
          history.exists(_.metadata.contains(Msg.MetadataResponsesIncompleteReplay)),
          history.flatMap(_.toolUses).map(_.input) == Chunk(Json.Obj()),
          history.flatMap(_.toolResults).count(_.id == "call-partial") == 1,
          history.flatMap(_.toolResults).forall(_.state == ToolResultState.Error),
          history.count(_.metadata.contains("agentscope_generate_reason")) == 1,
          next.isLeft,
          dispatches == 1,
          executions == 0
        )
    ,
    test("HTTP cleanup defects retain their identity alongside the original interruption"):
      val cleanupDefect = new IllegalStateException("Responses HTTP cleanup failed")
      ZIO.scoped:
        for
          blocked    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          executions <- Ref.make(0)
          prefix = Chunk(created, added(0, function("item-held", "call-held")))
          http = HttpClientTestSupport.scripted(_ =>
            ZIO
              .acquireRelease(ZIO.unit)(_ =>
                cleaning.succeed(()) *> release.await *> ZIO.die(cleanupDefect)
              )
              .as(
                response(
                  bytes(encoded(prefix)) ++ ZStream
                    .fromZIO(blocked.succeed(()))
                    .drain ++ ZStream.never
                )
              )
          )
          client <- ResponsesLlmClient.make(http)
          tool = Tool.fromFunction[Json, String]("read_evidence", "Read evidence", Json.Obj())(_ =>
            executions.update(_ + 1).as("not executed")
          )
          agent <- ReActAgent.make(
            client = client,
            llmConfig = config,
            toolkit = Toolkit.single(tool)
          )
          controller <- ZIO.fiberId
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(agent.run(Msg.user("Wait for cancellation."))).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _         <- blocked.await
          _         <- running.interruptAsFork(controller)
          _         <- cleaning.await
          held      <- running.poll
          _         <- release.succeed(())
          cancelled <- running.await
          count     <- executions.get
        yield assertTrue(
          held.isEmpty,
          cancelled.causeOption.exists(_.interruptors.contains(controller)),
          cancelled.causeOption.exists(_.defects.contains(cleanupDefect)),
          count == 0
        )
    ,
    test("data-only terminal events finish without waiting for HTTP EOF and close their resource"):
      ZIO.scoped:
        for
          released <- Ref.make(false)
          requests <- Ref.make(0)
          http = HttpClientTestSupport.scripted(_ =>
            requests.update(_ + 1) *> ZIO
              .acquireRelease(ZIO.unit)(_ => released.set(true))
              .as(
                response(
                  bytes(
                    encoded(events(Chunk(message("Complete without EOF."))), named = false)
                  ) ++ ZStream.never
                )
              )
          )
          client <- ResponsesLlmClient.make(http)
          agent  <- ReActAgent.make(client = client, llmConfig = config)
          result <- agent.run(Msg.user("Answer."))
          closed <- released.get
          count  <- requests.get
        yield assertTrue(
          result.state == AgentRunState.Completed,
          result.reply.text == "Complete without EOF.",
          closed,
          count == 1
        )
    ,
    test("cancellation during completed response cleanup retains the complete native replay chain"):
      ZIO.scoped:
        for
          cleaning    <- Promise.make[Nothing, Unit]
          interrupted <- Promise.make[Nothing, Unit]
          release     <- Promise.make[Nothing, Unit]
          requests    <- Ref.make(0)
          bodies      <- Ref.make(Chunk.empty[String])
          completedItems = Chunk(
            reasoning("reasoning-complete", "complete-cipher"),
            message("Finished first reply.")
          )
          http = HttpClientTestSupport.scripted(request =>
            for
              count <- requests.updateAndGet(_ + 1)
              body  <- request.body.asString
              _     <- bodies.update(_ :+ body)
              _ <-
                if count == 1 then
                  ZIO.acquireRelease(ZIO.unit)(_ =>
                    cleaning.succeed(()) *> ZIO.interruptible(ZIO.never).exit *>
                      interrupted.succeed(()) *> release.await
                  )
                else ZIO.unit
            yield response(
              bytes(
                encoded(
                  events(
                    if count == 1 then completedItems else Chunk(message("Continued with replay."))
                  )
                )
              )
            )
          )
          client     <- ResponsesLlmClient.make(http)
          agent      <- ReActAgent.make(client = client, llmConfig = config)
          controller <- ZIO.fiberId
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(agent.run(Msg.user("Answer first."))).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _         <- cleaning.await
          _         <- running.interruptAsFork(controller)
          _         <- interrupted.await
          held      <- running.poll
          _         <- release.succeed(())
          cancelled <- running.await
          history   <- agent.history
          result    <- agent.run(Msg.user("Continue."))
          sent      <- bodies.get
        yield assertTrue(
          held.isEmpty,
          cancelled.causeOption.exists(_.isInterrupted),
          history.exists(_.metadata.contains(Msg.MetadataResponsesNative)),
          !history.exists(_.metadata.contains(Msg.MetadataResponsesIncompleteReplay)),
          result.reply.text == "Continued with replay.",
          sent.size == 2,
          sent.lift(1).exists(_.contains("complete-cipher"))
        )
    ,
    test(
      "reasoning-only token exhaustion persists a replay fence without executing another request"
    ):
      ZIO.scoped:
        for
          requests <- Ref.make(0)
          item = reasoning("reasoning-exhausted", "unfinished-cipher")
          terminal = event(
            "response.incomplete",
            "response" -> Json.Obj(
              "id"                 -> Json.Str("response-1"),
              "status"             -> Json.Str("incomplete"),
              "incomplete_details" -> Json.Obj("reason" -> Json.Str("max_output_tokens")),
              "output"             -> Json.Arr(item),
              "usage"              -> usage
            )
          )
          http = HttpClientTestSupport.scripted(_ =>
            requests
              .update(_ + 1)
              .as(response(bytes(encoded(Chunk(created, added(0, item), terminal)))))
          )
          client  <- ResponsesLlmClient.make(http)
          agent   <- ReActAgent.make(client = client, llmConfig = config)
          stopped <- agent.run(Msg.user("Reason until the output limit."))
          history <- agent.history
          next    <- agent.run(Msg.user("Continue.")).either
          count   <- requests.get
        yield assertTrue(
          stopped.state == AgentRunState.Stopped("Model output token budget exhausted"),
          history.exists(_.metadata.contains(Msg.MetadataResponsesIncompleteReplay)),
          !history.exists(_.metadata.contains(Msg.MetadataResponsesNative)),
          next.isLeft,
          count == 1
        )
  ) @@ TestAspect.timeout(20.seconds)
