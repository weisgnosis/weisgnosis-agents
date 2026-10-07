# Inline skills and local repositories

[SkillMarkdownParser.parse](SkillMarkdownParser.scala) reads a bounded inline SKILL.md profile into core SkillDefinition. The required name and description fields must be nonblank YAML strings. Name becomes the exact ID; string values follow YAML semantics and the body is preserved exactly. Additional fields become ordered JSON metadata, excluding name/description. Resources and activation targets are empty: parsing acquires no capability and does not create group names or install a skill. Hosts can pass the result through checked SkillCatalog.make and explicitly configure any resources/targets.

The header begins at column zero with a delimiter line containing exactly three hyphens plus optional spaces/tabs. LF, CRLF and CR are supported. One optional U+FEFF is accepted only at the start immediately before the opening delimiter; repeated BOMs and preceding whitespace are rejected. Interior metadata/body BOMs remain unchanged. The first matching closing delimiter ends the header; the remaining body is preserved without trimming and must be nonblank. Quoted, plain textual, literal and folded string values follow YAML string semantics. Extra metadata supports block/flow mappings and sequences, strings, JSON-compatible finite numbers, booleans and null. Mapping keys must be strings; encounter order is retained at every object level.

Required name/description reject numeric, boolean, null and collection values; quoted string forms remain valid. Duplicate keys at every depth, collection/nonstring keys, aliases/anchors, explicit tags and YAML version/tag directives are rejected. YAML-only numeric spellings and nonfinite values are rejected rather than coerced. Metadata remains inert even for keys named allowed-tools, resources or activateGroups. Malformed YAML fails as AgentError.InvalidConfiguration, without permissive fallback or object loading. Other parser exceptions become bounded syntax diagnostics containing the exception class, not input content. Parsing uses the existing SnakeYAML Engine dependency; allocation, iteration, resolution and foreign event access are suspended in ZIO, with immutable parser state.

SkillMarkdownLimits defaults to 128,000 document characters and 16,000 header characters, measured as UTF-16 units. maxMetadataDepth defaults to 10 and counts nested metadata collections, excluding the outer header mapping; scalar values add no collection depth. All three limits must be positive. The document includes delimiters/body; the header excludes delimiter lines and includes the newline before the closing delimiter. Validation precedes YAML reader creation. These bounds intentionally differ from upstream's 16,384-code-point header limit. The limits have a derived codec; parse always validates decoded values.

Migration: SkillMarkdownLimits appends the defaulted third field maxMetadataDepth; old JSON without it uses 10. SkillDefinition appends default-empty metadata as its sixth field. Positional patterns/Product arity and encoded data change; rebuild dependent binaries. The shared frontmatter compatibility scope below also updates subagent declaration framing without changing its model. Markdown generation, source-derived IDs and skill capability registration remain unfinished; selected local loading and bounded repository discovery are defined below.

## UTF-8 BOM frontmatter compatibility

