# Inline skill parsing: verification

[SkillMarkdownParserSpec](SkillMarkdownParserSpec.scala) uses three table-driven groups:

- Exact body/declaration projection for plain, quoted, folded/literal, Unicode and flow-mapping strings, LF/CRLF framing, empty capabilities and checked catalogue reentry.
- Typed rejection of missing/blank fields, nonstring scalars, duplicate/unknown fields, nested shapes, anchors/tags and malformed YAML without fallback.
- Positive UTF-16 limits, exact size boundaries, delimiter near-misses, BOM/leading-whitespace rejection and blank bodies.

- [x] Both independent reviewers approved the [source contract and exact identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#verification-and-acceptance). Focused and full verification passed; current evidence belongs to that contract.
- [ ] Verify separately scoped filesystem/repository loading, generic metadata preservation and capability installation when implemented. These tests parse immutable text only.
