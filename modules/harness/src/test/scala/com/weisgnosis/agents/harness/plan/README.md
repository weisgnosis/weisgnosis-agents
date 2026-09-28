# com.weisgnosis.agents.harness.plan (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.harness.plan`.

---

## 🧪 Planned Test Specs

1. **`PlanModeInterceptorSpec.scala`** (corresponds to `PlanModeMiddlewareTest.java`):
   - Allowing read-only tool invocations while in plan mode.
   - Denying mutating tool invocations (`write_file`, shell modification).
   - Triggering `PermissionDecision.ask` upon `plan_exit`.

2. **`PlanModePropagationSpec.scala`** (corresponds to `PlanModeSubagentPropagationTest.java`):
   - Verifying that subagents spawned from a planning session automatically inherit planning constraints.