[Upstream change 2c503d88](https://github.com/agentscope-ai/agentscope-java/commit/2c503d88ba844fe5ee149e8e5f0c1ed32b036420) adds one initial U+FEFF to shared skill frontmatter, preserves interior BOMs and tests LF/CRLF/CR, actual file discovery/loading and subagent declarations. Current [SkillMarkdownParser](SkillMarkdownParser.scala) and [SubagentDeclarationParser](../subagent/SubagentDeclarationParser.scala) implement that framing at their existing checked boundaries. Both parsers adjust only delimiter recognition, preserving original offsets and exact body whitespace. Existing document UTF-16 limits include the initial BOM; header limits exclude delimiter lines; local byte bounds include the original three UTF-8 BOM bytes. No global stripping, newline normalization or new YAML leniency is introduced.

The existing four suites now carry the contract tables and actual UTF-8 files, with no new test groups. They retain strict repeated/noninitial BOM and delimiter near-misses, original size bounds, malformed UTF-8 and capability isolation. Upstream generic Markdown parsing returns unchanged raw input when frontmatter is absent; these domain parsers still reject missing required declarations as typed InvalidConfiguration. The subagent body remains exact rather than adopting Java's trim. This scope does not implement the upstream SkillManageTool create/edit APIs.

- [x] `frontmatter-bom-red.log` reproduces typed missing-opening-delimiter failures against unchanged `7692d70` in both parsers and actual repositories; eight groups passed and eight failed.
- [x] `frontmatter-bom-assertion-red.log` records three passing and two failing groups in 295 ms, including an explicit unchanged-production assertion: valid BOM table input returns Left(InvalidConfiguration) instead of the expected Right(SkillDefinition), preserving exact expected body and metadata.
- [x] Implement one initial BOM plus LF/CRLF/CR framing without changing public signatures, YAML interpretation or resource ownership.
- [x] `frontmatter-bom-focused-final.log` passes all sixteen existing groups in 382 ms with zero failures, ignored tests or compiler warnings.
- [x] `review_fp` and `review_invariants` independently approved all six exact source/test identities below against `7692d70`, with no unresolved findings.
- [x] `frontmatter-bom-full-verify.log` passes formatting, fatal-warning compilation, all 1,564 existing tests (687 core / 734 harness / 113 MCP / 22 fresh PostgreSQL / 8 examples) and four RC1 packages, with zero failures, ignored tests or compiler warnings.
- [x] `frontmatter-bom-preparation.log` passes archive preparation and negative selector/source-archive regressions.
- [x] `frontmatter-bom-consumer.log` passes standalone formatting, clean fatal-warning compilation and forked consumption of four fresh module hashes. Its existing packaged API smoke does not claim direct BOM parsing coverage.
- [x] `review_fp` and `review_invariants` approved the final combined twenty-file scope against `7692d70`, including six unchanged source/spec identities and fourteen Markdown files; no unresolved findings remain in this scope.

Current independently approved source/test manifest:

| File | Git blob |
| --- | --- |
| [SkillMarkdownParser.scala](SkillMarkdownParser.scala) | `6c0616eb9cee601f23e80ae46533fd73de369766` |
| [SubagentDeclarationParser.scala](../subagent/SubagentDeclarationParser.scala) | `8e9b4e87312a75fb4f6d3b865a6fd965f8fb630f` |
| [LocalSkillRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillRepositorySpec.scala) | `08be327e19529fdbd3369c6c88790877e1c7e724` |
| [SkillMarkdownParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/SkillMarkdownParserSpec.scala) | `f932cb0dab39ff35e4882f44ac68dd8322ab0622` |
| [LocalSubagentDeclarationsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/LocalSubagentDeclarationsSpec.scala) | `72ad8fe7b48c286dbf3d5ff0fbb8de346cdb3efe` |
| [SubagentDeclarationParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationParserSpec.scala) | `f34160b0dd8cb09a2ec9127a6c8f7dc7145a3250` |

## Verification and acceptance

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for ordered inert metadata, bounded parsing and preservation, with no findings.
- [x] Full `scalafmtAll scalafmtSbt verify` passed in `skill-metadata-verify.log`: 600 core / 687 harness / 79 MCP tests (1,366 total), compile task 1 second and test task 14 seconds, with zero compiler warnings or failures. Five parser groups include two new metadata groups; the [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) owns current repository totals.
- [x] Bounded repository discovery is independently reviewed and verified below; capability registration remains separate.

| Reviewed file | Reviewed Git blob |
| --- | --- |
| [SkillMarkdownParser.scala](SkillMarkdownParser.scala) | [Current BOM scope and acceptance gates](#utf-8-bom-frontmatter-compatibility) |
| [SkillMarkdownParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/SkillMarkdownParserSpec.scala) | [Current BOM scope and acceptance gates](#utf-8-bom-frontmatter-compatibility) |

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) record the bounded mappings and exclusions. No exhaustive upstream skill-parser or loader parity is claimed.

## Selected local loading

[LocalSkill.load](LocalSkill.scala) reads exact `SKILL.md` from one host-selected directory and only the explicitly selected resource identifiers, in sorted order. It uses the strict inline profile above, preserves the declared ID, inert metadata and exact UTF-8 text, and returns no activation targets. The directory name does not rename the skill; unselected files are neither discovered nor read. Missing, malformed, unreadable or oversized selected content fails the whole load.

WorkspaceTrust.Untrusted fails before filesystem access. HostControlled requires trusted ancestors. Raw parent traversal is rejected before normalization; PathSafety screens lexical and canonical paths. NOFOLLOW attributes reject symlinks and non-directory ancestors/nonregular leaves at the selected root and each relative component, with canonical containment inside that root. These checks are advisory under host-controlled filesystem mutation, not an atomic sandbox. Resource identifiers must have nonblank slash-separated segments: no leading slash, empty/dot/parent segment, backslash, colon or control character. Exact `SKILL.md` is reserved; nonblank names with spaces remain valid.

LocalSkillLimits defaults to 64 resources, 512,000 document bytes, 256,000 bytes per resource and default SkillMarkdownLimits. All limits are positive and byte limits stay below Int.MaxValue; resource count, identifier and all parser-limit validation, including metadata depth, precede I/O. Each scoped stream reads at most its byte limit plus one, then strict UTF-8 decoding rejects malformed input. Empty resource text is valid. Blocking operations and error translation are suspended under a mask; stream close failures remain defects. Cleanup joins before return, without a cancellation deadline or protection against hostile/blocking filesystems.

LocalSkill keeps its call signature; its nested parser limits and returned SkillDefinition follow the migrations above. Hosts explicitly choose trust, directory and resources, then may validate/install the returned definition through SkillCatalog; no filesystem authority enters the core catalogue. Limits use a derived codec and are validated on every load. Binary resources, recursive discovery, caches, writes and automatic group generation are outside this boundary.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for local metadata preservation and pre-I/O depth validation, with no findings. The three local-loader groups and full verification passed in `skill-metadata-verify.log`, as recorded above.

| Reviewed file | Reviewed Git blob |
| --- | --- |
| [LocalSkill.scala](LocalSkill.scala), reviewed eager baseline | `d260589839585de3588c3a14ccb2a69f31200330` |
| [LocalSkillSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillSpec.scala) | `cd0a1531f8de85150a98b70c234b13865b3a1404` |

Tests cover real selected reads and resource release. Symlink rejection is source-reviewed; platform symlink/junction integration coverage remains unverified.

## Explicit local resource reader

LocalSkill.resourceReader binds one host-selected directory, logical resource and limits without I/O. Each read revalidates explicit WorkspaceTrust, portable path, lexical/canonical sensitive checks, file shape and strict UTF-8/byte bounds through the existing scoped reader. The host binds that reader to an exact SkillResourceKey in core SkillLibrary; model input cannot choose a physical path. Read results are fresh, uncached text. Existing advisory race/platform and joined-blocking cancellation limits remain unchanged.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the current LocalSkill source/spec below, with no findings; full verification passed. Existing eager behavior and repository discovery are unchanged. Combined ten-file `skill-library-final-verify.log` evidence is in the [core capability acceptance](../../../../../../../../../core/src/main/scala/com/weisgnosis/agents/core/skill/README.md#deferred-resource-capabilities).

| Current local reader file | Git blob |
| --- | --- |
| [LocalSkill.scala](LocalSkill.scala) | `acf28d1b119bc47fa04372df48f7804431803057` |
| [LocalSkillSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillSpec.scala) | `8c7f6e551f67393de01d873a06f950b099fc9282` |

## Local repository discovery

[LocalSkillRepository.load](LocalSkillRepository.scala) returns a checked SkillCatalog from immediate child directories of one explicit HostControlled root. The effect is cold, read-only and creates no directories. Untrusted roots and invalid repository or nested LocalSkillLimits fail before I/O. LocalSkillRepositoryLimits defaults to maxEntries = 4096, maxSkills = 256 and default LocalSkillLimits; both repository limits must be positive. Every immediate entry counts before filtering, including ordinary files and directories without a skill document.

The scoped directory scan closes before candidate inspection and document loading. Entries are processed in filename order; the returned catalogue exposes definitions in declared-ID order. Ordinary non-directory entries are ignored, but every symbolic-link entry is rejected. Candidate directories undergo lexical and canonical sensitive-path checks before probing exact SKILL.md; their canonical path must equal the enumerated entry and have the repository root as its direct parent. Only NoSuchFileException from the document attribute probe means absence and skips that directory. Other probe failures and nonregular or symlink documents fail the whole load. A missing root also fails.

Selected documents use LocalSkill's byte/parser limits, strict UTF-8 and joined scoped reads. Declared IDs, bodies and inert metadata survive unchanged; duplicate IDs or any malformed selected document fail the entire result. The default DocumentsOnly mode leaves resources and activation targets empty. Skill-directory discovery remains nonrecursive. There is no runtime filesystem fallback, origin/source ID generation, cache, save/delete operation or automatic installation; request-only catalogue projection is a separate core runtime contract. Host-controlled ancestor and filesystem-race assumptions remain unchanged: these are advisory checks, not an atomic sandbox or a cancellation deadline. Platform symlink/junction integration is still unverified.

The appended resources parameter accepts DocumentsOnly (default) or TextTree(LocalSkillResourceLimits(maxEntries = 4096, maxDepth = 8)). TextTree eagerly discovers UTF-8 resources under each selected skill. Positive limits are checked before I/O; the aggregate per-skill entry budget counts every entry, including the root SKILL.md and directories. The skill root has depth zero; deeper directories fail before opening when the depth limit is exceeded. Each sorted directory scan closes before child processing. Existing LocalSkillLimits retain resource-count and per-file-byte authority.

Only the root document, compared by filesystem-provider Path equality, is excluded from resources; nested SKILL.md is ordinary resource content. Benign dot files/directories are not filtered. Sensitive paths, symbolic links, nonregular entries and canonical identity/containment changes reject the whole load. Files are then read in sorted logical-name order through LocalSkill's checked, scoped, strict UTF-8 boundary. Invalid UTF-8 fails rather than becoming base64; no directory/read error produces a partial snapshot. Returned text is detached from the filesystem, and loading does not create activation targets or runtime disk capabilities. Alias and symlink/junction behavior remains source-inspected, without platform integration coverage.

Migration: load appends a defaulted resources argument, preserving ordinary documents-only source calls; method-value adaptation and dependent binaries may require rebuilding. No binary compatibility is promised. LocalSkill.load and its limits remain unchanged.

- [x] `registry_contract` approved FP/ZIO correctness and `current_docs` approved invariants for the current TextTree source/spec below, with no findings; full verification passed. Focused `skill-resource-tree-focused.log` passed eight repository/local-loader groups in 378 ms. DocumentsOnly behavior and the unchanged LocalSkill helper retain their prior acceptance.
Full `scalafmtAll scalafmtSbt verify` passed in `skill-resource-tree-verify.log`: 602 core / 692 harness / 82 MCP tests (1,376 total), compile task 1 second and test task 12 seconds, with zero compiler warnings or failures. The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) records current repository acceptance.

| Reviewed file | Git blob |
| --- | --- |
| [LocalSkillRepository.scala](LocalSkillRepository.scala) | `eff186771a953bc0b3c6ea0bf71b603927499f37` |
| [LocalSkillRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillRepositorySpec.scala) | [Current BOM scope and acceptance gates](#utf-8-bom-frontmatter-compatibility) |
| [LocalSkill.scala](LocalSkill.scala), package-visible helper reuse | `d260589839585de3588c3a14ccb2a69f31200330` |

The repository table retains its reviewed helper baseline; current LocalSkill source/spec identities are in the explicit reader section. Existing load behavior and signature are unchanged.

- [ ] Repository-wide next work is ordered in the [tracker](../../../../../../../../../../docs/architecture/tracker.md). Other repository variants, source IDs, automatic lazy-resource discovery and caches remain unfinished.
