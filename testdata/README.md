# Shared test vectors

Golden files that BOTH SDKs must satisfy, so the Swift and Kotlin
implementations cannot drift apart silently.

Planned layout (one directory per area, JSON vectors + expected outputs):

```
testdata/
├── commands/      # command payloads → expected serialized Commands messages
├── values/        # Daml values ↔ native type codec round-trips
└── events/        # raw transaction events → expected decoded models
```

Nothing here yet — vectors land together with the first codec implementations.
When adding a vector, wire it into `swift/Tests` and `kotlin/canton-sdk/src/test`
in the same PR.
