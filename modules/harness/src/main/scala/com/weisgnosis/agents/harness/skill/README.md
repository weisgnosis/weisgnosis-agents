# Inline skills and local repositories

[SkillMarkdownParser.parse](SkillMarkdownParser.scala) reads a bounded inline SKILL.md profile into core SkillDefinition. The required name and description fields must be nonblank YAML strings. Name becomes the exact ID; string values follow YAML semantics and the body is preserved exactly. Additional fields become ordered JSON metadata, excluding name/description. Resources and activation targets are empty: parsing acquires no capability and does not create group names or install a skill. Hosts can pass the result through checked SkillCatalog.make and explicitly configure any resources/targets.

The header begins at column zero with a delimiter line containing exactly three hyphens plus optional spaces/tabs. LF, CRLF and CR are supported. One optional U+FEFF is accepted only at the start immediately before the opening delimiter; repeated BOMs and preceding whitespace are rejected. Interior metadata/body BOMs remain unchanged. The first matching closing delimiter ends the header; the remaining body is preserved without trimming and must be nonblank. Quoted, plain textual, literal and folded string values follow YAML string semantics. Extra metadata supports block/flow mappings and sequences, strings, JSON-compatible finite numbers, booleans and null. Mapping keys must be strings; encounter order is retained at every object level.

Required name/description reject numeric, boolean, null and collection values; quoted string forms remain valid. Duplicate keys at every depth, collection/nonstring keys, aliases/anchors, explicit tags and YAML version/tag directives are rejected. YAML-only numeric spellings and nonfinite values are rejected rather than coerced. Metadata remains inert even for keys named allowed-tools, resources or activateGroups. Malformed YAML fails as AgentError.InvalidConfiguration, without permissive fallback or object loading. Other parser exceptions become bounded syntax diagnostics containing the exception class, not input content. Parsing uses the existing SnakeYAML Engine dependency; allocation, iteration, resolution and foreign event access are suspended in ZIO, with immutable parser state.

SkillMarkdownLimits defaults to 128,000 document characters and 16,000 header characters, measured as UTF-16 units. maxMetadataDepth defaults to 10 and counts nested metadata collections, excluding the outer header mapping; scalar values add no collection depth. All three limits must be positive. The document includes delimiters/body; the header excludes delimiter lines and includes the newline before the closing delimiter. Validation precedes YAML reader creation. These bounds intentionally differ from upstream's 16,384-code-point header limit. The limits have a derived codec; parse always validates decoded values.

Migration: SkillMarkdownLimits appends the defaulted third field maxMetadataDepth; old JSON without it uses 10. SkillDefinition appends default-empty metadata as its sixth field. Positional patterns/Product arity and encoded data change; rebuild dependent binaries. The shared frontmatter compatibility scope below also updates subagent declaration framing without changing its model. Markdown generation, source-derived IDs and skill capability registration remain unfinished; selected local loading and bounded repository discovery are defined below.

## UTF-8 BOM frontmatter compatibility

