# Inline and local skill verification

[SkillMarkdownParserSpec](SkillMarkdownParserSpec.scala) uses three table-driven groups:

- Exact body/declaration projection for plain, quoted, folded/literal, Unicode and flow-mapping strings, LF/CRLF framing, empty capabilities and checked catalogue reentry.
- Typed rejection of missing/blank fields, nonstring scalars, duplicate/unknown fields, nested shapes, anchors/tags and malformed YAML without fallback.
- Positive UTF-16 limits, exact size boundaries, delimiter near-misses, BOM/leading-whitespace rejection and blank bodies.

- [x] Both independent reviewers approved the [source contract and exact identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#verification-and-acceptance). Focused and full verification passed; current evidence belongs to that contract.

## Selected local loading

[LocalSkillSpec](LocalSkillSpec.scala) adds three grouped contracts: cold scoped loading of exact metadata/body and selected UTF-8 resources with stream release; trust, traversal/sensitive-path, identifier and file-shape rejection; and count, byte, decoding and parser bounds. Selected empty text and multibyte UTF-8 exercise exact limits, while corrupt unselected files remain unread. No filesystem discovery or generated activation is inferred.

- [x] `path_review` and `current_docs` approved both [local source/test identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#selected-local-loading), with no findings. Focused `local-skill-test.log` passed all three groups in 218 ms.
- [x] Full `local-skill-final-verify.log` verification passed with zero compiler warnings or failures; the linked source acceptance records totals and timing. Platform symlink execution is not covered; the source contract records the advisory filesystem guarantees.
- [ ] Next: declaration skill allowlist parsing and installation. Repository discovery, general metadata and capability installation remain separate.
