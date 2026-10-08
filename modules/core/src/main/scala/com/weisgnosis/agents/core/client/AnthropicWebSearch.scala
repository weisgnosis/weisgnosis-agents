/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import zio.json.ast.Json

/** Explicit host selection of basic native web search, not a local executable tool binding. */
opaque type AnthropicWebSearch = Int

object AnthropicWebSearch:
  def make(maxUses: Int = 5): Either[AgentError.InvalidConfiguration, AnthropicWebSearch] =
    Either.cond(
      maxUses >= 1 && maxUses <= 20,
      maxUses,
      AgentError.InvalidConfiguration("Anthropic web-search maxUses must be between 1 and 20")
    )

  extension (selection: AnthropicWebSearch)
    def maxUses: Int = selection
    private[client] def declaration: Json = Json.Obj(
      "type"     -> Json.Str("web_search_20250305"),
      "name"     -> Json.Str("web_search"),
      "max_uses" -> Json.Num(selection)
    )
