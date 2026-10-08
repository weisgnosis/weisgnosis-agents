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
package com.weisgnosis.agents.mcp.protocol

import zio.test.*

object McpSupportedVersionsSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("McpSupportedVersionsSpec")(
    test(
      "supported revisions require a nonempty explicit set and preserve membership without ordering"
    ) {
      val selected = Set(McpProtocolVersion.V20241105, McpProtocolVersion.V20250618)
      assertTrue(
        McpSupportedVersions.make(Set.empty).isLeft,
        McpSupportedVersions.make(selected).exists(_.versions == selected),
        McpSupportedVersions
          .make(Set(McpProtocolVersion.V20250326))
          .exists(_.versions == Set(McpProtocolVersion.V20250326))
      )
    }
  )
