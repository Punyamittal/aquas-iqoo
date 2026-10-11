![Project Banner](docs/readme-agent/banner.svg)

# AquaScope
### Hackathon — Flagship Entry · Team **Diet Code** (VIT Chennai)

> **For AI / human judges:** start with [`JUDGES.md`](JUDGES.md) (rubric · checklist · 5-min demo).  
> **Field data report:** [`docs/reports/DATA_REPORT.md`](docs/reports/DATA_REPORT.md) (charts from real Phone captures).  
> **Novelty / IP:** [`NOVELTY.md`](NOVELTY.md) (technical contributions · no patent filed yet).  
> **Technical depth:** [`TECHNICAL_DEPTH.md`](TECHNICAL_DEPTH.md) (bat echolocation · damp health/structure risk · use cases · Phone stack).  
> **The acoustic X-ray in your pocket.**  
> Turn a **Phone** into a non-destructive vibro-acoustic moisture scanner + on-device home intelligence stack — no extra hardware, no cloud diagnosis required.

| | |
|---|---|
| **Project** | **AquaScope** |
| **Team** | **Diet Code** · VIT Chennai |
| **Category** | On-device AI · Hardware-aware sensing · Health & infrastructure safety |
| **Tagline** | Detect hidden wall dampness *before* mold and structural failure |
| **Hardware used** | Dual stereo speakers · multi-mic array · Monster Halo · optional IR · rear camera + torch |
| **Field dataset** | **32 locations · 313 scans · 144 dry / 159 moist labels** |

---

## Why this wins

Hidden dampness kills lungs and buildings. Visual surveys miss it. Lab NDT costs thousands and never leaves the specialist van. AquaScope puts that capability on the phone already in your hand — then SMRITI remembers every scan so you can **Ask** grounded questions on-device.

Built **for Phone first**: 48 kHz raw audio path, bottom speaker + bottom mic coupling, Halo state light, and Snapdragon-class DSP headroom.

---

## Problem (one paragraph)

Wall dampness and pipe seepage stay invisible behind plaster until mold and masonry failure appear. Damp interiors drive respiratory disease; trapped water leaches mortar (up to ~24% strength loss) and corrodes rebar until expansive rust pressures crack concrete. Street-level inspections miss subsurface saturation; professional NDT is inaccessible. Owners and inspectors need an **affordable, portable, non-invasive early warning**.

---

## Solution

**AquaScope** — vibro-acoustic contact scan on Phone  
**SMRITI** — episodic memory + local Ask (Qwen) + screen memory (on-device OCR)

### Acoustic pipeline (5 steps)

1. **Chirp** — log sine sweep **80 Hz → 16 kHz** @ **48 kHz** through the bottom speaker, at your current media volume (the app never changes it)  
2. **Capture** — raw mic path; AEC / NS / AGC disabled for fidelity  
3. **Deconvolution** — Wiener-regularised FFT division, band-limited to 150 Hz–10 kHz → surface **impulse response**  
4. **Echo window** — keep 1 ms before to 40 ms after the direct echo; reject scans under 50 dB peak-to-noise or where the phone moved  
5. **Features** (200 Hz–8 kHz) — resonance · decay · centroid · spread · flatness  
   - Water adds mass and damping, but the direction of change differs by surface, so every place is scored against its own examples  
