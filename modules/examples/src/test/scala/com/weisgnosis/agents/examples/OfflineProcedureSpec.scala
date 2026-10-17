/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.harness.procedure.ProcedureOutcome
import zio.*
import zio.test.*

object OfflineProcedureSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("OfflineProcedureSpec")(
    test("real local backend reopen skips the acknowledged accepted prefix"):
      OfflineProcedure.exercise.map(receipt =>
        assertTrue(
          receipt.first == ProcedureOutcome.Accepted("draft"),
          receipt.resumed.isInstanceOf[ProcedureOutcome.Completed],
          receipt.executions == Chunk("draft", "package")
        )
      )
  )
