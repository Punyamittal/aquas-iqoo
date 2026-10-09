# SMRITI AQUA (module)

> **iQOO Hackathon submodule** — native KissFFT DSP + acoustic probe + episodic memory for plumbing/wall acoustics.

## Role in the monorepo

Powers high-band acoustic fingerprinting and offline recall for the iQOO 15. The **main judge entry** is the root `:app` (AquaScope + SMRITI UI). This module supplies C++/JNI DSP building blocks.

## Why judges should care

| Claim | Detail |
|---|---|
| Hardware-native | Speaker chirp + mic + accel fusion on iQOO |
| Real DSP | KissFFT STFT · log-mel · fingerprint · anomaly |
| Grounded memory | Store events → Ask never invents leaks |
| Offline-capable design | Airplane-mode oriented contracts in `docs/` |

## Build

Open **repo root** in Android Studio · NDK + CMake · run on **iQOO 15 arm64**.

```bash
./gradlew :smriti-aqua:assembleDebug
```

## Demo (module / simulator)

1. Demo rig ON → CALIBRATE baseline → INSPECT anomaly  
2. Ask chips → grounded answers; refusal if asked to “confirm leak” without evidence  

## Related docs

- Root [`README.md`](../README.md) · [`JUDGES.md`](../JUDGES.md)  
- Spec [`docs/iQOO_15_SMRITI_AQUA_SPEC.md`](../docs/iQOO_15_SMRITI_AQUA_SPEC.md)
