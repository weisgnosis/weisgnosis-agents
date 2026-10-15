# Preloaded skill catalogue: verification

[SkillCatalogSpec](SkillCatalogSpec.scala) covers catalogue contracts: checked catalogue construction and duplicate/path rejection; exact text/resource lookup with typed failures; current Meta-group binding and derived codec reentry through validation. The whitespace regression checks rejection of a whole-path blank, paired with an accepted nonblank spaced identifier. Fixtures are immutable text and identifiers; no test performs filesystem or network operations.

Selection tests verify exact retained definitions, unchanged parent contents, empty selection, blank/unknown rejection and rejection of attempts to recover a removed ID.

The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/core/skill/README.md) describes scope and limitations. Reminder tests verify empty/shared-target reminders, deterministic ordering, JSON quoting and reconstruction without changing raw declarations or duplicating text. This is a bounded reminder adaptation; filesystem loading, Java subclass copying remain separate.

## Deferred resource verification

[SkillLibrarySpec](SkillLibrarySpec.scala) covers checked cold exact-key capability binding, preloaded/deferred separation and monotone selection; and scoped resolution with cleanup-before-return, exact mixed failure/defect and held joined cancellation. Repeated reads return fresh values and prompt projection exposes only selected logical names.