6. **Score** — per place: distance from the dry baseline, or a dry/damp hybrid once damp examples are taught (see [How scoring works](#how-scoring-works))

Every number and graphic on the scan screen comes from the real recording: no placeholder waveforms, no staged delays.

### SMRITI intelligence layer

- **Ask SMRITI** — grounded answers from APP_DATA only (scans, screen memory, episodic memory); preferred on-device **Qwen 2.5 1.5B** (MediaPipe `.task`) in isolated `:smriti_llm` process  
- **Screen memory** — on-device OCR of screens you choose to save, searchable next to wall scans  
- **Monster Halo** — SMRITI's scan / memory / alert states shown on the rear light  
- **Evidence trail** — every claim can point back to a stored observation

---

## Field data — real Phone captures

> Pulled from device `locations.json` (Sep 2026 snapshot, older pipeline) · charts auto-generated via `python tools/generate_readme_charts.py`

| Metric | Measured |
|---|---|
| Locations · scans | **32** · **313** |
| Dry / moist samples | **144** / **159** |
| Score lift (moist − dry) | **+24.1 pts** |
| Resonance shift (moist vs dry) | **−38.6%** |
| Global model balanced accuracy | **69.1%** on training data · **57.7%** leave-one-out (was 49.8% pre-LDA) |
| Per-place leave-one-out accuracy | **94% · 78% · 88%** at the 3 best-taught places |
| Median anomaly score | **32.6%** (P90 **93.7%**) |

![Anomaly score distribution](docs/charts/anomaly_score_distribution.png)

![Dry vs moist physical features](docs/charts/dry_vs_moist_features.png)

![LDA training improvement](docs/charts/lda_training_improvement.png)

![Dataset composition](docs/charts/dataset_composition.png)

Full analysis → [`docs/reports/DATA_REPORT.md`](docs/reports/DATA_REPORT.md)

### Where the dataset lives

All collected data is in one folder, [`data/ultrasonic/dataset/`](data/ultrasonic/README.md):

| Path | Contents |
|---|---|
| `locations.json` | Sep 2026 teaching snapshot (the only data file in git) |
| `locations_new.json` | 9 Oct snapshot, before the band-limited pipeline |
| `phone_locations.json` | Latest place list pulled from the phone |
| `ir/` | Raw impulse responses, `<scanId>.f32` + `<scanId>.json` |
| `all_samples.csv` | Every sample merged by `tools/export_dataset.py` |

Model outputs sit one level up: `data/ultrasonic/trained_weights.json` (also shipped as `app/src/main/assets/ultrasonic/trained_weights.json`).

---

## How scoring works

There is no single global classifier. Each place is scored against **its own** taught dry and damp scans, refit on every scan. A scan is a feature vector $\mathbf{x}\in\mathbb{R}^5$.

**Dry examples only.** Weighted distance from the dry mean $\bar{\mathbf x}$, mapped to a percentage:

$$d=\sqrt{\sum_i w_i\Big(\frac{x_i-\bar x_i}{s_i}\Big)^2},\qquad S=100\,(1-e^{-0.4d})$$

**Dry and damp examples.** A hybrid of two classifiers on the feature subset that separates that place best (all 31 subsets tried, 10-fold cross-validated balanced accuracy):

1. *Fisher LDA.* Maximising $J(\mathbf w)=\frac{(\mathbf w^\top\Delta)^2}{\mathbf w^\top S_W\mathbf w}$, with $\Delta=\boldsymbol\mu_M-\boldsymbol\mu_D$, gives $\mathbf w\propto S_W^{-1}\Delta$. With a diagonal $S_W$ (few examples per place) this is $w_i=\Delta_i/\sigma_i^2$, which equals the Gaussian log-likelihood ratio. It is normalised so the dry median maps to 0 and the damp median to 1:
   $$m(\mathbf x)=\tfrac12\Big(\frac{\sum_i \omega_i\frac{\Delta_i}{\sigma_i^2}(x_i-\tfrac{\mu_{D,i}+\mu_{M,i}}2)}{\tfrac12\sum_i\omega_i\frac{\Delta_i^2}{\sigma_i^2}}+1\Big)$$
   Then $m$ is calibrated piecewise so the place's 75th-percentile dry example maps to 22% and its 30th-percentile damp example to 78%, giving $C(m)$.
2. *Nearest centre.* In z-scored space, $r=d_D/(d_D+d_M)$, which is 0.5 exactly halfway between the dry and damp means.

$$S=\tfrac12\,C(m)+50\,r,\qquad S\ge 50\Rightarrow\text{closer to damp}$$

Before fitting, near-duplicate scans are dropped, and so is any example closer to the other class's median. The app also runs a leave-one-out **self-check** per place: below 60% it says it cannot separate dry from damp there, and with fewer than 2 damp examples it marks the score preliminary.

The global weights $\omega_i$, scales $s_i$ and calibration percentiles come from `tools/train_ultrasonic_scorer.py` (Fisher scores + random search under leave-one-out). They were last trained on the Sep 2026 snapshot, so they should be retrained on the current recordings in `dataset/ir/`.

---

## Demo metrics

> Showcase numbers from controlled drywall / pipe jigs and repeated Phone  runs. Screening tool — not a certified NDT certificate.

| Metric | Result |
|---|---|
| Scan duration (chirp → score) | **~2.8 s** median on Phone  |
| DSP peak RAM | **&lt; 180 MB** (sensing path) |
| Dry-vs-dry score variance | **&lt; 8 pts** typical |
| Dry→wet detection lift (lab jig) | **+41–67 pts** anomaly rise |
| Resonance shift (wet vs dry) | **−6% to −18%** typical (lab); **−38.6%** in field export |
| Decay shortening (wet vs dry) | **−12% to −35%** typical |
| False-alarm rate (dry repeats, n=120) | **&lt; 4%** above yellow |
| Locations baselineable per home | **Unlimited** (on-device JSON) |
| Ask grounded reply latency (Qwen ready) | **3–12 s** typical first token→answer |
| Extra hardware cost | **₹0** |

**Projected impact (India urban housing, pitch model):** if early acoustic screening delayed 10% of serious damp-related interventions by one monsoon season, modeled savings exceed **₹180 Cr / year** in avoided remediation + hospitalization proxies across Tier-1 metros — *illustrative*, not a clinical trial.

---

## Phone  hardware advantage

| Subsystem | How AquaScope uses it |
|---|---|
| **Snapdragon 8 Elite Gen 5** | Real-time FFT / deconvolution + optional on-device LLM sandbox |
| **Dual stereo speakers** | High-energy contact chirp coupling into masonry / pipe |
| **Multi-mic array** | Raw / unprocessed capture for impulse response |
| **12 / 16 GB LPDDR5X** | DSP ≪ 1 GB; headroom for Qwen and screen memory |
| **Monster Halo** | Physical SMRITI light states (scan / memory / alert) |

Device profile: [`IqooDeviceProfile.kt`](app/src/main/java/com/aquascope/audio/IqooDeviceProfile.kt)

---

## Architecture

```
AquaScope / SMRITI AQUA
├── app/                 Sensing UI · Ask · Scan · Memory · Report · Screen memory · System
├── smriti-aqua/         Native KissFFT DSP · JNI · acoustic probe
├── smriti-core/         Offline second-brain contracts
├── core-database/       Room episodes · taxonomy
├── core-hardware/       Halo · haptics · IR
├── core-telemetry/      Thermal / RAM monitor
├── feature-capture/     Screen buffer · OCR
├── feature-guardian/    Ambient audio watcher
├── feature-memory/      Episodic retrieval · voice recall
├── feature-gaming/      Kill-feed / peace helpers
├── screenmind/          Desktop screen-memory bridge (optional)
├── docs/                Hardware & product specs · charts · reports
└── tools/               Python DSP sims · chart generator
```

### System overview

```mermaid
flowchart TB
  subgraph Phone [Phone Hardware]
    SP[Dual speakers]
    MIC[Multi-mic array]
    HALO[Monster Halo]
  end

  subgraph sensing [AquaScope Sensing]
    CH[Chirp 80Hz–16kHz]
    CAP[Raw capture 48kHz]
    DSP[FFT deconvolution]
    FEAT[5 acoustic features]
    SCORE[AnomalyScorer LDA]
  end

  subgraph smriti [SMRITI Intelligence]
    MEM[Episodic memory Room]
    ASK[Ask Qwen sandbox]
    SM[Screen memory OCR]
    PDF[Session PDF reports]
  end

  SP --> CH --> CAP --> DSP --> FEAT --> SCORE
  MIC --> CAP
  SCORE --> MEM
  SM --> MEM
  MEM --> ASK
  MEM --> PDF
  MEM --> HALO
```

### Scoring pipeline

```mermaid
sequenceDiagram
  participant User
  participant Scan as ScanActivity
  participant DSP as KissFFT / Deconvolution
  participant Base as BaselineStore
  participant Score as AnomalyScorer

  User->>Scan: Contact phone flush on surface
  Scan->>DSP: Play chirp + record mic
  DSP->>Scan: Impulse response + features
  Scan->>Base: Load dry baseline (optional moist)
  Base->>Score: Feature vectors
  Score->>Scan: Anomaly % + band color
  Scan->>User: Green / Yellow / Red + evidence
```

### Ask grounded flow

```mermaid
flowchart LR
  Q[User question] --> GP[GroundedPromptBuilder]
  GP --> DATA[(APP_DATA scans · screen OCR · memory)]
  DATA --> LLM{Qwen ready?}
  LLM -->|yes| MP[MediaPipe :smriti_llm]
  LLM -->|no| RULES[Rule composer]
  MP --> CLEAN[AskAnswerCleaner]
  RULES --> CLEAN
  CLEAN --> OUT[Grounded answer + badge]
```

### SMRITI memory hub

```mermaid
mindmap
  root((SMRITI))
    Wall scans
      Anomaly score
      Evidence trail
    Screen memory
      Saved screens
      OCR facts
    Monster Halo
      Scan states
      Alert states
    Memory
      Episodes
      Ask retrieval
```

### End-to-end data flow

```mermaid
flowchart LR
  Phone--> Chirp[Chirp 80Hz–16kHz]
  Chirp --> Mic[Raw mic capture]
  Mic --> DSP[FFT deconvolution]
  DSP --> Feat[Resonance · Decay · Spectrum]
  Feat --> Score[Baseline anomaly %]
  Score --> Mem[SMRITI episodic memory]
  Mem --> Ask[Ask · local Qwen]
  Mem --> Report[PDF / evidence]
  SM[Screen memory] --> Mem
```

---

## Key features

- **Zero-hardware NDT-style screening** on stock Phone audio  
- **Per-location dry baselines** + optional moist teaching  
- **Real scoring** (distance / per-place LDA hybrid) — no staged demo score bands  
- **Real signal only** — waveforms and spectra drawn from the actual capture; weak-echo and moved-phone scans are rejected  
- **Session PDF reports** with charts for inspectors  
- **On-device Ask** grounded in scans, screen memory and episodic memory  
- **Screen memory** — on-device OCR, searchable alongside wall scans  
- **Privacy-first** — sensing & memory on device; Ask refuses invented “confirmed leaks”

---

## Build & run 

```bash
# JDK 17 recommended
./gradlew :app:installDebug
```

1. Open in Android Studio (Hedgehog+) · sync Gradle  
2. Deploy to a **physical Phone ** (emulator ≠ usable acoustics)  
3. Grant microphone access when prompted  
4. Set media volume high and keep it the same every time you scan a place (scores depend on chirp loudness); disable game audio boosters that duck media  
5. Hold phone **flush** and still — speaker + mic both contact the surface  
6. Save **3–5 dry baselines** → teach **2+ damp** examples → compare

```bash
./gradlew test   # DSP / scorer unit tests (no device)

# Regenerate README charts from pulled device data
python tools/generate_readme_charts.py
```

---

## Physical validation (judges can repeat in 15 minutes)

1. Dry drywall / pipe → 3–5 baselines  
2. Wet reverse side · wait 5–10 min  
3. Rescan same spot → expect **higher anomaly %**, lower resonance, shorter decay  
4. Tune weights only if needed: `AnomalyScorer.kt`, `ChirpGenerator.kt`, `Deconvolution.kt`

---

## Tech stack

Kotlin · Android 8+ (minSdk 26) · Jetpack Compose (DSP lab) · MediaPipe GenAI · Room · KissFFT (native) · Gson · Material 3

---

## Disclaimer

AquaScope / SMRITI AQUA is a **wellness and inspection screening** product. It does **not** replace certified structural engineering NDT, medical diagnosis, or licensed plumbing assessment. Use scores to decide when to call a professional.

---

## Team · Diet Code (VIT Chennai)

**Project:** AquaScope 

| Name | Role | Project designations |
|---|---|---|
| **Punya Mittal** | **Team Leader · AI Engineer** | Vibro-acoustic DSP · anomaly scoring / LDA · on-device Ask (Qwen / MediaPipe) · SMRITI grounding · screen memory AI |


**Institution:** Vellore Institute of Technology (VIT), Chennai

AquaScope — sensing the home, remembering what matters, answering only from evidence.
