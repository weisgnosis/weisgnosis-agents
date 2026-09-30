# Preloaded skill catalogue

[SkillCatalog](SkillCatalog.scala) validates immutable host-supplied SkillDefinition values and stores one authoritative ID map. Definitions are sorted by ID. IDs, descriptions, main content and target-group names must be nonblank; duplicate IDs fail. Derived declaration codecs do not bypass checked make.

Resources use exact portable logical identifiers, with no filesystem interpretation. Whitespace-only paths, empty/dot/parent segments, leading slash, backslashes, colons and control characters are rejected; spaces within nonblank identifiers and names such as a..b are allowed. Exact lookup does not trim or normalize an accepted path. The exact root SKILL.md name is reserved for main content; other keys are case-sensitive. Resource text can be empty. load returns content and proposed activation groups, or a ToolExecutionError named load_skill_through_path. It performs no effects or activation.

validateGroups checks the supplied current ToolGroups value: every target must exist and have Meta scope. The catalogue owns no runtime state, filesystem capability, network access or repository discovery. The separate runtime adapter owns actual permission admission and group activation.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the identities below, with no findings. Full `scalafmtAll scalafmtSbt verify` passed in `skill-runtime-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.

| Reviewed file | Git blob |
| --- | --- |
| [SkillCatalog.scala](SkillCatalog.scala) | `5c51628bfa4f6d4d03564ffb098e9234e20a29ec` |
| [SkillCatalogSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/core/skill/SkillCatalogSpec.scala) | `3e904a9a5f50e589241b8651fc91c411f96819e3` |

The [runtime adapter](../agent/README.md#preloaded-skills) performs ordinary authorization and invocation-owned group activation; its current source/spec acceptance is separate.

- [ ] Next: actual harness Plan admission for the runtime skill loader. The read-only flag alone does not establish that integration.
- [ ] Keep filesystem discovery, skill reminders and durable activation outside this bounded preloaded contract.

The whitespace-path regression failed against unchanged source `189b8e8a907b9addd034f2d104dfebc0a1385b68` with spec `483032c253f236de22f2abe74c4abc930d5d9b3f`: make accepted the resource key `" "` instead of returning InvalidConfiguration. `skill-catalog-whitespace-red.log` records the direct assertion failure. The log records two passing groups and one failing group in 193 ms. The correction rejects whole-path blanks and preserves the nonblank spaced-name control; both reviewers approved the corrected scope and full verification passed.
