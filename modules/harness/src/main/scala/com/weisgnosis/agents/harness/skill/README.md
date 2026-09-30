# Inline and selected local skills

[SkillMarkdownParser.parse](SkillMarkdownParser.scala) reads a bounded inline SKILL.md profile into core SkillDefinition. The required name and description fields must be nonblank YAML strings. Name becomes the exact ID; string values follow YAML semantics and the body is preserved exactly. Additional fields become ordered JSON metadata, excluding name/description. Resources and activation targets are empty: parsing acquires no capability and does not create group names or install a skill. Hosts can pass the result through checked SkillCatalog.make and explicitly configure any resources/targets.

The header begins at column zero with a delimiter line containing exactly three hyphens plus optional spaces/tabs. LF and CRLF are supported, with no BOM or preceding whitespace. The first matching closing delimiter ends the header; the remaining body is preserved without trimming and must be nonblank. Quoted, plain textual, literal and folded string values follow YAML string semantics. Extra metadata supports block/flow mappings and sequences, strings, JSON-compatible finite numbers, booleans and null. Mapping keys must be strings; encounter order is retained at every object level.

Required name/description reject numeric, boolean, null and collection values; quoted string forms remain valid. Duplicate keys at every depth, collection/nonstring keys, aliases/anchors, explicit tags and YAML version/tag directives are rejected. YAML-only numeric spellings and nonfinite values are rejected rather than coerced. Metadata remains inert even for keys named allowed-tools, resources or activateGroups. Malformed YAML fails as AgentError.InvalidConfiguration, without permissive fallback or object loading. Other parser exceptions become bounded syntax diagnostics containing the exception class, not input content. Parsing uses the existing SnakeYAML Engine dependency; allocation, iteration, resolution and foreign event access are suspended in ZIO, with immutable parser state.

SkillMarkdownLimits defaults to 128,000 document characters and 16,000 header characters, measured as UTF-16 units. maxMetadataDepth defaults to 10 and counts nested metadata collections, excluding the outer header mapping; scalar values add no collection depth. All three limits must be positive. The document includes delimiters/body; the header excludes delimiter lines and includes the newline before the closing delimiter. Validation precedes YAML reader creation. These bounds intentionally differ from upstream's 16,384-code-point header limit. The limits have a derived codec; parse always validates decoded values.

Migration: SkillMarkdownLimits appends the defaulted third field maxMetadataDepth; old JSON without it uses 10. SkillDefinition appends default-empty metadata as its sixth field. Positional patterns/Product arity and encoded data change; rebuild dependent binaries. The subagent declaration parser is unchanged. Markdown generation, repository discovery, source-derived IDs and skill capability registration remain unfinished; selected local loading is defined below.

## Verification and acceptance

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for ordered inert metadata, bounded parsing and preservation, with no findings.
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `skill-metadata-verify.log`: 600 core / 687 harness / 79 MCP tests (1,366 total), compile task 1 second and test task 14 seconds, with zero compiler warnings or failures. Five parser groups include two new metadata groups; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals.
- [ ] Next: bounded host-owned repository discovery. Capability registration remains separate.

| Reviewed file | Reviewed Git blob |
| --- | --- |
| [SkillMarkdownParser.scala](SkillMarkdownParser.scala) | `90a75be3050a79b13f356b3f39198b5b07b04699` |
| [SkillMarkdownParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/SkillMarkdownParserSpec.scala) | `477a44c3bc663ed1db1a377d4ddc0437e72991f4` |

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) record the bounded mappings and exclusions. No exhaustive upstream skill-parser or loader parity is claimed.

## Selected local loading

[LocalSkill.load](LocalSkill.scala) reads exact `SKILL.md` from one host-selected directory and only the explicitly selected resource identifiers, in sorted order. It uses the strict inline profile above, preserves the declared ID, inert metadata and exact UTF-8 text, and returns no activation targets. The directory name does not rename the skill; unselected files are neither discovered nor read. Missing, malformed, unreadable or oversized selected content fails the whole load.

WorkspaceTrust.Untrusted fails before filesystem access. HostControlled requires trusted ancestors. Raw parent traversal is rejected before normalization; PathSafety screens lexical and canonical paths. NOFOLLOW attributes reject symlinks and non-directory ancestors/nonregular leaves at the selected root and each relative component, with canonical containment inside that root. These checks are advisory under host-controlled filesystem mutation, not an atomic sandbox. Resource identifiers must have nonblank slash-separated segments: no leading slash, empty/dot/parent segment, backslash, colon or control character. Exact `SKILL.md` is reserved; nonblank names with spaces remain valid.

LocalSkillLimits defaults to 64 resources, 512,000 document bytes, 256,000 bytes per resource and default SkillMarkdownLimits. All limits are positive and byte limits stay below Int.MaxValue; resource count, identifier and all parser-limit validation, including metadata depth, precede I/O. Each scoped stream reads at most its byte limit plus one, then strict UTF-8 decoding rejects malformed input. Empty resource text is valid. Blocking operations and error translation are suspended under a mask; stream close failures remain defects. Cleanup joins before return, without a cancellation deadline or protection against hostile/blocking filesystems.

LocalSkill keeps its call signature; its nested parser limits and returned SkillDefinition follow the migrations above. Hosts explicitly choose trust, directory and resources, then may validate/install the returned definition through SkillCatalog; no filesystem authority enters the core catalogue. Limits use a derived codec and are validated on every load. Binary resources, recursive discovery, caches, writes and automatic group generation are outside this boundary.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for local metadata preservation and pre-I/O depth validation, with no findings. The three local-loader groups and full verification passed in `skill-metadata-verify.log`, as recorded above.

| Reviewed file | Reviewed Git blob |
| --- | --- |
| [LocalSkill.scala](LocalSkill.scala) | `3c6e81e5b9a8102a555b4c3e31e68c18d206ee70` |
| [LocalSkillSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillSpec.scala) | `cd0a1531f8de85150a98b70c234b13865b3a1404` |

Tests cover real selected reads and resource release. Symlink rejection is source-reviewed; platform symlink/junction integration coverage remains unverified.
