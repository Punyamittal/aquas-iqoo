# SMRITI Core (module)

> **iQOO Hackathon submodule** — on-device second brain: episodic memory, Guardian, screen Play, Halo / IR / haptics.

## Role in the monorepo

Provides second-brain library capabilities (memory, ambient guardian, actuators). These are library contracts only; the shipping `:app` uses the memory engine and screen memory (`com.aquascope.smriti.brain`), not the Guardian or heart-rate screens.

## Why judges should care

| Pillar | What we ship |
|---|---|
| Memory | Room + FTS / embeddings · zero-hallucination recall |
| Guardian | Ambient audio (+ YAMNet when asset present) · fall cues |
| Actuators | Monster Halo RGB · haptics · optional IR |
| Capture | Screen ring buffer / clip flush hooks |
| Privacy | Designed for on-device inference; no account required |

## Build

```bash
./gradlew :smriti-core:assembleDebug
```

Drop-in assets (optional): `yamnet.tflite`, Vosk model — code degrades gracefully if missing.

## Related docs

- Root [`JUDGES.md`](../JUDGES.md)  
- Spec [`docs/iQOO_15_SMRITI_CORE_SPEC.md`](../docs/iQOO_15_SMRITI_CORE_SPEC.md)
