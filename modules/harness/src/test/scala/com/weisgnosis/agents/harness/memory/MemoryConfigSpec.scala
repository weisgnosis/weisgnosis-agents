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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.harness.transcript.TranscriptRetentionPolicy
import zio.*
import zio.json.*
import zio.test.*

object MemoryConfigSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryConfigSpec")(
    test(
      "defaults_matchHistoricalBehaviour through explicit default selection and typed policies"
    ) {
      val config = MemoryConfig.Default
      assertTrue(
        config.flushPrompt.isEmpty,
        config.consolidationPrompt.isEmpty,
        config.consolidationBudget.value == 4000,
        config.resolvedFlushPrompt == MemoryFlushManager.DefaultPrompt,
        config.resolvedConsolidationPrompt == ConsolidationPrompt.default(
          MemoryTokenBudget.Default
        ),
        config.triggers == MemoryTriggerPolicy(),
        config.triggers.flush == MemoryTrigger.Always,
        MemoryTrigger.throttled(30.minutes) == Right(config.triggers.maintenance),
        config.dailyRetention == MemoryRetentionPolicy.ArchiveAfter(RetentionDays.DailyDefault),
        config.transcriptRetention == TranscriptRetentionPolicy.PruneAfter(
          RetentionDays.SessionDefault
        ),
        "{}".fromJson[MemoryConfig] == Right(config)
      )
    },
    test("builder_overridesAreCarried through immutable configuration and codecs") {
      for
        budget      <- ZIO.fromEither(MemoryTokenBudget.fromInt(1234))
        daily       <- ZIO.fromEither(RetentionDays.fromInt(30))
        session     <- ZIO.fromEither(RetentionDays.fromInt(60))
        flush       <- ZIO.fromEither(MemoryTrigger.throttled(10.minutes))
        maintenance <- ZIO.fromEither(MemoryTrigger.throttled(15.minutes))
        prompt = ConsolidationPrompt("keep within ", " tokens, ", " chars")
        config = MemoryConfig(
          Some("custom flush"),
          Some(prompt),
          budget,
          MemoryTriggerPolicy(flush, maintenance),
          MemoryRetentionPolicy.ArchiveAfter(daily),
          TranscriptRetentionPolicy.PruneAfter(session)
        )
      yield assertTrue(
        config.resolvedFlushPrompt == "custom flush",
        config.resolvedConsolidationPrompt == "keep within 1234 tokens, 4936 chars",
        config.toJson.fromJson[MemoryConfig] == Right(config),
        config.triggers.flush == flush,
        config.triggers.maintenance == maintenance,
        config.dailyRetention == MemoryRetentionPolicy.ArchiveAfter(daily),
        config.transcriptRetention == TranscriptRetentionPolicy.PruneAfter(session)
      )
    },
    test(
      "builder_consolidationPromptMustHaveTwoIntPlaceholders is replaced by a fixed two-position template"
    ) {
      val config = MemoryConfig(consolidationPrompt =
        Some(ConsolidationPrompt("keep within ", " tokens, ", " chars"))
      )
      assertTrue(
        config.resolvedConsolidationPrompt == "keep within 4000 tokens, 16000 chars",
        List("{}", "{\"beforeTokens\":\"a\"}", "{\"beforeTokens\":\"a\",\"betweenBudgets\":\"b\"}")
          .forall(_.fromJson[ConsolidationPrompt].isLeft)
      )
    },
    test("builder_rejectsInvalidNumericArgs through validated budgets and retention days") {
      assertTrue(
        List(0, -1, 4001).forall(MemoryTokenBudget.fromInt(_).isLeft),
        List(0, -5).forall(RetentionDays.fromInt(_).isLeft),
        "{\"consolidationBudget\":0}".fromJson[MemoryConfig].isLeft,
        "{\"consolidationBudget\":\"1234\"}".fromJson[MemoryConfig].isLeft,
        "{\"dailyRetention\":{\"ArchiveAfter\":{\"days\":0}}}".fromJson[MemoryConfig].isLeft,
        "{\"transcriptRetention\":{\"PruneAfter\":{\"days\":-5}}}".fromJson[MemoryConfig].isLeft
      )
    },
    test(
      "builder_rejectsNullDurationsAndTrigger adapts negative and infinite durations with typed trigger values"
    ) {
      assertTrue(
        MemoryTrigger.throttled(Duration.fromSeconds(-1)).isLeft,
        MemoryTrigger.throttled(Duration.Infinity).isLeft,
        MemoryTrigger.throttled(Duration.Zero) == Right(MemoryTrigger.Always)
      )
    },
    test(
      "builder_isReusable_andEachBuildIsIndependent becomes immutable copy without changing prior fields"
    ) {
      for
        firstBudget  <- ZIO.fromEither(MemoryTokenBudget.fromInt(1000))
        secondBudget <- ZIO.fromEither(MemoryTokenBudget.fromInt(2000))
        first = MemoryConfig(flushPrompt = Some("v1"), consolidationBudget = firstBudget)
        second = first.copy(
          flushPrompt = Some("v2"),
          consolidationBudget = secondBudget,
          consolidationPrompt = Some(ConsolidationPrompt("limit ", "/", "")),
          triggers = MemoryTriggerPolicy(MemoryTrigger.Never, MemoryTrigger.Always),
          dailyRetention = MemoryRetentionPolicy.KeepAll,
          transcriptRetention = TranscriptRetentionPolicy.KeepAll
        )
      yield assertTrue(
        first == MemoryConfig(flushPrompt = Some("v1"), consolidationBudget = firstBudget),
        second.resolvedFlushPrompt == "v2",
        second.resolvedConsolidationPrompt == "limit 2000/8000",
        second.triggers.flush == MemoryTrigger.Never,
        second.triggers.maintenance == MemoryTrigger.Always,
        second.dailyRetention == MemoryRetentionPolicy.KeepAll,
        second.transcriptRetention == TranscriptRetentionPolicy.KeepAll,
        first.copy(flushPrompt = Some("")).resolvedFlushPrompt == "",
        first.copy(flushPrompt = None).resolvedFlushPrompt == MemoryFlushManager.DefaultPrompt
      )
    }
  )
