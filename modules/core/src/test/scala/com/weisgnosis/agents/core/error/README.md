# Typed error verification

[AgentErrorSpec](AgentErrorSpec.scala) verifies TextTransformationError codec identity and diagnostics. The client transformation suite checks that provider ModelError cannot inhabit the transform callback error channel, and that explicitly mapped host failures do not trigger native-format fallback.

- [x] `review_fp` and `review_harness` approved the final seven-file text-transformation increment against `02b4654` on 2026-09-29 after the fallback-provenance correction. No findings remain within this scope. Full formatting, fatal-warning compilation, and verification passed 434 core and 40 harness tests.
- [ ] Complete exhaustive error-hierarchy and upstream scenario mapping; this suite does not claim it.

The [client transformation table](../../../../../../../main/scala/com/weisgnosis/agents/core/client/README.md) records all seven exact reviewed identities.
