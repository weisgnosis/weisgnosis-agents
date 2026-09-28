# Continuous integration

[ci.yml](ci.yml) checks out the repository, provisions JDK 21 and sbt, and runs `sbt verify` on pushes and pull requests. The command checks formatting, compilation with fatal warnings, and tests. Remote workflow execution must be checked separately from local validation. Semantic FP correctness and review approval remain mandatory manual gates.
