# Inline skill declarations

[SkillMarkdownParser.parse](SkillMarkdownParser.scala) reads a bounded inline SKILL.md profile into core SkillDefinition. The only accepted header fields are name and description, both required nonblank YAML strings. Name becomes the exact ID; description and the entire body retain their text. Resources and activation targets are empty: parsing acquires no capability and does not create group names or install a skill. Hosts can pass the result through checked SkillCatalog.make and explicitly configure any resources/targets.

The header begins at column zero with a delimiter line containing exactly three hyphens plus optional spaces/tabs. LF and CRLF are supported, with no BOM or preceding whitespace. The first matching closing delimiter ends the header; the remaining body is preserved without trimming and must be nonblank. Quoted, plain textual, literal and folded string values follow YAML string semantics. Flow mappings are accepted within the same two-field profile.

Plain numeric, boolean and null values are rejected; their quoted string forms are allowed. Duplicate or unknown fields, nested values, aliases/anchors, explicit tags and YAML version/tag directives are rejected. Malformed YAML fails as AgentError.InvalidConfiguration, without permissive fallback or object loading. Other parser exceptions become bounded syntax diagnostics containing the exception class, not input content. Parsing uses the existing SnakeYAML Engine dependency; allocation, iteration, resolution and foreign event access are suspended in ZIO, with immutable parser state.

SkillMarkdownLimits defaults to 128,000 document characters and 16,000 header characters, measured as UTF-16 units. Both limits must be positive. The document includes delimiters/body; the header excludes delimiter lines and includes the newline before the closing delimiter. Validation precedes YAML reader creation. These bounds intentionally differ from upstream's 16,384-code-point header limit. The limits have a derived codec; parse always validates decoded values.

This additive API changes no core declaration, catalogue codec or existing subagent parser. It deliberately accepts a narrower profile than generic upstream metadata parsing: additional metadata fails rather than disappearing. General metadata retention, Markdown generation, filesystem/repository loading, source-derived IDs and skill capability registration remain unfinished.

## Verification and acceptance

- [x] `path_review` approved FP/ZIO correctness and `current_docs` approved invariants for the two identities below, with no findings.
- [x] Three focused table groups passed in `skill-markdown-test.log` (192 ms). Full `scalafmtAll scalafmtSbt verify` passed in `skill-markdown-verify.log`, with zero compiler warnings or failures; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current totals.
- [ ] Next: bounded local skill loading through an explicitly host-owned filesystem boundary; generic metadata and capability registration remain separate.

| File | Reviewed Git blob |
| --- | --- |
| [SkillMarkdownParser.scala](SkillMarkdownParser.scala) | `efda25a76751b91386a17bf7bbe100ea4d1cb2cf` |
| [SkillMarkdownParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/SkillMarkdownParserSpec.scala) | `599378aa9f92b7570326f703f27f93500db39814` |

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) record the bounded mappings and exclusions. No exhaustive upstream skill-parser or loader parity is claimed.
