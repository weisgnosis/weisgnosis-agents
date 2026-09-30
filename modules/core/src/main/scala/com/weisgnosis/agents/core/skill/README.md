# Preloaded skill catalogue

[SkillCatalog](SkillCatalog.scala) validates immutable host-supplied SkillDefinition values and stores one authoritative ID map. Definitions are sorted by ID. IDs, descriptions, main content and target-group names must be nonblank; duplicate IDs fail. Derived declaration codecs do not bypass checked make.

select(ids) returns a checked immutable subset with unchanged definitions, resources and target groups. Empty selection removes every skill; blank or unknown IDs fail as InvalidConfiguration. Selecting from a subset cannot recover entries removed by an ancestor. This is catalogue membership, not permission to execute any tool or mutate group activation.

Resources use exact portable logical identifiers, with no filesystem interpretation. Whitespace-only paths, empty/dot/parent segments, leading slash, backslashes, colons and control characters are rejected; spaces within nonblank identifiers and names such as a..b are allowed. Exact lookup does not trim or normalize an accepted path. The exact root SKILL.md name is reserved for main content; other keys are case-sensitive. Resource text can be empty. load returns content and proposed activation groups, or a ToolExecutionError named load_skill_through_path. It performs no effects or activation.

validateGroups checks the supplied current ToolGroups value: every target must exist and have Meta scope. The catalogue owns no runtime state, filesystem capability, network access or repository discovery. The separate runtime adapter owns actual permission admission and group activation.

groupNotes is a pure advisory projection of explicit bindings: groups and their unique skill IDs are sorted, names are JSON-quoted, and no bindings produce empty text. Shared targets combine IDs without changing raw definitions. Notes do not track loaded skills or force group retention. The runtime combines them with the same captured Meta snapshot for reset descriptions and with the atomic update result for reset output; explicit deactivation remains available. This adds text only, with no data/schema or constructor change. Exact Java prose, subclass copying and SkillHook system-prompt injection are not implemented by this projection.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the identities below, with no findings. Full `scalafmtAll scalafmtSbt verify` passed in `child-skills-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.

| Reviewed file | Git blob |
| --- | --- |
| [SkillCatalog.scala](SkillCatalog.scala) | `8b737956cd17bd1eb36122afb7cb667e09da398e` |
| [SkillCatalogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/skill/SkillCatalogSpec.scala) | `2e44a118befeb81ebc2a11d1219638883d32e791` |

The [runtime adapter](../agent/README.md#preloaded-skills) performs ordinary authorization and invocation-owned group activation; its current source/spec acceptance is separate.

- [x] The opt-in [harness Plan adapter](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/plan/README.md#dynamic-plan-admission) verifies actual runtime loader admission with ordinary permission and confirmation policy.
- [x] Both reviewers approved pure group reminders and runtime description/result integration; exact current identities and verification are recorded above and in the runtime contract.
- [ ] Next: declaration skill allowlist parsing and installation. The harness [skill adapters](../../../../../../../../../harness/src/main/scala/com/weisgnosis/agents/harness/skill/README.md) provide the accepted strict name/description profile and bounded selected local loading. Generated registration, system-prompt injection and durable activation remain unfinished.

The whitespace-path regression failed against unchanged source `189b8e8a907b9addd034f2d104dfebc0a1385b68` with spec `483032c253f236de22f2abe74c4abc930d5d9b3f`: make accepted the resource key `" "` instead of returning InvalidConfiguration. `skill-catalog-whitespace-red.log` records the direct assertion failure. The log records two passing groups and one failing group in 193 ms. The correction rejects whole-path blanks and preserves the nonblank spaced-name control; both reviewers approved the corrected scope and full verification passed.
