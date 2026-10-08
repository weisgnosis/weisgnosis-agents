# Core test resources

[java-event-wire-fixtures.json](java-event-wire-fixtures.json) contains actual Jackson serialization output from the pinned AgentScope Java revision named in its `pin` field. Each fixture retains its input, output, category and descriptive name. The [wire suites](../scala/com/weisgnosis/agents/core/event/javawire/README.md) describe generation and coverage. No fixture generation occurs during Scala tests.
