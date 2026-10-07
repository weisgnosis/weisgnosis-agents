# Distribution verification

The [artifact consumer](artifact-consumer/README.md) checks freshly generated library distributions from an external host namespace. It is a standalone unpublished fixture so project-class dependencies cannot substitute for packaged artifacts. CI runs it after normal repository verification and packaging; remote CI execution and publication remain separate gates.