[Upstream change 2c503d88](https://github.com/agentscope-ai/agentscope-java/commit/2c503d88ba844fe5ee149e8e5f0c1ed32b036420) adds one initial U+FEFF to shared skill frontmatter, preserves interior BOMs and tests LF/CRLF/CR, actual file discovery/loading and subagent declarations. Current [SkillMarkdownParser](SkillMarkdownParser.scala) and [SubagentDeclarationParser](../subagent/SubagentDeclarationParser.scala) implement that framing at their existing checked boundaries. Both parsers adjust only delimiter recognition, preserving original offsets and exact body whitespace. Existing document UTF-16 limits include the initial BOM; header limits exclude delimiter lines; local byte bounds include the original three UTF-8 BOM bytes. No global stripping, newline normalization or new YAML leniency is introduced.

Paired suites cover the contract tables and actual UTF-8 files. They retain strict repeated/noninitial BOM and delimiter near-misses, original size bounds, malformed UTF-8 and capability isolation. Upstream generic Markdown parsing returns unchanged raw input when frontmatter is absent; these domain parsers still reject missing required declarations as typed InvalidConfiguration. The subagent body remains exact rather than adopting Java's trim. This scope does not implement the upstream SkillManageTool create/edit APIs.

- [x] Implement one initial BOM plus LF/CRLF/CR framing without changing public signatures, YAML interpretation or resource ownership.

Source and paired tests: [SkillMarkdownParser.scala](SkillMarkdownParser.scala), [SubagentDeclarationParser.scala](../subagent/SubagentDeclarationParser.scala), [LocalSkillRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillRepositorySpec.scala), [SkillMarkdownParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/SkillMarkdownParserSpec.scala), [LocalSubagentDeclarationsSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/LocalSubagentDeclarationsSpec.scala), [SubagentDeclarationParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/subagent/SubagentDeclarationParserSpec.scala).

## Verification and acceptance

- [x] Bounded repository discovery is independently reviewed and verified below; capability registration remains separate.

Source and paired tests: [SkillMarkdownParser.scala](SkillMarkdownParser.scala), [SkillMarkdownParserSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/SkillMarkdownParserSpec.scala).

The [test index](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/README.md) and [parity audit](../../../../../../../../../../docs/architecture/test-parity.md) record the bounded mappings and exclusions. No exhaustive upstream skill-parser or loader parity is claimed.

## Selected local loading

[LocalSkill.load](LocalSkill.scala) reads exact `SKILL.md` from one host-selected directory and only the explicitly selected resource identifiers, in sorted order. It uses the strict inline profile above, preserves the declared ID, inert metadata and exact UTF-8 text, and returns no activation targets. The directory name does not rename the skill; unselected files are neither discovered nor read. Missing, malformed, unreadable or oversized selected content fails the whole load.

WorkspaceTrust.Untrusted fails before filesystem access. HostControlled requires trusted ancestors. Raw parent traversal is rejected before normalization; PathSafety screens lexical and canonical paths. NOFOLLOW attributes reject symlinks and non-directory ancestors/nonregular leaves at the selected root and each relative component, with canonical containment inside that root. These checks are advisory under host-controlled filesystem mutation, not an atomic sandbox. Resource identifiers must have nonblank slash-separated segments: no leading slash, empty/dot/parent segment, backslash, colon or control character. Exact `SKILL.md` is reserved; nonblank names with spaces remain valid.

LocalSkillLimits defaults to 64 resources, 512,000 document bytes, 256,000 bytes per resource and default SkillMarkdownLimits. All limits are positive and byte limits stay below Int.MaxValue; resource count, identifier and all parser-limit validation, including metadata depth, precede I/O. Each scoped stream reads at most its byte limit plus one, then strict UTF-8 decoding rejects malformed input. Empty resource text is valid. Blocking operations and error translation are suspended under a mask; stream close failures remain defects. Cleanup joins before return, without a cancellation deadline or protection against hostile/blocking filesystems.

LocalSkill keeps its call signature; its nested parser limits and returned SkillDefinition follow the migrations above. Hosts explicitly choose trust, directory and resources, then may validate/install the returned definition through SkillCatalog; no filesystem authority enters the core catalogue. Limits use a derived codec and are validated on every load. Binary resources, recursive discovery, caches, writes and automatic group generation are outside this boundary.

Source and paired tests: [LocalSkill.scala](LocalSkill.scala), [LocalSkillSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillSpec.scala).

Tests cover real selected reads and resource release. Symlink rejection is source-reviewed; platform symlink/junction integration coverage remains unverified.

## Explicit local resource reader

LocalSkill.resourceReader binds one host-selected directory, logical resource and limits without I/O. Each read revalidates explicit WorkspaceTrust, portable path, lexical/canonical sensitive checks, file shape and strict UTF-8/byte bounds through the existing scoped reader. The host binds that reader to an exact SkillResourceKey in core SkillLibrary; model input cannot choose a physical path. Read results are fresh, uncached text. Existing advisory race/platform and joined-blocking cancellation limits remain unchanged.

Source and paired tests: [LocalSkill.scala](LocalSkill.scala), [LocalSkillSpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillSpec.scala).

## Local repository discovery

[LocalSkillRepository.load](LocalSkillRepository.scala) returns a checked SkillCatalog from immediate child directories of one explicit HostControlled root. The effect is cold, read-only and creates no directories. Untrusted roots and invalid repository or nested LocalSkillLimits fail before I/O. LocalSkillRepositoryLimits defaults to maxEntries = 4096, maxSkills = 256 and default LocalSkillLimits; both repository limits must be positive. Every immediate entry counts before filtering, including ordinary files and directories without a skill document.

The scoped directory scan closes before candidate inspection and document loading. Entries are processed in filename order; the returned catalogue exposes definitions in declared-ID order. Ordinary non-directory entries are ignored, but every symbolic-link entry is rejected. Candidate directories undergo lexical and canonical sensitive-path checks before probing exact SKILL.md; their canonical path must equal the enumerated entry and have the repository root as its direct parent. Only NoSuchFileException from the document attribute probe means absence and skips that directory. Other probe failures and nonregular or symlink documents fail the whole load. A missing root also fails.

Selected documents use LocalSkill's byte/parser limits, strict UTF-8 and joined scoped reads. Declared IDs, bodies and inert metadata survive unchanged; duplicate IDs or any malformed selected document fail the entire result. The default DocumentsOnly mode leaves resources and activation targets empty. Skill-directory discovery remains nonrecursive. There is no runtime filesystem fallback, origin/source ID generation, cache, save/delete operation or automatic installation; request-only catalogue projection is a separate core runtime contract. Host-controlled ancestor and filesystem-race assumptions remain unchanged: these are advisory checks, not an atomic sandbox or a cancellation deadline. Platform symlink/junction integration is still unverified.

The appended resources parameter accepts DocumentsOnly (default) or TextTree(LocalSkillResourceLimits(maxEntries = 4096, maxDepth = 8)). TextTree eagerly discovers UTF-8 resources under each selected skill. Positive limits are checked before I/O; the aggregate per-skill entry budget counts every entry, including the root SKILL.md and directories. The skill root has depth zero; deeper directories fail before opening when the depth limit is exceeded. Each sorted directory scan closes before child processing. Existing LocalSkillLimits retain resource-count and per-file-byte authority.

Only the root document, compared by filesystem-provider Path equality, is excluded from resources; nested SKILL.md is ordinary resource content. Benign dot files/directories are not filtered. Sensitive paths, symbolic links, nonregular entries and canonical identity/containment changes reject the whole load. Files are then read in sorted logical-name order through LocalSkill's checked, scoped, strict UTF-8 boundary. Invalid UTF-8 fails rather than becoming base64; no directory/read error produces a partial snapshot. Returned text is detached from the filesystem, and loading does not create activation targets or runtime disk capabilities. Alias and symlink/junction behavior remains source-inspected, without platform integration coverage.

Migration: load appends a defaulted resources argument, preserving ordinary documents-only source calls; method-value adaptation and dependent binaries may require rebuilding. No binary compatibility is promised. LocalSkill.load and its limits remain unchanged.

The [tracker](../../../../../../../../../../docs/architecture/tracker.md#current-acceptance) records current repository acceptance.

Source and paired tests: [LocalSkillRepository.scala](LocalSkillRepository.scala), [LocalSkillRepositorySpec.scala](../../../../../../../test/scala/com/weisgnosis/agents/harness/skill/LocalSkillRepositorySpec.scala), [LocalSkill.scala](LocalSkill.scala), package-visible helper reuse.

Existing load behavior and signature are unchanged.

- [ ] Repository-wide next work is ordered in the [tracker](../../../../../../../../../../docs/architecture/tracker.md). Other repository variants, source IDs, automatic lazy-resource discovery and caches remain unfinished.
