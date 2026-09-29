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

import com.weisgnosis.agents.core.model.Msg
import zio.Chunk
import zio.json.*
import zio.test.*

object StructuredOutputCapabilitySpec extends ZIOSpecDefault:
  private val capabilities: Chunk[StructuredOutputCapability] = Chunk(
    StructuredOutputCapability.Unknown,
    StructuredOutputCapability.Unsupported,
    StructuredOutputCapability.Native(),
    StructuredOutputCapability.Native(NativeToolSupport.Unsupported)
  )

  def spec: Spec[TestEnvironment, Any] = suite("StructuredOutputCapabilitySpec")(
    test("capabilities and native tool support round trip through derived codecs") {
      assertTrue(
        capabilities.forall(value =>
          value.toJson.fromJson[StructuredOutputCapability] == Right(value)
        ),
        NativeToolSupport.values.forall(value =>
          value.toJson.fromJson[NativeToolSupport] == Right(value)
        )
      )
    },
    test("defaultWithToolsFollowsNativeFlag uses native support unless explicitly restricted") {
      assertTrue(
        StructuredOutputCapability.Native() == StructuredOutputCapability.Native(
          NativeToolSupport.Supported
        ),
        """{"Native":{}}""".fromJson[StructuredOutputCapability] == Right(
          StructuredOutputCapability.Native()
        ),
        """{"model":"configured"}""".fromJson[LlmConfig].map(_.structuredOutputCapability) == Right(
          StructuredOutputCapability.Unknown
        )
      )
    },
    test(
      "configured capabilities survive config serialization without entering provider request bodies"
    ) {
      val baseline = LlmConfig("configured")
      val history  = Chunk(Msg.user("question"))
      val expected = ChatCompletionsRequest.encode(history, Chunk.empty, baseline)
      assertTrue(
        expected.isRight,
        capabilities.forall { capability =>
          val configured = baseline.copy(structuredOutputCapability = capability)
          configured.toJson.fromJson[LlmConfig] == Right(configured) &&
          ChatCompletionsRequest.encode(history, Chunk.empty, configured) == expected
        }
      )
    },
    test("invalid capability and native tool declarations fail decoding") {
      assertTrue(
        """{"Native":{"toolSupport":"Other"}}""".fromJson[StructuredOutputCapability].isLeft,
        """"Other"""".fromJson[StructuredOutputCapability].isLeft,
        """{"model":"configured","structuredOutputCapability":false}""".fromJson[LlmConfig].isLeft
      )
    }
  )
