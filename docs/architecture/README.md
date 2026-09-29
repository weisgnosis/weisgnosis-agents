# Current architecture contracts

- [High-level design](high-level-design.md): authority, modules, and host responsibilities.
- [Mid-level design](mid-level-design.md): implemented behavior and planned integrations.
- [Low-level design](low-level-design.md): source-linked API contracts.
- [Implementation tracker](tracker.md): current delivery and remaining work.
- [Phase 4 contracts](phase-4-implementation.md): compaction, persistence, and permissions.
- [Parity mapping](test-parity.md): current coverage and outstanding audit.

Update these living documents when behavior changes. Current acceptance belongs in the affected module/package README; resolved temporary review reports are removed after explicit reviewer approval.

The message boundary contracts now document private construction, typed role factories, validated updates, and the 26 immutable factory adaptations. All 119 message methods now have checked coverage/adaptations; the tracker retains broader parity and runtime integration gaps. Content codecs and ToolUse protocol states now have explicit wire migration notes.

Event contracts distinguish 39 Scala payload variants from 31 protocol identifiers. All 13 enabled stream-test methods have mapped identifier/payload evidence; the runtime limit-ordering requirement is now verified, while other ordering requirements and legacy event compatibility remain open.
