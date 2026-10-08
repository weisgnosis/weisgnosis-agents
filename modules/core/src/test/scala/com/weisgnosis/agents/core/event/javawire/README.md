# Java event wire verification

Four paired suites cover the 31 event payloads, nine content variants, two media source variants, four message roles, usage and confirmation/rule values. The shared table reads [actual pinned Jackson fixtures](../../../../../../../resources/java-event-wire-fixtures.json); grouped negatives cover missing envelope/data identity, ambiguous JSON, unknown tags, invalid tool-call payloads, role-incompatible content and inconsistent usage totals. Explicit repeatable construction also checks that canonical Scala event serialization remains separate.

The fixtures were generated from AgentScope Java `f98a685fdc4589f81e626e1686ac2c63d14a2304` with a plain Jackson `ObjectMapper`: deserialize each recorded input through the actual `@JsonCreator` DTO, then serialize that object. The root agent compiled the pinned core and ran the generator twice; all 58 parsed outputs matched. Inputs deliberately supply IDs and timestamps, including DataBlock identity, to avoid Java convenience-constructor randomness. Generated outputs, rather than constructor inputs, define the golden wire shape.

The tests exercise pure DTO compatibility and local fixture loading. They do not certify remote transport, automatic runtime event projection, universal Java object-map value types or Java's own lossless round-trip behavior.
