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
package com.weisgnosis.agents.harness.eviction

import zio.json.*

final case class EvictionConfig(
    maxResultChars: Int = 80000,
    previewHeadChars: Int = 2000,
    previewTailChars: Int = 2000,
    excludedTools: Set[String] =
      Set("read_file", "write_file", "edit_file", "memory_search", "memory_get", "session_search")
) derives JsonCodec
