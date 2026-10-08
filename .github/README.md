# Repository collaboration configuration

The [pull request template](PULL_REQUEST_TEMPLATE.md) asks for the delivered behavior, proportionate validation and actual independent review of behavioral changes. [CONTRIBUTING.md](../CONTRIBUTING.md) defines the workflow.

[CI](workflows/README.md) performs repository verification with a PostgreSQL fixture, library artifact generation, archive/POM inspection and external consumer checks. Local packaging is not evidence of a successful remote CI run or public publication. See the [build contract](../project/README.md#local-artifact-packaging) and [completion checklist](../docs/architecture/tracker.md).
