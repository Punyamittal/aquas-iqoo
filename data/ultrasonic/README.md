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

## Testing new acoustic features

`locations.json` only stores the five computed features, so a new feature cannot be measured on old scans. The app therefore also keeps the raw impulse response of each scan:

```
/data/data/com.aquascope/files/aquascope_data/ir/<scanId>.f32   float32 little-endian
/data/data/com.aquascope/files/aquascope_data/ir/<scanId>.json  location, sample rate, label, phone features
```

Labels are `dry`, `damp`, `damp_implicit` (user skipped labelling, the app assumed damp) or `unlabelled`. The folder is capped at 64 MB, oldest first.

```powershell
python tools\evaluate_extra_features.py --pull
```

This pulls the folder to `data/ultrasonic/ir/` (git-ignored), checks the Python feature code against the phone's numbers, and compares leave-one-out accuracy per location for the five shipping features with and without each candidate. A candidate is only marked **keep** with 30+ labelled scans, 3+ locations that each have 2+ dry and 2+ damp scans, a gain of 3+ points, and more locations improved than worsened. Implicit damp labels are left out unless you pass `--include-implicit`.

## Judge note

Scoring works **out of the box** with defaults. Trained weights improve separation after you collect dry + moist samples on iQOO 15.
