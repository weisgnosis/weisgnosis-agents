> Research and target architecture reference. This document preserves upstream analysis and planned platform concepts; examples and forecasts are not current exported APIs or delivery claims. Consult [the implementation tracker](architecture/tracker.md), [current contracts](architecture/low-level-design.md), and [parity audit](architecture/test-parity.md) for implementation status.

# Architectural Manifesto: WeisGnosis OS & `weisgnosis-agents`

> **Document Status:** Single Source of Truth (SSOT) Architecture  
> **Based on:** Autonomous agent empirical research 2025–2026, expert architectural review, and `agentscope-java` codebase audit  
> **Stack:** Scala 3.9.0 LTS, ZIO 2.1.26+, Temporal, PostgreSQL  
> **License:** Apache-2.0  
> **Organization:** `com.weisgnosis`  

---

## 1. The Fundamental Law of WeisGnosis

For years, the industry consensus was:  
*“Agents think and propose; runtime owns state, transitions, effects, and permissions.”*

Empirical studies from 2025–2026 (MAST NeurIPS 2025, Google Research arXiv 2512.08296, fail-plausible incidents arXiv 2606.14589) demonstrated that this boundary is necessary but insufficient. Models consistently exhibit premature victory declaration (“processed 37 out of 100 entities, generated convincing report”) and hallucinated consensus in unstructured multi-agent deliberation.

We establish the **Fundamental Law of WeisGnosis**:

> ### ⚡ **Runtime owns truth and completion.**
> **An agent is never granted the authority to decide two things:**  
> **1. “This is a fact.”** *(Fact status is assigned exclusively by a deterministic Arbiter based on independent Evidence).*  
> **2. “The task is complete.”** *(COMPLETE status is granted exclusively by an Acceptance Contract when all machine-verifiable Obligations are satisfied).*

---

## 2. The Big Picture: Separation of Layers

```text
                                  WEISGNOSIS OS
                       (Layer of Authority, Truth, & Workflows)

   User Objective ──► Contract Compiler ──► Acceptance Contract vN (Immutable)
                                                   │
   ┌───────────────────────────────────────────────┴──────────────────────────────┐
   │                                   CASE                                       │
   │  ┌────────────────────────────────────────────────────────────────────────┐  │
   │  │ Epistemic SSOT (PostgreSQL Events + Projections)                       │  │
   │  │   Sources ──► Evidence (Hash/CAS) ──► Assertions (PROPOSED/ACCEPTED)   │  │
   │  └────────────────────────────────────────────────────────────────────────┘  │
   │                                                                              │
   │  Workset Ledger (Cardinality, monotonic milestones)                          │
   │  Verification Pipeline (L0 Schema ─► L1 Deterministic ─► L2 Grounding)      │
   │  Targeted Repair Router (FailureType ─► WorkItem with budget)               │
   │  Arbiter (Deterministic pure function: Contract x SSOT ─► Decision)         │
   └──────────────────────────────────────┬───────────────────────────────────────┘
                                          │ Assigns WorkItem
                                          ▼
                      ┌───────────────────────────────────────┐
                      │          ACTORS / EXECUTORS           │
                      │                                       │
                      │   • weisgnosis-agents (LLM ReAct)     │
                      │   • Human-in-the-Loop (Temporal Sig)  │
                      │   • Deterministic Tools / CI / Scanners│
                      └───────────────────────────────────────┘
                                          │
                                          ▼
                                   weisgnosis-agents
                          (Layer of Cognitive Execution)
   ┌──────────────────────────────────────────────────────────────────────────────┐
   │ • Pure FP Stateless ReAct Engine on Scala 3.9 LTS + ZIO 2.1                  │
   │ • Input: WorkItem Slice (objective, evidence slice, schema, tools, budget)   │
   │ • Output: Proposal (new Assertions, tool call traces, produced artifacts)    │
   │ • 31 typed real-time events streamed via Server-Sent Events (ZStream)        │
   │ • 35 protocol safeguards (Dangling reconcile, reasoning stream, eviction)    │
   │ • Total isolation via ZIO Structured Concurrency (zero fiber leaks)          │
   └──────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Ten Universal Primitives of WeisGnosis OS

Instead of an ad-hoc "agent swarm", all operations are structured using **10 formal primitives**:

| Primitive | Role | Formal Semantics |
| :--- | :--- | :--- |
| **Case** | Case/Investigation instance | CMMN-like dynamic lifecycle: objectives, actors, epistemic audit log. |
| **Obligation** | Machine-verifiable contract clause | Predicate, cardinality (e.g. exactly 100 documents), criticality tier. |
| **WorkItem** | Unit of executable work | Assigned to a specific `Actor` (agent, operator, CI job, scanner). |
| **Artifact** | Content-addressed output | Files, reports, diff patches identified by immutable sha256 hashes. |
| **Evidence** | Immutable proof artifact | Source snapshot (verbatim excerpt, URL, log, hash, timestamp, provenance). |
| **Assertion** | Proposition with truth status | `PROPOSED` $\to$ `SUPPORTED` $\to$ `ACCEPTED` / `CONTESTED` / `REFUTED` / `SUPERSEDED`. |
| **Check** | Validator with designated authority | L0 (Schema) $\to$ L1 (Deterministic) $\to$ L2 (Grounding/NLI) $\to$ L3 (Cross-family) $\to$ L4 (Adversarial). |
| **Verdict** | Typed verification outcome | `PASS` or `FAIL(failureType, severity, target, repairHint)`. |
| **Grant** | Policy and capability grant | Tool access capabilities + data egress boundary rules (CaMeL model). |
| **Gate** | Decision authority point | Human operator approval (HITL) or external side-effect emission point. |

---

## 4. Architectural Boundaries: Temporal, WeisGnosis OS, and `weisgnosis-agents`

### 4.1. Role of Temporal
* **Durable Execution:** Guarantees workflow survival across host restarts, Kubernetes pod redeployments, and network partitions.
* **Deterministic Replay:** Reconstructs state evolution through event sourcing.
* **Signal Channels:** Supports human-in-the-loop approval pauses spanning hours, days, or weeks without occupying active threads.
* **Timeouts & Retries:** Governs activity retry policies and SLA timeouts.

### 4.2. Role of WeisGnosis OS (above Temporal)
* **Epistemic Authority:** Maintains truth tracking and provenance (Epistemic SSOT in PostgreSQL).
* **Acceptance Compiler & Arbiter:** Compiles goals into machine-executable `AcceptanceContract` instances and deterministically evaluates task completion.
* **Targeted Repair Router:** On `Verdict(FAIL)`, triggers isolated repair subflows (`FailureType -> RepairSubflow`) bounded by strict token and attempt budgets.

### 4.3. Role of `weisgnosis-agents`
* Functions as a **Stateless Worker / Actor** executing single `WorkItem` slices.
* Receives a specific task slice, evidence context, authorized MCP/local tools, and invariants.
* Runs a pure ReAct loop on ZIO fibers, streaming execution events via `ZStream[Any, AgentError, AgentEvent]`.
* Produces an **`AgentProposal`** (assertions, citations, artifacts).
* **Never** decides whether a proposal constitutes global truth, and **never** marks a task as complete.

---

## 5. Multi-Tier Verification Pipeline

Unstructured debate or self-reflection among identical models **does not constitute verification** (correlated failure modes among identical model families can reach 60%). Rigorous validation is structured across progressive authority tiers:

```text
Executor (weisgnosis-agents)
   ▼ returns Proposal
