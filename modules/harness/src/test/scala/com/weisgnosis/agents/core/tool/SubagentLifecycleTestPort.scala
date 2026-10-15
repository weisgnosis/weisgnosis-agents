/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.event.{AgentEvent, EventAdmission, EventPublisher}
import zio.*

/** Lets lifecycle tests pause diagnostic admission without changing the production publisher. */
object SubagentLifecycleTestPort:
  def open(publish: AgentEvent => UIO[EventAdmission]): ZIO[Scope, Nothing, ToolEventPort] =
    ToolEventPort.open(EventPublisher.fromFunction(publish))
