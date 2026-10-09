# Ultrasonic / vibro-acoustic training data

> Labels for AquaScope anomaly scorer — used in iQOO hackathon demos to refine dry→moist weights.

## On-device source of truth

```
/data/data/com.aquascope/files/aquascope_data/locations.json
```

| Field | Meaning |
|---|---|
| `baselineFeatures` | Dry labels |
| `moistFeatures` | Moist / anomalous teaching labels |
| `scanHistory` | Scored compares |

## Pull + train (from repo root)

```powershell
.\tools\pull_and_train_ultrasonic.ps1
```

Or:

```powershell
mkdir data\ultrasonic -Force
adb exec-out run-as com.aquascope cat files/aquascope_data/locations.json > data\ultrasonic\locations.json
python tools\train_ultrasonic_scorer.py data\ultrasonic\locations.json
.\gradlew :app:installDebug
```

Output: `app/src/main/assets/ultrasonic/trained_weights.json` (loaded at app start by `UltrasonicModelWeights`).

## Judge note

Scoring works **out of the box** with defaults. Trained weights improve separation after you collect dry + moist samples on iQOO 15.
