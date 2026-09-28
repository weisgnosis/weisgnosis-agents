# Master Architecture Implementation Tracker

> **Document:** Master Architecture Implementation Tracker
> **System:** `weisgnosis-agents`
> **Namespace:** `com.weisgnosis.agents`
> **Last Synced:** September 2026
> **Overall Progress:** Phase 3 verified; Phase 4 compaction, eviction, and permission components implemented

---

## 🧭 Architecture Documentation Index

| Document | Purpose | File Link |
| :--- | :--- | :--- |
| **High-Level Design (HLD)** | Executive architecture, system context, boundaries, and invariants | [`high-level-design.md`](high-level-design.md) |
| **Mid-Level Design (MLD)** | ReAct state machines, turn lifecycles, event pipelines, compaction | [`mid-level-design.md`](mid-level-design.md) |
| **Low-Level Design (LLD)** | Concrete Scala 3 ADTs, codecs, method signatures, error models | [`low-level-design.md`](low-level-design.md) |
| **Architectural Manifesto** | Epistemic SSOT, Arbiter, Verification Pipeline, 10 universal primitives | [`../weisgnosis-os-and-agents-architecture.md`](../weisgnosis-os-and-agents-architecture.md) |
| **Porting & Edge Case Guide** | 35 production edge cases, LOC forecasts, Reactor-to-ZIO porting guide | [`../deep-research-and-porting-guide.md`](../deep-research-and-porting-guide.md) |

---

## 📊 Phased Implementation Roadmap

```
Phase 0: Architecture, Audit & Build Setup  [████████████████████] 100% (Complete)
Phase 1: Core Domain Models & Codecs        [████████████████████] 100% (Complete)
Phase 2: Streaming Model Client & Acc       [████████████████████] 100% (Complete)
Phase 3: ReAct Engine & Loop Guards         [####################] 100% (Complete)
Phase 4: Compaction, eviction, permissions implemented; memory services pending
Phase 5: Subagents & MCP Client             [                    ]   0% (Pending)
Phase 6: WeisGnosis OS Production Cutover   [                    ]   0% (Pending)
```

---

## 📦 Package Implementation & Test Parity Tracker

### 1. `weisgnosis-agents-core`

| Package | Status | Implementation Files | Parity Tests | Test Spec Location |
| :--- | :---: | :--- | :--- | :--- |
| **`core.model`** | `[x]` | `Role.scala`<br/>`ToolResultState.scala`<br/>`MediaSource.scala`<br/>`ContentBlock.scala`<br/>`ChatUsage.scala`<br/>`Msg.scala` | `MsgSpec.scala`<br/>`MsgSerdeSpec.scala`<br/>`ContentBlockSerdeSpec.scala` | [`modules/core/.../model/README.md`](../../modules/core/src/test/scala/com/weisgnosis/agents/core/model/README.md) |
| **`core.event`** | `[x]` | `AgentEvent.scala` (31 cases) | `AgentEventSerdeSpec.scala`<br/>`AgentEventStreamingSpec.scala` | [`modules/core/.../event/README.md`](../../modules/core/src/test/scala/com/weisgnosis/agents/core/event/README.md) |
| **`core.error`** | `[x]` | `AgentError.scala` (typed domain errors) | Compiler verified typed ADT | [`modules/core/.../error/`](../../modules/core/src/main/scala/com/weisgnosis/agents/core/error/) |
| **`core.tool`** | `[x]` | `ToolDef.scala`<br/>`ToolExecutionResult.scala`<br/>`PathSafety.scala`<br/>`Tool.scala`<br/>`Toolkit.scala` | `ToolkitSpec.scala`<br/>`ToolExecutionSpec.scala`<br/>`PathSafetySpec.scala` | [`modules/core/.../tool/README.md`](../../modules/core/src/test/scala/com/weisgnosis/agents/core/tool/README.md) |
| **`core.client`** | `[x]` | `LlmChunk.scala`<br/>`TurnOutput.scala`<br/>`StreamAccumulator.scala`<br/>`SseParser.scala`<br/>`ReasoningModelAdapter.scala`<br/>`LlmClient.scala` | `SseParserSpec.scala`<br/>`StreamAccumulatorSpec.scala`<br/>`ReasoningAdapterSpec.scala`<br/>`LlmClientSpec.scala` | [`modules/core/.../client/README.md`](../../modules/core/src/test/scala/com/weisgnosis/agents/core/client/README.md) |
| **`core.agent`** | `[x]` | `ReActAgent.scala`<br/>`AgentConfig.scala`<br/>`AgentResult.scala`<br/>`StructuredOutput.scala` | `ReActAgentSpec.scala`<br/>`ReActAgentInterruptSpec.scala`<br/>`ReActAgentThinkingRetrySpec.scala`<br/>`StructuredOutputSpec.scala` | [`modules/core/.../agent/README.md`](../../modules/core/src/test/scala/com/weisgnosis/agents/core/agent/README.md) |

---

### 2. `weisgnosis-agents-harness`

