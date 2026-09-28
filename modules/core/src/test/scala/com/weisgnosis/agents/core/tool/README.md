# com.weisgnosis.agents.core.tool (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.tool`.

---

## 🧪 Implemented Test Specs (Passing)

1. **`ToolkitSpec.scala`** `[x]` (corresponds to `ToolkitTest.java`):
   - Registering tools and verifying lookup by name.
   - Detecting name collisions and rejecting duplicates.
   - Merging two `Toolkit` instances (`toolkitA ++ toolkitB`).

2. **`ToolExecutionSpec.scala`** `[x]` (corresponds to `ToolExecutionTest.java`):
   - Executing tools with valid and invalid JSON input.
   - Returning typed `ToolResultState.Error` on parse errors.
   - Preserving error messages formatted for model consumption.
   - Responsiveness to fiber cancellation.

3. **`PathSafetySpec.scala`** `[x]` (corresponds to `DangerousPathBypassTest.java` and `ToolBaseTest.java`):
   - Sensitive path rejection (`.env`, `.ssh/id_rsa`, `.git/config`, `.bashrc`).
   - Tilde expansion and case-insensitivity.
   - Dangerous destructive command detection.
