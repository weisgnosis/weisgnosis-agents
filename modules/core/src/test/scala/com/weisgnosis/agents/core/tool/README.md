# com.weisgnosis.agents.core.tool (Tests)

ZIO Test parity suite for `com.weisgnosis.agents.core.tool`.

---

## 🧪 Planned Test Specs

1. **`ToolkitSpec.scala`** (corresponds to `ToolkitTest.java`):
   - Registering tools and verifying lookup by name.
   - Detecting name collisions and rejecting duplicates.
   - Merging two `Toolkit` instances (`toolkitA ++ toolkitB`).

2. **`ToolExecutionSpec.scala`** (corresponds to `ToolExecutionTest.java`):
   - Executing tools with valid and invalid JSON input.
   - Returning typed `ToolResultState.Error` on parse errors.
   - Preserving error messages formatted for model consumption.

3. **`PathSafetySpec.scala`** (corresponds to `DangerousPathBypassTest.java`):
   - Sensitive path rejection (`.env`, `.ssh/id_rsa`, `.git/config`).
   - Symlink traversal and `..` escape prevention using path canonicalization.