┌────────────────────────────────────────────────────────────────────────┐
│ L0: Schema & Type Gate (Instant, blocking)                             │
├────────────────────────────────────────────────────────────────────────┤
│ L1: Deterministic Checks (Parallel)                                    │
│   • Cardinality verification (processed 100 out of 100 items?)         │
│   • Citation & URL resolubility                                        │
│   • Compiler verification, test execution, SAST scanners              │
├────────────────────────────────────────────────────────────────────────┤
│ L2: Evidence & Grounding (Parallel, specialized small models)          │
│   • Natural Language Inference (NLI) entailment against citations      │
│   • Source tiering (primary vs secondary source ranking)               │
├────────────────────────────────────────────────────────────────────────┤
│ L3: Cross-Family Blind Reviewer (For Medium+ risk levels)              │
│   • Independent model from a different architecture/family             │
│   • Author's reasoning chain is hidden; evaluates only claims vs specs │
├────────────────────────────────────────────────────────────────────────┤
│ L4: Adversarial War Room (High-risk only / Final sign-off)             │
│   • Adversary agent searching actively for counter-evidence            │
└────────────────────────────────────────────────────────────────────────┘
   ▼
ARBITER (Deterministic evaluation)
   ├── PASS ──────────────────────────► Accepted into SSOT / Task Completed
   ├── REPAIR(failures[], budget) ────► Routed to Targeted Repair Router
   └── HITL(conflict, reason) ────────► Escalated to Human Operator
```

---

## 6. Targeted Repair vs Retry Storms

When a check fails, the engine avoids naive global workflow restarts:
```yaml
verdict:
  check_id: evidence.grounding.v3
  status: FAIL
  failures:
    - type: SOURCE_GAP
      severity: CRITICAL
      target: {kind: assertion, id: C-123, version: 4}
      obligation: O-7
      reason: "Only secondary source cited"
      repair_hint:
        subflow: find_primary_source
        budget: {attempts: 2, tokens: 200000}
```
* **Repair Router** dispatches an isolated `WorkItem` addressing claim `C-123`.
* Upon acquiring the primary source, only the impacted downstream dependency graph (`derived-from`) is re-evaluated.
* If the repair budget is exhausted, the issue escalates to an operator via a Human-in-the-Loop gate.

---

## 7. Library Implementation Map in `weisgnosis-agents`

`weisgnosis-agents` is designed to support this architecture seamlessly:
1. **`weisgnosis-agents-core`:**
   - Domain models: `Msg`, `Role`, `ContentBlock`, and 31 `AgentEvent` cases.
   - Streaming SSE client supporting thinking token streaming and vendor-neutral endpoints.
   - Pure ReAct engine with dangling tool reconciliation (`ToolResultBlock(INTERRUPTED)`).
   - Structured proposals: `AgentProposal(assertions, artifacts, toolTraces)`.
2. **`weisgnosis-agents-harness`:**
   - Tool result offloading (`ToolResultEvictor`).
   - Conversation compaction (`ConversationCompactor`) protecting atomic tool turns.
   - Plan mode governance (`PlanMode`) enforcing read-only constraints until approved.
3. **`weisgnosis-agents-mcp`:**
   - Native ZIO MCP client (Stdio / SSE) fully adhering to JSON-RPC 2.0 specifications.
