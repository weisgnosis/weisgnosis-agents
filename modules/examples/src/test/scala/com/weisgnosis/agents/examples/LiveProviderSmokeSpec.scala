/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */
package com.weisgnosis.agents.examples

import zio.*
import zio.test.*

object LiveProviderSmokeSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("LiveProviderSmoke")(
    test("ordinary execution stays offline and enabled execution requires explicit configuration"):
      for
        _              <- TestSystem.clearEnv("WEISGNOSIS_LIVE_SMOKE")
        disabled       <- LiveProviderSmoke.run.exit
        _              <- TestSystem.clearEnv("WEISGNOSIS_NATIVE_PROVIDER")
        nativeDisabled <- NativeProviderSmoke.run.exit
        _              <- TestSystem.putEnv("WEISGNOSIS_NATIVE_PROVIDER", "unsupported")
        nativeInvalid  <- NativeProviderSmoke.run.exit
        output         <- TestConsole.output
        _              <- TestSystem.putEnv("WEISGNOSIS_LIVE_SMOKE", "enabled")
        _              <- TestSystem.clearEnv("WEISGNOSIS_LIVE_ENDPOINT")
        missing        <- LiveProviderSmoke.configuration.either
        _ <- TestSystem.putEnv(
          "WEISGNOSIS_LIVE_ENDPOINT",
          "https://example.invalid/v1/chat/completions"
        )
        _              <- TestSystem.putEnv("WEISGNOSIS_LIVE_MODEL", "explicit-model")
        _              <- TestSystem.clearEnv("WEISGNOSIS_LIVE_API_KEY")
        enabled        <- LiveProviderSmoke.configuration
        _              <- TestSystem.putEnv("WEISGNOSIS_LIVE_MAX_TOKENS", "8192")
        configured     <- LiveProviderSmoke.configuration
        _              <- TestSystem.putEnv("WEISGNOSIS_LIVE_MAX_TOKENS", "0")
        invalidTokens  <- LiveProviderSmoke.configuration.either
        _              <- TestSystem.putEnv("WEISGNOSIS_LIVE_TIMEOUT_SECONDS", "600")
        timeout        <- LiveProviderSmoke.runTimeout
        _              <- TestSystem.putEnv("WEISGNOSIS_LIVE_TIMEOUT_SECONDS", "1801")
        invalidTimeout <- LiveProviderSmoke.runTimeout.either
        _              <- TestSystem.putEnv("WEISGNOSIS_LIVE_SMOKE", "true")
        malformed      <- LiveProviderSmoke.configuration.either
      yield assertTrue(
        disabled.isSuccess,
        nativeDisabled.isSuccess,
        nativeInvalid.isFailure,
        output.exists(_.contains("disabled")),
        missing.isLeft,
        enabled.exists(config => config.model == "explicit-model" && config.apiKey.isEmpty),
        enabled.exists(_.maxTokens.contains(4096)),
        configured.exists(_.maxTokens.contains(8192)),
        invalidTokens.isLeft,
        timeout == 600.seconds,
        invalidTimeout.isLeft,
        malformed.isLeft
      )
  )
