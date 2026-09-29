# Current architecture contracts

- [High-level design](high-level-design.md): authority, modules, and host responsibilities.
- [Mid-level design](mid-level-design.md): implemented behavior and planned integrations.
- [Low-level design](low-level-design.md): source-linked API contracts.
- [Implementation tracker](tracker.md): current delivery and remaining work.
- [Phase 4 contracts](phase-4-implementation.md): compaction, persistence, and permissions.
- [Parity mapping](test-parity.md): current coverage and outstanding audit.

Update these living documents when behavior changes. Current acceptance belongs in the affected module/package README; resolved temporary review reports are removed after explicit reviewer approval.

The message boundary contracts now document private construction, typed role factories, validated updates, and the 26 immutable factory adaptations. All 119 message methods now have checked coverage/adaptations; the tracker retains broader parity and runtime integration gaps. Content codecs and ToolUse protocol states now have explicit wire migration notes.

Event contracts distinguish 39 Scala payload variants from 31 protocol identifiers. All 13 enabled stream-test methods have mapped identifier/payload evidence; the runtime limit-ordering requirement is now verified, while other ordering requirements remain open; ten deprecated legacy-envelope methods are explicitly excluded rather than counted as passing tests.

Structured completion now resolves one payload AST, prepares within the attempt timeout, commits history, completes required scoped observation, and emits AgentResult before AgentEnd. Direct and structured streaming APIs share the same path; Auto fallback retains two attempt boundary pairs. The agent README records the scoped acceptance and exact reviewed source identities.

ChatCompletionsClient now connects scoped HTTP completion to ReAct. Runtime-assigned reply/request context correlates live deltas and successful model completion; only fully validated streams can dispatch tools. Live event ordering is an explicit adaptation from the disabled upstream buffered-order sketches. Completed local/inline tool results now retain request provenance, with local state committed before output publication. Incremental/structured tool output, richer provider content, and exhaustive transport mapping remain tracked work.

Shared JSON validation now lives in core.json, independent of client/model layering. Structured text, metadata, captured payloads, and synthetic wrappers reject trailing data or ambiguous fields before finalized publication. Real HTTP structured scenarios are verified; typed capability selection is implemented; visible text transformation is implemented; typed post-reasoning policy is implemented; local-result eviction is integrated; compaction is installed through the validated history port; general model-call middleware and broader legacy hooks remain next steps.

The core authorization boundary is implemented and reviewed; the interactive harness adapter now connects policy and approval requests. Durable confirmation/resumption stays explicitly pending in the tracker.

Plan-mode contracts now distinguish reviewed static syntax, opt-in runtime admission and standalone draft storage from pending child propagation. The parity inventory keeps all fourteen upstream methods individually visible, with eight adapted and six pending after the directory increment.

PlanSession now adds reviewed in-memory draft and approval authority to plan admission. Request projection and standalone draft storage are now available; the tracker keeps approval restoration and model-facing review tools separate; partial manager mode evidence does not close its path assertions.

Plan context now has a reviewed request-only projection adapter. It samples once, preserves canonical/System/tool history and never substitutes prompt text for live permission authority. The parity inventory records 8/14 plan adaptations; skills and child propagation remain pending.

Standalone local plan drafts now have reviewed ownership, exact encoding and atomic publication. Stored content is not an approval credential; session-generation integration is available; opt-in model-facing tools are available; location context is available; supervised child propagation is the next tracked task.

Stored plan sessions now invalidate review before writing and reopen content only as unapproved Plan drafts. Opt-in model-facing controls are now available; descriptive location context is available; typed workspace-relative directories are available; durable approval restoration remains separate work.

PlanTools now adds strict opt-in enter/write/exit capabilities. Exact-ticket host review is mandatory inside exit even with ordinary Allow rules; publisher transport and authenticated decision routing remain host responsibilities.

PlanContext now associates one snapshot with configured/observed location metadata. Four additional manager/prompt scenarios and the stricter typed-directory scenario are adapted; no location value grants filesystem or approval authority.

Typed PlanDirectory configuration now selects an existing strict descendant of an explicit host-controlled workspace. Native Path opening retains its semantics; no directory creation or implicit workspace discovery is provided. Plan parity now records eight of fourteen named adaptations; skills and supervised child propagation remain pending.

Local scoped task supervision now has reviewed execution ownership and promotion. The subagent inventory distinguishes eighteen supporting tests from still-pending actual spawn/task-method parity. Runtime-owned depth is available; configured child construction and registry are the next tracked scope.
