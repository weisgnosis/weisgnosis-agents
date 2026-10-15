# Release signing key

The public release signing key is [weisgnosis-release.asc](weisgnosis-release.asc).
Its primary fingerprint is:

```text
3884 E102 EAFC 8CE7 6C76 E4BF F342 DB1C 0CAC 3B0D
```

Inspect and import it from the repository root:

```sh
gpg --show-keys --with-fingerprint project/keys/weisgnosis-release.asc
gpg --import project/keys/weisgnosis-release.asc
```

Compare the full fingerprint before trusting the key. For a downloaded artifact
and its detached signature, verify both files together:

```sh
gpg --verify weisgnosis-agents-core_3-0.1.0-RC1.jar.asc weisgnosis-agents-core_3-0.1.0-RC1.jar
```

A valid signature establishes that the signing key signed those bytes; it does
not establish API stability or successful Maven Central publication. See the
[package guide](../../docs/packages.md) for available distribution channels.

Private keys, passphrases, revocation certificates and registry tokens must stay
outside this repository. This directory contains public key material only.
