# Preloaded skill catalogue

[SkillCatalog](SkillCatalog.scala) validates immutable host-supplied SkillDefinition values and stores one authoritative ID map. Definitions are sorted by ID. IDs, descriptions, main content and target-group names must be nonblank; duplicate IDs fail. Derived declaration codecs do not bypass checked make.

SkillDefinition.metadata is an ordered Json.Obj of inert extras, defaulting to an empty object. Checked make rejects duplicate keys recursively and reserved root name/description keys; required identity and description remain typed fields. Metadata named resources, activateGroups or allowed-tools cannot install resources, activate groups or grant permissions. Catalogue selection preserves the full metadata value.

Migration: SkillDefinition now has six fields. Existing calls and missing-field JSON use empty metadata; positional patterns/Product arity and encoded data change, and dependent binaries must be rebuilt. No binary compatibility is promised. Codecs do not replace checked make.

select(ids) returns a checked immutable subset with unchanged definitions, metadata, resources and target groups. Empty selection removes every skill; blank or unknown IDs fail as InvalidConfiguration. Selecting from a subset cannot recover entries removed by an ancestor. This is catalogue membership, not permission to execute any tool or mutate group activation.

Resources use exact portable logical identifiers, with no filesystem interpretation. Whitespace-only paths, empty/dot/parent segments, leading slash, backslashes, colons and control characters are rejected; spaces within nonblank identifiers and names such as a..b are allowed. Exact lookup does not trim or normalize an accepted path. The exact root SKILL.md name is reserved for main content; other keys are case-sensitive. Resource text can be empty. load returns content and proposed activation groups, or a ToolExecutionError named load_skill_through_path. It performs no effects or activation.

validateGroups checks the supplied current ToolGroups value: every target must exist and have Meta scope. The catalogue owns no runtime state, filesystem capability, network access or repository discovery. The separate runtime adapter owns actual permission admission and group activation.

groupNotes is a pure advisory projection of explicit bindings: groups and their unique skill IDs are sorted, names are JSON-quoted, and no bindings produce empty text. Shared targets combine IDs without changing raw definitions. Notes do not track loaded skills or force group retention. The runtime combines them with the same captured Meta snapshot for reset descriptions and with the atomic update result for reset output; explicit deactivation remains available. The reminder projection changes text only; metadata has the separate declaration migration above. Exact Java prose and subclass copying are excluded; the separate systemPrompt projection supplies request-only catalogue text.

The systemPrompt projection is pure and derived only from this selected catalogue: sorted IDs, descriptions and exact logical resource names are JSON-escaped. It excludes skill bodies, resource content and inert metadata. Empty catalogues produce no prompt. This descriptive text neither loads resources nor activates groups.

- [x] Both independent reviewers approved systemPrompt and its request-only runtime integration. One pure projection group verifies selected visibility, deterministic JSON escaping, logical resource names and excluded content/metadata; runtime evidence is linked below.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the preloaded catalogue baseline, including request-only prompt projection and unchanged checked metadata/selection contracts, with no findings.

Full `scalafmtAll scalafmtSbt verify` passed in `skill-system-prompt-verify.log`: 602 core / 690 harness / 82 MCP tests (1,374 total), compile task 2 seconds and test task 15 seconds, with zero compiler warnings or failures. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals.

| Reviewed preloaded baseline | Git blob |
| --- | --- |
| [SkillCatalog.scala](SkillCatalog.scala) | `5f3635463c6b195aa4adf62ede42429ecc9df2a4` |
| [SkillCatalogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/skill/SkillCatalogSpec.scala) | `ae298bce6828be805c1536d5a333ea7d09c31aad` |

The [runtime adapter](../agent/README.md#deferred-skill-reads) performs ordinary authorization and invocation-owned group activation; its skill-specific evidence is recorded under deferred skill reads, with the current ReActAgent identity in [incremental execution](../tool/README.md#incremental-execution).

- [x] The opt-in [harness Plan adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) verifies actual runtime loader admission with ordinary permission and confirmation policy.
- [x] Both reviewers approved pure group reminders and runtime description/result integration; current catalogue identities are recorded under deferred resource capabilities; earlier verification remains scoped to its reviewed baseline.
- [ ] Automatic repository fallback, source IDs, caches, generated registration and durable activation remain unfinished. The harness [skill adapters](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md) own bounded local loading and eager TextTree discovery; repository-wide next work is in the [tracker](../../../../../../../../../../docs/architecture/tracker.md#immediate-next-work).

## Deferred resource capabilities

[SkillLibrary](SkillLibrary.scala) combines a checked catalogue with exact SkillResourceKey(skillId, path) reader capabilities. make rejects unknown skills, invalid/reserved paths and collisions with preloaded resources without invoking a reader. select narrows both definitions and readers; descendants cannot recover removed entries. systemPrompt advertises selected logical names, never physical paths or fetched text. SkillCatalog remains pure and preloaded; SkillLibrary has no codec or ambient filesystem fallback.

resolve reads only an explicitly bound capability and closes its per-read Scope before returning text with catalogue-owned activation targets. Readers are borrowed host capabilities; the host retains any captured dependency lifetime. Repeated resolution rereads rather than caches. Failures, defects and interruption preserve complete causes; no successful activation result precedes cleanup. from(catalog) retains existing eager behavior.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the identities below, with no findings; full verification passed. The earlier table is the preloaded baseline; the unchanged SkillCatalogSpec retains its identity.

Full `scalafmtAll scalafmtSbt verify` passed in `skill-library-final-verify.log`: 605 core / 693 harness / 82 MCP tests (1,380 total), compile task 9 seconds and test task 15 seconds, zero compiler warnings or failures. Focused checks passed 18 core groups in 638 ms and 24 harness groups in 725 ms. This acceptance covers the exact ten changed source/test identities recorded here and in the linked runtime, local-reader and child contracts; no automatic repository fallback or whole upstream lazy-repository parity is claimed.

| Current capability file | Git blob |
| --- | --- |
| [SkillCatalog.scala](SkillCatalog.scala) | `4f07f4673e20b12ed21e819364da771b6f081c0e` |
| [SkillLibrary.scala](SkillLibrary.scala) | `11333b60a29e1cc80f0f3d2c2fec3bf1324a9d94` |
| [SkillLibrarySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/skill/SkillLibrarySpec.scala) | `0cc7faf99960df2475f7bade924ddd789e664a65` |

The whitespace-path regression failed against unchanged source `189b8e8a907b9addd034f2d104dfebc0a1385b68` with spec `483032c253f236de22f2abe74c4abc930d5d9b3f`: make accepted the resource key `" "` instead of returning InvalidConfiguration. `skill-catalog-whitespace-red.log` records the direct assertion failure. The log records two passing groups and one failing group in 193 ms. The correction rejects whole-path blanks and preserves the nonblank spaced-name control; both reviewers approved the corrected scope and full verification passed.
