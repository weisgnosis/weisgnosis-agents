# Documentation

Start with [Getting started](getting-started.md): local installation and complete scripted, HTTP/tool and MCP applications. Public artifacts and frozen binary compatibility are not yet available.

## Guides and contracts

- [Host examples](../modules/examples/README.md): composition, lifecycle logging and opt-in live verification.
- [Compatibility and migration](../project/README.md#compatibility-policy): public API rebuild requirements.
- [Architecture overview](../ARCHITECTURE.md): runtime authority and host boundaries.
- [Architecture index](architecture/README.md): component contracts and design documents.
- [Completion checklist](architecture/tracker.md): the single current list of remaining library outcomes.
- [Upstream parity reference](architecture/test-parity.md): mapped behavior, intentional adaptations and unresolved evidence.
- [Module index](../modules/README.md): modules and package navigation.
- [Contribution workflow](../CONTRIBUTING.md): implementation, proportionate verification and independent review.

## Research references

[System architecture research](weisgnosis-os-and-agents-architecture.md) and the [porting guide](deep-research-and-porting-guide.md) explain broader platform goals and upstream analysis. Their sketches are not exported APIs or proof of implemented behavior.

## Maintaining documentation

Describe current APIs, usage, ownership, compatibility and limitations. Update affected contracts instead of appending delivery history. Keep review outcomes in the PR or task conversation; Git retains previous findings. Older package review journals still require cleanup, with their applicable contracts preserved.

Documentation distinguishes offline checks, actual live runs and unsupported behavior. Never include credentials or imply that passing tests establish exhaustive parity or production readiness.
