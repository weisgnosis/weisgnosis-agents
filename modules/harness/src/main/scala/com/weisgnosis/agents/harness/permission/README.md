# Permission policy and confirmation

PermissionEngine combines independent gate decisions with Deny > Ask > Passthrough > Allow. Unknown tools default to Ask. Restricted rules inspect a selected argument path. PermissionRequests registers an interactive Promise before publishing its request and removes interrupted entries. PendingApproval and ConfirmResult provide serializable durable suspension data. resume applies modified input and rechecks deny policies without another model call. Hosts must persist checkpoints, validate request identity, and rerun tool safety checks. ReAct middleware and durable workflow storage are pending.

## Implementation

- [ConfirmResult](ConfirmResult.scala)
- [PermissionBehavior](PermissionBehavior.scala)
- [PermissionEngine](PermissionEngine.scala)
- [PermissionRequests](PermissionRequests.scala)
- [PermissionRule](PermissionRule.scala)

See the [repository architecture](../../../../../../../../../../ARCHITECTURE.md) for integration boundaries and invariant definitions.
