# Preloaded skill catalogue: verification

[SkillCatalogSpec](SkillCatalogSpec.scala) has three grouped contracts: checked catalogue construction and duplicate/path rejection; exact text/resource lookup with typed failures; current Meta-group binding and derived codec reentry through validation. The unchanged-source whitespace regression directly checks rejection of a whole-path blank, paired with an accepted nonblank spaced identifier; RED evidence is in the source contract. Fixtures are immutable text and identifiers; no test performs filesystem or network operations.

The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/core/skill/README.md) owns scope, limitations and final acceptance. These tests do not establish upstream filesystem loader or skill-reminder parity.

- [x] Both independent reviewers approved the final source/spec revision, and full verification passed in skill-runtime-verify.log; exact identities and current evidence are in the linked source contract.
