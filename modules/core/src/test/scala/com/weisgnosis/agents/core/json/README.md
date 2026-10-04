# Shared JSON boundary verification

[JsonBoundarySpec](JsonBoundarySpec.scala) covers complete documents, primitive/container syntax, escaping, recursive duplicate rejection, truncated input and direct AST validation. Its exact-rendering group uses independently authored numeric/escaped literals, nested objects/arrays, signed Long endpoints, a scaled minimum coefficient and a 20,000-level array. It does not generate expected wire values with the dependency writer under test.

[ExactJsonSpec](ExactJsonSpec.scala) adds two groups: independent nested/scaled numeric checkpoint/event/block round-trips including metadata, and typed rejection of duplicate objects or failed encoder AST conversion. The regression specifically covers the metadata decoder as well as rendering; custom lossy encoders remain outside the helper guarantee.

The [source contract](../../../../../../../main/scala/com/weisgnosis/agents/core/json/README.md#verification-and-acceptance) records the reproduced decimal corruption, current identities, review status and verification. Tests establish only the explicitly integrated renderer boundaries, not all derived codecs, arbitrary host serialization or general schema validation.