| Package | Status | Implementation Files | Parity Tests | Test Spec Location |
| :--- | :---: | :--- | :--- | :--- |
| **`harness.memory`** | `[~]` | `CompactorConfig.scala`<br/>`ConversationCompactor.scala`<br/>`TokenCounter.scala`<br/>`MemoryFlushManager.scala`<br/>`MemoryConsolidator.scala` | `ConversationCompactorSpec.scala`<br/>`TokenCounterSpec.scala`<br/>`MemoryFlushSpec.scala` | [`modules/harness/.../memory/README.md`](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/memory/README.md) |
| **`harness.eviction`** | `[x]` | `EvictionConfig.scala`<br/>`ToolResultEvictor.scala` | `ToolResultEvictorSpec.scala` | [`modules/harness/.../eviction/README.md`](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/eviction/README.md) |
| **`harness.permission`**| `[x]` | `PermissionBehavior.scala`<br/>`PermissionRule.scala`<br/>`ConfirmResult.scala`<br/>`PermissionEngine.scala` | `PermissionGateSpec.scala`<br/>`PermissionPromiseSpec.scala`<br/>`DurableResumeSpec.scala` | [`modules/harness/.../permission/README.md`](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/permission/README.md) |
| **`harness.subagent`** | `[ ]` | `SubagentDeclaration.scala`<br/>`SubagentCoordinator.scala`<br/>`AgentSpawnTool.scala`<br/>`TaskSupervisor.scala` | `SubagentSpawnSpec.scala`<br/>`SubagentCancelSpec.scala`<br/>`SubagentPlanModePropagationSpec.scala`<br/>`TimeoutPromotionSpec.scala` | [`modules/harness/.../subagent/README.md`](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/subagent/README.md) |
| **`harness.plan`** | `[ ]` | `PlanModeState.scala`<br/>`PlanModeInterceptor.scala`<br/>`PlanModeTools.scala` | `PlanModeInterceptorSpec.scala`<br/>`PlanModePropagationSpec.scala`<br/>`ShellHardeningSpec.scala` | [`modules/harness/.../plan/README.md`](../../modules/harness/src/test/scala/com/weisgnosis/agents/harness/plan/README.md) |

---

### 3. `weisgnosis-agents-mcp`

| Package | Status | Implementation Files | Parity Tests | Test Spec Location |
| :--- | :---: | :--- | :--- | :--- |
| **`mcp.protocol`** | `[ ]` | `JsonRpc.scala`<br/>`McpSchema.scala` | `McpProtocolSpec.scala`<br/>`McpToolCodecSpec.scala` | [`modules/mcp/.../protocol/README.md`](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/protocol/README.md) |
| **`mcp.transport`** | `[ ]` | `McpTransport.scala`<br/>`StdioTransport.scala`<br/>`SseTransport.scala` | `StdioTransportSpec.scala`<br/>`SseTransportSpec.scala` | [`modules/mcp/.../transport/README.md`](../../modules/mcp/src/test/scala/com/weisgnosis/agents/mcp/transport/README.md) |
| **`mcp.client`** | `[ ]` | `McpClient.scala`<br/>`McpToolBridge.scala` | `McpClientSpec.scala`<br/>`McpToolkitBridgeSpec.scala` | [`modules/mcp/.../client/README.md`](../../modules/core/src/test/scala/com/weisgnosis/agents/mcp/client/README.md) |

---

## 🛡️ Critical Invariant Verification Matrix

| Invariant | Test Verification | Target Spec | Status |
| :--- | :--- | :--- | :--- |
| **INV-1: Pure FP State** | Effects suspended in ZIO; typed registration; total collection access | Source audit and boundary regressions | `[x]` |
| **INV-2: Dangling Tool Reconciliation** | Canceled fibers synthesize `ToolResultBlock(Error)` for in-flight tools only | `ReActAgentInterruptSpec.scala` | `[x]` |
| **INV-3: Thinking Loopback Guard** | Empty content turns with reasoning trigger `<system-reminder>` | `ReActAgentThinkingRetrySpec.scala` | `[x]` |
| **INV-4: Atomic Turn Pairing & Cutoff** | History compaction never splits assistant call and tool result; handles consecutive user turns | `ConversationCompactorSpec.scala` | `[x]` |
| **INV-5: Summary Deduplication** | Re-compaction flattens prior summary blocks without nesting | `ConversationCompactorSpec.scala` | `[x]` |
| **INV-6: Output Eviction Previews** | Outputs >80k chars saved to `large_tool_results/` with 2k head / 2k tail preview | `ToolResultEvictorSpec.scala` | `[x]` |
| **INV-7: Dual-Mode HITL** | In-memory `Promise` for CLI; state persistence & `ConfirmResult` resume for durable | `PermissionPromiseSpec.scala`, `DurableResumeSpec.scala` | `[ ]` |
| **INV-8: Max Spawn Depth & Task Supervisor** | Subagent recursion capped at depth 3; timeout promotion detached to supervisor | `SubagentSpawnSpec.scala`, `TimeoutPromotionSpec.scala` | `[ ]` |
| **INV-9: Plan Mode & Shell Hardening** | File mutations rejected in plan mode; shell commands statically inspected | `PlanModeInterceptorSpec.scala`, `ShellHardeningSpec.scala` | `[ ]` |
| **INV-10: Sensitive Path Canonicalization** | Blocks symlink traversal to sensitive paths via `toRealPath()` | `PathSafetySpec.scala` | `[~]` Canonicalization implemented; privileged symlink integration test unavailable on this host |
| **INV-11: Structured Output Dual-Engine** | Toggles native format vs `generate_response` with root `$defs` hoisting | `StructuredOutputSpec.scala` | `[x]` |
| **INV-12: Chunk Index Assembly** | Groups parallel tool call argument fragments by `streamIndex` | `StreamAccumulatorSpec.scala` | `[x]` Verified |

## Phase 4 delivery boundary

ConversationCompactor, TokenCounter, ToolResultEvictor, the local ToolResultStore, PermissionEngine, and PermissionRequests are implemented and tested. MemoryFlushManager and MemoryConsolidator remain pending. Hosts supply compaction summary/flush/offload callbacks and persist durable PendingApproval values. Agent-level middleware installation and durable workflow storage are separate integration work; the permission component never invokes the model during resume.

See [Phase 4 implementation notes](phase-4-implementation.md) for contracts and API changes.
