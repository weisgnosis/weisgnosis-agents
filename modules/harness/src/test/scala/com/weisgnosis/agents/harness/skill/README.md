# Inline and local skill verification

The [current BOM compatibility scope](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#utf-8-bom-frontmatter-compatibility) records actual failing-before-fix evidence, both final independent source approvals and sixteen focused passing groups for two parsers and four existing suites; full verification and package preparation passed. Fresh artifact consumption and final combined documentation approval passed against 7692d70 for six unchanged Scala files and fourteen Markdown files. Tables extend independent behavior without adding test groups; earlier parser acceptance does not accept this change.

[SkillMarkdownParserSpec](SkillMarkdownParserSpec.scala) covers five grouped bounded contracts:

- Exact body/declaration projection for plain, quoted, folded/literal, Unicode and flow-mapping strings, LF/CRLF/CR framing with one optional initial BOM and preserved interior BOMs, empty capabilities and checked catalogue reentry.
- Required-string validation; ordered inert nested metadata; recursive duplicate, invalid-key, unsupported scalar/tag/alias and malformed-YAML rejection without fallback.
- Positive UTF-16/depth limits, exact size/depth boundaries, delimiter near-misses, repeated/noninitial BOM and leading-whitespace rejection and blank bodies.

- [x] `registry_contract` and `current_docs` approved the final metadata scope. The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#verification-and-acceptance) records exact current identities and full `skill-metadata-verify.log` verification.

## Selected local loading

[LocalSkillSpec](LocalSkillSpec.scala) adds three grouped contracts: cold scoped loading of exact metadata/body and selected UTF-8 resources with stream release; trust, traversal/sensitive-path, identifier and file-shape rejection; and count, byte, decoding and parser bounds. Selected empty text and multibyte UTF-8 exercise exact limits, while corrupt unselected files remain unread. Metadata is preserved independently of selected resources and activation targets; nonpositive depth limits fail before I/O. No filesystem discovery or generated activation is inferred.

- [x] `registry_contract` and `current_docs` approved the [local source/test identities](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#selected-local-loading), including metadata preservation and pre-I/O depth validation. Full `skill-metadata-verify.log` verification passed with zero compiler warnings or failures. Platform symlink execution remains unverified; the source contract records the advisory filesystem guarantees.
- [x] Bounded repository discovery below is independently reviewed and verified; capability installation remains separate.

## Local repository discovery

[LocalSkillRepositorySpec](LocalSkillRepositorySpec.scala) retains three documents-only groups: cold immediate discovery with exact declared data and no resource loading; root/trust/path/file-shape and whole-batch validation; and all-entry, selected-skill and inherited local limits. The existing text-tree fixture includes an actual UTF-8 BOM document, exact preserved interior BOM, original byte/UTF-16 bounds and discovered ID. Directory scan ownership and filename processing are separate from the returned catalogue's ID ordering. The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#local-repository-discovery) defines deliberate upstream differences and unverified platform behavior.

Two additional TextTree groups verify exact nested and benign dot-file resources, root-document exclusion, retained nested SKILL.md, detached text after moving the repository and default-mode isolation from invalid binary data. A boundary table pairs accepted entry/depth/count/byte limits with rejections and pre-I/O configuration validation; a sensitive nested path rejects the whole snapshot. These are five repository groups, plus three unchanged LocalSkill groups in the focused run. No platform alias/symlink or hostile-filesystem cancellation guarantee is tested.

- [x] `registry_contract` and `current_docs` approved the current repository source/spec with no findings; full `skill-resource-tree-verify.log` verification passed with zero compiler warnings or failures. Focused eight repository/local-loader groups passed in 378 ms (`skill-resource-tree-focused.log`); the source contract records exact current identities.

## Explicit deferred reader verification

One added LocalSkillSpec group constructs a reader before its directory exists, binds a logical name distinct from the physical path, and observes fresh content on repeated reads without parsing SKILL.md. Exact multibyte bounds, changed oversized/invalid-UTF-8/deleted files and pre-I/O trust/path/limit rejection retain the existing local safety boundary. It does not test platform aliases/symlinks or hostile-filesystem cancellation deadlines. Current identities and acceptance are in the [reader contract](../../../../../../../main/scala/com/weisgnosis/agents/harness/skill/README.md#explicit-local-resource-reader).
