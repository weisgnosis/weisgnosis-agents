# Example source package

HostAssembly accepts an explicit Provided transcript or resolves one Registered catalog descriptor before MCP acquisition; no registration/history preflight or execution authority is inferred. See the [registered binding contract](../../../../../../../README.md#registered-transcript-assembly) for catalog selection and ownership. HostAssembly composes the existing builder with host-supplied dependencies and a scoped MCP manager. Both toolkit and group snapshots come from one installation. Local/remote name collisions fail assembly, and failed or interrupted assembly joins already acquired cleanup before returning.

Returned handles must remain inside their owner Scope. MCP acquisition must own its transport resources in the supplied scope. Hosts supply a read_file capability matching result-store paths, explicit authorization, transcript replay policy, and summarization. Summarizers and transcript storage must not reenter the same agent while its run lock is held. Completion-observer failure may follow successful tool side effects; callers must not automatically replay a run.

LiveProviderSmoke explicitly reads its opt-in toggle before acquiring the HTTP client. Endpoint and model are host-selected and a dedicated optional credential environment variable is used. Its harmless addition tool and model/result checks exercise provider streaming with a bounded run; no raw requests, credentials, or response bodies are logged on success. See the [module README](../../../../../../../README.md) for configuration and cost implications. Live execution is separate from ordinary verification.

LifecycleLogging observes a narrow runtime-event allowlist with static messages and local correlation annotations. Its wrapper consumes EOF, preserves terminal results/failures and joins cancellation cleanup. The host owns logger configuration and independent log privacy; ZIO logging spans do not implement OpenTelemetry. See the [logging contract](../../../../../../../README.md#lifecycle-logging).

These are unpublished integration examples, not additional supported library APIs. The [module guide](../../../../../../../README.md#verification-and-acceptance) distinguishes offline checks from selected actual provider runs.

[AuditedLlmClient](../../../../../../../README.md#audited-public-model-call) demonstrates one suspended public client delegation with request cleanup before context/timing/outcome observation. Its observer must terminate and private capability-based failover is not forwarded; the linked contract describes failure and ownership boundaries.

LiveFileTask supplies bounded read/write tools for one disposable host-owned file. It checks exact physical bytes and model-visible observations before accepting Completed, and joins cleanup. It is not an unrestricted filesystem toolkit.

LiveProviderProfiles runs explicitly selected native JSON, synthetic terminal JSON or local-tool cancellation checks. Configuration and actual selected-provider results, including the failed reasoning-model Native run, are in the [profile guide](../../../../../../../README.md#selected-structured-output-and-cancellation-profiles). A final success marker is required; process exit alone does not prove the profile passed.
