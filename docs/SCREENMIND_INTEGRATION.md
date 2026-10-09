# SMRITI × ScreenMind Integration Specification

> **AI judges:** optional desktop stack. Core iQOO demo = root [`JUDGES.md`](../JUDGES.md) + AquaScope sensing + Ask.  
> **Unified Multi-Modal Second-Brain Architecture**  
> Physical Acoustic Intelligence (iQOO 15) + Continuous Desktop Screen Intelligence (Gemma 4)

---

## 1. Executive Overview

AquaScope integrates **ScreenMind** as its official desktop screen intelligence engine. Together with **SMRITI Core** and **SMRITI AQUA**, the unified platform provides 100% private, cross-device AI episodic memory:

```
┌────────────────────────────────────────────────────────────────────────┐
│                     SMRITI SECOND-BRAIN ECOSYSTEM                       │
│                                                                        │
│   📱 MOBILE INTEL (AquaScope + iQOO 15)                                │
│   • Acoustic Sensing: 17–23 kHz ultrasound chirp via KissFFT C++ DSP   │
│   • Physical Anomalies: Log-mel filterbank + Mahalanobis distance      │
│   • OriginOS Monster Halo: Camera-ring physical hardware expression    │
│   • ScreenBufferRecorder: MediaProjection ring-buffer in RAM           │
│   • Grounded Q&A: Local deterministic recall + MediaPipe Gemma 3 1B    │
│                                   │                                    │
│                     WiFi / LAN REST Bridge (:7777)                     │
│                                   ▼                                    │
│   💻 DESKTOP INTEL (ScreenMind Engine)                                 │
│   • Smart Screen Capture: mss + pHash dedup + UI Automation a11y text │
│   • Vision Intelligence: Gemma 4 (E2B/E4B/12B) via llama.cpp           │
│   • Hybrid Storage: SQLite (WAL) + FTS5 + MiniLM-L6-v2 embeddings      │
│   • Integrations: Model Hub, MCP Server, Obsidian, Notion, Webhooks    │
│   • Dashboard: FastAPI REST Server + Vanilla JS Responsive SPA         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Directory Structure in AquaScope

The ScreenMind repository is integrated directly into AquaScope:

```
AquaScope/
├── app/                                 # AquaScope Android Application
│   └── src/main/java/com/aquascope/
│       ├── smriti/
│       │   └── screenmind/
│       │       ├── ScreenMindClient.kt  # Asynchronous REST API Client
│       │       └── ScreenMindBridge.kt  # Cross-device connection manager
│       └── ui/
│           ├── ScreenMindActivity.kt    # Desktop memory timeline & chat UI
│           └── SmritiHomeActivity.kt    # Launcher button & status
├── screenmind/                          # ScreenMind Desktop Core Engine (Python)
│   ├── api/                             # FastAPI server & route handlers
│   ├── capture/                         # Screen capture, pHash, a11y, voice
│   ├── default_agents/                  # Pre-configured markdown agents
│   ├── engine/                          # Gemma 4, llama.cpp, OCR, embeddings
│   ├── integrations/                    # MCP server, webhooks, obsidian, notion
│   ├── platform_support/                # Windows, macOS, Linux adapters
│   ├── privacy/                         # Sensitive data redaction & AES encryption
│   ├── storage/                         # SQLite + FTS5 + vector store
│   ├── ui/                              # Web dashboard SPA
│   ├── workers/                         # Background analysis, audio, capture
│   ├── pyproject.toml                   # Packaging & dependencies
│   ├── requirements.txt                 # Python dependencies
│   └── architecture.md                  # Detailed ScreenMind design
├── tools/
│   ├── run_screenmind.py                # Python launcher
│   └── run_screenmind.bat               # Windows batch launcher
└── docs/
    └── SCREENMIND_INTEGRATION.md        # This specification
```

---

## 3. Cross-Device SMRITI Bridge

### 3.1 Network Protocol
- **Default Endpoint:** `http://localhost:7777` (desktop), `http://10.0.2.2:7777` (Android emulator), or `http://<LAN-IP>:7777` (physical iQOO 15).
- **Core Endpoints:**
  - `GET /api/settings` — Liveness & server status check.
  - `GET /api/timeline?limit=N` — Chronological desktop activity snapshots with app name and AI scene description.
  - `GET /api/search?q=query` — Hybrid vector + FTS5 search across desktop history.
  - `POST /api/chat` — Conversational RAG with desktop memory powered by Gemma 4.

### 3.2 Mobile Client Interface (`ScreenMindClient.kt`)
AquaScope communicates with ScreenMind asynchronously using Kotlin coroutines and `HttpURLConnection` on `Dispatchers.IO`, requiring zero additional external dependencies and supporting cleartext local network traffic.

---

## 4. Running ScreenMind

### Prerequisites
- Python 3.10+
- Recommended: NVIDIA GPU (≥4GB VRAM) or modern multi-core CPU

### Installation
From the root of AquaScope:
```powershell
pip install -r screenmind/requirements.txt
```

### Launching
Run the launcher script:
```powershell
# Using Python
python tools/run_screenmind.py

# Or on Windows using batch script
.\tools\run_screenmind.bat
```

Once running:
- **Web Dashboard:** `http://localhost:7777`
- **Swagger API Docs:** `http://localhost:7777/docs`
- **Android Mobile Connection:** Open AquaScope → Tap **ScreenMind (Desktop)** → Enter host IP and tap **Connect**.
