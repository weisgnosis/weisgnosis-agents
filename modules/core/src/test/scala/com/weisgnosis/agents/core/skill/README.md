# Preloaded skill catalogue: verification

[SkillCatalogSpec](SkillCatalogSpec.scala) retains three catalogue contracts: checked catalogue construction and duplicate/path rejection; exact text/resource lookup with typed failures; current Meta-group binding and derived codec reentry through validation. The unchanged-source whitespace regression directly checks rejection of a whole-path blank, paired with an accepted nonblank spaced identifier; RED evidence is in the source contract. Fixtures are immutable text and identifiers; no test performs filesystem or network operations.

The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/core/skill/README.md) owns scope, limitations and final acceptance. One additional table group verifies empty/shared-target reminders, deterministic ordering, JSON quoting and reconstruction without changing raw declarations or duplicating text. This is a bounded reminder adaptation; filesystem loading, Java subclass copying and SkillHook system-prompt injection remain separate.

- [x] Both independent reviewers approved the final source/spec revision, and full verification passed in skill-reminders-final-verify.log; exact identities and current evidence are in the linked source contract.
