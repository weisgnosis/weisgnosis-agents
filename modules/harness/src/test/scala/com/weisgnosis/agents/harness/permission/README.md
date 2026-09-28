# com.weisgnosis.agents.harness.permission (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.harness.permission`.

---

## 🧪 Planned Test Specs

1. **`PermissionGateSpec.scala`** (corresponds to `PermissionEngineTest.java`):
   - Rule resolution hierarchy: `Deny` overrides `Ask`, `Ask` overrides `Allow`.
   - Tool name wildcard matching and argument pattern regex filters.
   - Default behavior configuration for unspecified tools.

2. **`PermissionPromiseSpec.scala`** (corresponds to `InteractivePermissionTest.java`):
   - Fiber suspension during approval request.
   - Resuming upon `resolve(requestId, Approved)`.
   - Halting and emitting `ToolResultState.Denied` upon `resolve(requestId, Rejected)`.
