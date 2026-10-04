# Inline and local skill verification

[SkillMarkdownParserSpec](SkillMarkdownParserSpec.scala) covers five grouped bounded contracts:

- Exact body/declaration projection for plain, quoted, folded/literal, Unicode and flow-mapping strings, LF/CRLF framing, empty capabilities and checked catalogue reentry.
- Required-string validation; ordered inert nested metadata; recursive duplicate, invalid-key, unsupported scalar/tag/alias and malformed-YAML rejection without fallback.
- Positive UTF-16/depth limits, exact size/depth boundaries, delimiter near-misses, BOM/leading-whitespace rejection and blank bodies.

- [x] `registry_contract` and `current_docs` approved the final metadata scope. The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#verification-and-acceptance) records exact current identities and full `skill-metadata-verify.log` verification.

## Selected local loading

[LocalSkillSpec](LocalSkillSpec.scala) adds three grouped contracts: cold scoped loading of exact metadata/body and selected UTF-8 resources with stream release; trust, traversal/sensitive-path, identifier and file-shape rejection; and count, byte, decoding and parser bounds. Selected empty text and multibyte UTF-8 exercise exact limits, while corrupt unselected files remain unread. Metadata is preserved independently of selected resources and activation targets; nonpositive depth limits fail before I/O. No filesystem discovery or generated activation is inferred.

- [x] `registry_contract` and `current_docs` approved the [local source/test identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#selected-local-loading), including metadata preservation and pre-I/O depth validation. Full `skill-metadata-verify.log` verification passed with zero compiler warnings or failures. Platform symlink execution remains unverified; the source contract records the advisory filesystem guarantees.
- [x] Bounded repository discovery below is independently reviewed and verified; capability installation remains separate.

## Local repository discovery

[LocalSkillRepositorySpec](LocalSkillRepositorySpec.scala) covers three grouped contracts: cold immediate discovery with exact declared data and no resource loading; root/trust/path/file-shape and whole-batch validation; and all-entry, selected-skill and inherited local limits. Directory scan ownership and filename processing are separate from the returned catalogue's ID ordering. The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#local-repository-discovery) defines deliberate upstream differences and unverified platform behavior.

- [x] `registry_contract` and `current_docs` approved the exact repository/local-loader identities in the source contract. Focused six repository/local-loader groups passed in 336 ms; full `local-skill-repository-verify.log` verification passed with zero compiler warnings or failures.
