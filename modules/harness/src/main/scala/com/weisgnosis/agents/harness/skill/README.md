# Inline and selected local skills

[SkillMarkdownParser.parse](SkillMarkdownParser.scala) reads a bounded inline SKILL.md profile into core SkillDefinition. The only accepted header fields are name and description, both required nonblank YAML strings. Name becomes the exact ID; description and the entire body retain their text. Resources and activation targets are empty: parsing acquires no capability and does not create group names or install a skill. Hosts can pass the result through checked SkillCatalog.make and explicitly configure any resources/targets.

The header begins at column zero with a delimiter line containing exactly three hyphens plus optional spaces/tabs. LF and CRLF are supported, with no BOM or preceding whitespace. The first matching closing delimiter ends the header; the remaining body is preserved without trimming and must be nonblank. Quoted, plain textual, literal and folded string values follow YAML string semantics. Flow mappings are accepted within the same two-field profile.

Plain numeric, boolean and null values are rejected; their quoted string forms are allowed. Duplicate or unknown fields, nested values, aliases/anchors, explicit tags and YAML version/tag directives are rejected. Malformed YAML fails as AgentError.InvalidConfiguration, without permissive fallback or object loading. Other parser exceptions become bounded syntax diagnostics containing the exception class, not input content. Parsing uses the existing SnakeYAML Engine dependency; allocation, iteration, resolution and foreign event access are suspended in ZIO, with immutable parser state.

SkillMarkdownLimits defaults to 128,000 document characters and 16,000 header characters, measured as UTF-16 units. Both limits must be positive. The document includes delimiters/body; the header excludes delimiter lines and includes the newline before the closing delimiter. Validation precedes YAML reader creation. These bounds intentionally differ from upstream's 16,384-code-point header limit. The limits have a derived codec; parse always validates decoded values.

This additive API changes no core declaration, catalogue codec or existing subagent parser. It deliberately accepts a narrower profile than generic upstream metadata parsing: additional metadata fails rather than disappearing. General metadata retention, Markdown generation, repository discovery, source-derived IDs and skill capability registration remain unfinished; selected local loading is defined below.

## Verification and acceptance

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the two identities below, with no findings.
- [x] Three focused table groups passed in `skill-markdown-test.log` (192 ms). Full `scalafmtAll scalafmtSbt verify` passed in `skill-markdown-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.
- [ ] Next: bounded preservation of additional skill metadata. Generic metadata, repository discovery and capability registration remain separate.

| File | Reviewed Git blob |
| --- | --- |
| [SkillMarkdownParser.scala](SkillMarkdownParser.scala) | `efda25a76751b91386a17bf7bbe100ea4d1cb2cf` |
| [SkillMarkdownParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/SkillMarkdownParserSpec.scala) | `599378aa9f92b7570326f703f27f93500db39814` |

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) record the bounded mappings and exclusions. No exhaustive upstream skill-parser or loader parity is claimed.

## Selected local loading

[LocalSkill.load](LocalSkill.scala) reads exact `SKILL.md` from one host-selected directory and only the explicitly selected resource identifiers, in sorted order. It uses the strict inline profile above, preserves the metadata ID and exact UTF-8 text, and returns no activation targets. The directory name does not rename the skill; unselected files are neither discovered nor read. Missing, malformed, unreadable or oversized selected content fails the whole load.

WorkspaceTrust.Untrusted fails before filesystem access. HostControlled requires trusted ancestors. Raw parent traversal is rejected before normalization; PathSafety screens lexical and canonical paths. NOFOLLOW attributes reject symlinks and non-directory ancestors/nonregular leaves at the selected root and each relative component, with canonical containment inside that root. These checks are advisory under host-controlled filesystem mutation, not an atomic sandbox. Resource identifiers must have nonblank slash-separated segments: no leading slash, empty/dot/parent segment, backslash, colon or control character. Exact `SKILL.md` is reserved; nonblank names with spaces remain valid.

LocalSkillLimits defaults to 64 resources, 512,000 document bytes, 256,000 bytes per resource and default SkillMarkdownLimits. All limits are positive and byte limits stay below Int.MaxValue; resource count and identifier validation precede I/O. Each scoped stream reads at most its byte limit plus one, then strict UTF-8 decoding rejects malformed input. Empty resource text is valid. Blocking operations and error translation are suspended under a mask; stream close failures remain defects. Cleanup joins before return, without a cancellation deadline or protection against hostile/blocking filesystems.

Migration: this additive API changes no existing parser or catalogue contract. Hosts explicitly choose trust, directory and resources, then may validate/install the returned definition through SkillCatalog; no filesystem authority enters the core catalogue. Limits use a derived codec and are validated on every load. Binary resources, recursive discovery, caches, writes and automatic group generation are outside this boundary.

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for both identities below, with no findings. The three focused groups passed in `local-skill-test.log` (218 ms).
- [x] Full `local-skill-final-verify.log` verification passed: 599 core / 681 harness / 79 MCP tests (1,359 total), compile task 6 seconds and test task 12 seconds, with zero compiler warnings or failures. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals.

| File | Reviewed Git blob |
| --- | --- |
| [LocalSkill.scala](LocalSkill.scala) | `c13bbc014147f5f0afe01cb63e1eb3f448bc02a3` |
| [LocalSkillSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillSpec.scala) | `3c70e86b97f07e9cbc4720b2fdd1d0e52c112c3e` |

Tests cover real selected reads and resource release. Symlink rejection is source-reviewed; platform symlink/junction integration coverage remains unverified.
