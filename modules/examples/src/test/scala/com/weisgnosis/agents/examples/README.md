# Example integration checks

HostAssemblySpec contains three independent integration groups, including registered aggregate/segmented selection in an actual ReAct table with Ref-backed conditional storage (not PostgreSQL evidence). Existing integration groups cover selected MCP execution with authorization/history preparation/eviction/transcript composition and actual model-requested read_file retrieval of committed offload content, and failed or cancelled catalog/MCP assembly with joined cleanup while the caller scope remains open. See [scoped host usage](../../../../../../../README.md#scoped-host-usage) and [registered transcript assembly](../../../../../../../README.md#registered-transcript-assembly) for current composition and ownership. The MCP peer is a deterministic in-process protocol fixture; it does not contact a provider or execute processes.

LiveProviderSmokeSpec verifies the disabled runnable path and explicit configuration using TestSystem; it never enables external requests.

LifecycleLoggingSpec verifies payload exclusion and local correlation on example-origin records, actual terminal results, preserved failures and cancellation waiting for model cleanup. Independent ZIO failed-fiber logs are outside this adapter's privacy boundary.

The fixture approval is explicit test data, not a suggested host authorization policy. These examples do not establish upstream parity. See [verification boundaries](../../../../../../../README.md#verification-and-acceptance); ordinary tests use controlled clients rather than public providers.

AuditedLlmClientSpec has two grouped public-wrapper contracts: exact forwarding/laziness/output/error/observer-defect handling and joined cancellation cleanup before one timed observation. It does not validate provider interoperability, durable audit delivery or private failover capability preservation; see the [wrapper contract](../../../../../../../README.md#audited-public-model-call).

LiveFileTaskSpec exercises actual disposable-file read/write effects through a scripted model and verifies interrupted cleanup. LiveProviderProfilesSpec exercises real ReAct structured/cancellation paths with controlled clients and rejects missing tool calls, wrong values, extra native fields and native-format failures. Independent profile selection is checked without promoting a rejected Native profile to fallback success. Actual provider runs are opt-in and documented separately.
