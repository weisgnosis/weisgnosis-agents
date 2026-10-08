# Preloaded skill catalogue

[SkillCatalog](SkillCatalog.scala) validates immutable host-supplied SkillDefinition values and stores one authoritative ID map. Definitions are sorted by ID. IDs, descriptions, main content and target-group names must be nonblank; duplicate IDs fail. Derived declaration codecs do not bypass checked make.

SkillDefinition.metadata is an ordered Json.Obj of inert extras, defaulting to an empty object. Checked make rejects duplicate keys recursively and reserved root name/description keys; required identity and description remain typed fields. Metadata named resources, activateGroups or allowed-tools cannot install resources, activate groups or grant permissions. Catalogue selection preserves the full metadata value.

Migration: SkillDefinition now has six fields. Existing calls and missing-field JSON use empty metadata; positional patterns/Product arity and encoded data change, and dependent binaries must be rebuilt. No binary compatibility is promised. Codecs do not replace checked make.

select(ids) returns a checked immutable subset with unchanged definitions, metadata, resources and target groups. Empty selection removes every skill; blank or unknown IDs fail as InvalidConfiguration. Selecting from a subset cannot recover entries removed by an ancestor. This is catalogue membership, not permission to execute any tool or mutate group activation.

Resources use exact portable logical identifiers, with no filesystem interpretation. Whitespace-only paths, empty/dot/parent segments, leading slash, backslashes, colons and control characters are rejected; spaces within nonblank identifiers and names such as a..b are allowed. Exact lookup does not trim or normalize an accepted path. The exact root SKILL.md name is reserved for main content; other keys are case-sensitive. Resource text can be empty. load returns content and proposed activation groups, or a ToolExecutionError named load_skill_through_path. It performs no effects or activation.

validateGroups checks the supplied current ToolGroups value: every target must exist and have Meta scope. The catalogue owns no runtime state, filesystem capability, network access or repository discovery. The [runtime adapter](../agent/README.md#deferred-skill-reads) owns ordinary permission admission and invocation-owned group activation.

groupNotes is a pure advisory projection of explicit bindings: groups and their unique skill IDs are sorted, names are JSON-quoted, and no bindings produce empty text. Shared targets combine IDs without changing raw definitions. Notes do not track loaded skills or force group retention. The runtime combines them with the same captured Meta snapshot for reset descriptions and with the atomic update result for reset output; explicit deactivation remains available. The reminder projection changes text only; metadata has the separate declaration migration above. Exact Java prose and subclass copying are excluded; the separate systemPrompt projection supplies request-only catalogue text.

The systemPrompt projection is pure and derived only from this selected catalogue: sorted IDs, descriptions and exact logical resource names are JSON-escaped. It excludes skill bodies, resource content and inert metadata. Empty catalogues produce no prompt. This descriptive text neither loads resources nor activates groups.

- The opt-in [harness Plan adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) verifies actual runtime loader admission with ordinary permission and confirmation policy.
- [ ] Automatic repository fallback, source IDs, caches, generated registration and durable activation remain unfinished. The harness [skill adapters](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md) own bounded local loading and eager TextTree discovery; repository-wide next work is in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#immediate-next-work).

## Deferred resource capabilities

[SkillLibrary](SkillLibrary.scala) combines a checked catalogue with exact SkillResourceKey(skillId, path) reader capabilities. make rejects unknown skills, invalid/reserved paths and collisions with preloaded resources without invoking a reader. select narrows both definitions and readers; descendants cannot recover removed entries. systemPrompt advertises selected logical names, never physical paths or fetched text. SkillCatalog remains pure and preloaded; SkillLibrary has no codec or ambient filesystem fallback.

resolve reads only an explicitly bound capability and closes its per-read Scope before returning text with catalogue-owned activation targets. Readers are borrowed host capabilities; the host retains any captured dependency lifetime. Repeated resolution rereads rather than caches. Failures, defects and interruption preserve complete causes; no successful activation result precedes cleanup. from(catalog) retains existing eager behavior.
