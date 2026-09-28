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

import zio.json.*

enum PermissionBehavior derives JsonCodec:
  case Allow, Deny, Ask, Passthrough

object PermissionBehavior:
  /** Combines independent gates without allowing a weaker decision to override a stronger one. */
  def combine(first: PermissionBehavior, second: PermissionBehavior): PermissionBehavior =
    if priority(first) >= priority(second) then first else second

  private def priority(behavior: PermissionBehavior): Int = behavior match
    case PermissionBehavior.Deny        => 3
    case PermissionBehavior.Ask         => 2
    case PermissionBehavior.Passthrough => 1
    case PermissionBehavior.Allow       => 0
