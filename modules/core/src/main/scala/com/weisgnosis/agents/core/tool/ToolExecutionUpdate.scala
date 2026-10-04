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
package com.weisgnosis.agents.core.tool

/**
 * Provisional observation content and the independently authoritative typed return value.
 * Progress never contributes to final model history. Completed must occur exactly once and last;
 * EOF and all stream cleanup must succeed before its value becomes a completed tool observation.
 */
enum ToolExecutionUpdate[+Out]:
  case Progress(payload: ToolResultPayload)
  case Completed(value: Out)
