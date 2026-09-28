# `weisgnosis-agents` Documentation Hub

Welcome to the documentation hub for **`weisgnosis-agents`**, the production-grade, Pure FP multi-agent framework for Scala 3 and ZIO 2.

---

## 📚 Architectural Guides

1. **[High-Level Architecture Design (HLD)](architecture/high-level-design.md)**  
   Executive overview, system layering, module boundaries, core invariants, and ecosystem placement.

2. **[Mid-Level Architecture Design (MLD)](architecture/mid-level-design.md)**  
   Component lifecycles, ReAct state machines, the 31-event streaming pipeline, compaction algorithms, and HITL mechanics.

3. **[Low-Level Technical Specification (LLD)](architecture/low-level-design.md)**  
   Concrete Scala 3 ADT definitions, `zio-json` codecs, method signatures, error hierarchies, and protocol schemas.

4. **[Master Architecture Tracker](architecture/tracker.md)**  
   Comprehensive package implementation checklist, 1:1 test parity maps against AgentScope Java, and invariant verification statuses.

5. **[Architectural Manifesto: WeisGnosis OS & Agents](weisgnosis-os-and-agents-architecture.md)**  
   The epistemic boundary (*"Runtime owns truth and completion"*), 10 universal primitives, multi-tier verification pipelines, and targeted repair routers.

6. **[Deep Research & Porting Guide](deep-research-and-porting-guide.md)**  
   Audit of the upstream AgentScope Java codebase, LOC forecasts, Macro (Temporal) vs Micro (ZIO) separation, and catalog of 35 production edge cases.

---

## 📦 Module Navigation

- **[`weisgnosis-agents-core`](../modules/core/README.md)**: ReAct engine, 31-event stream, tool engine, and streaming SSE client.
- **[`weisgnosis-agents-harness`](../modules/harness/README.md)**: Memory compaction, tool result eviction, permission gating, subagents, and plan mode.
- **[`weisgnosis-agents-mcp`](../modules/mcp/README.md)**: Model Context Protocol (MCP) client, Stdio & SSE transports, and toolkit bridging.
- **[Complete Module Index](../modules/README.md)**: Master package registry and mini-wiki index.
