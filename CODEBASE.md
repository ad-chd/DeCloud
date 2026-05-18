# DeCloud / DeCloud — Codebase Overview

A two-part file-transfer system that moves data between an Android phone and a Windows PC over WiFi hotspot, USB/ADB, or direct push — without relying on cloud services.

The repository contains two companion apps that speak a shared HTTP protocol on port **64666**:

| Project | Role | Stack |
|---|---|---|
| `DeCloud/` | Android client + transfer server | Kotlin, AndroidX, Material 3, NanoHTTPD |
| `DeCloud-Desktop/` | Windows desktop counterpart | Electron 28, Node.js, Vanilla JS |

---

## 1. DeCloud (Android)

High-performance Android app that scans the device, lets the user select files/categories/backups, and serves them as a streamed ZIP to a connected PC.

### Tech stack
- **Kotlin** 1.9.22 / **Java** 11
- **Android Gradle Plugin** 8.2.2, **minSdk** 26, **targetSdk** 34
- AndroidX (appcompat, lifecycle, coroutines), **Material 3**, RecyclerView + DiffUtil
- **NanoHTTPD** 2.3.1 — embedded HTTP server
- **ZXing** 3.5.2 — QR codes for pairing

### Architecture (5 layers)
1. **UI** — 7 Activities (ModeSelection → Main → Browse/Category/Search → ReadyToSend) + 7 adapters, View Binding, dark/light themes.
2. **State** — `SelectionManager` singleton: thread-safe selection of files/folders with cancellable async operations.
3. **Business logic** — `FileScanner`, `MediaScanner` (LRU cache, 10s TTL), `BackupManager` (contacts, SMS, call logs), `ThumbnailLoader`, `QRCodeGenerator`, `ThemeManager`.
4. **Services** — `TransferService` (WiFi hotspot, foreground), `AdbTransferService` (USB), `ReceiveService` (PC → phone).
5. **Server** — `ZipStreamServer` (NanoHTTPD on 64666) exposing `/info` (metadata) and `/download` (on-the-fly ZIP stream, no temp files); `DirectStreamServer` as alternative.

### Key features
- Three transfer modes: WiFi hotspot, USB/ADB, Direct Push
- Folder structure preservation
- Category browsing (Images, Videos, Audio, Docs, Downloads, Apps)
- Search with filters (type, size, date, depth)
- Media / contacts / SMS backup with prefix grouping
- QR pairing, real-time speed / ETA, retry + logs

### Layout
```
app/src/main/
├── java/com/decloud/
│   ├── ui/           Activities + adapters
│   ├── model/        FileItem, SelectionManager
│   ├── service/      Transfer / Adb / Receive services
│   ├── server/       ZipStreamServer, DirectStreamServer
│   ├── hotspot/      HotspotManager (WiFi AP)
│   ├── receiver/     AdbCommandReceiver
│   └── util/         15+ utilities
└── res/              19 layouts, 56+ drawables, themes
```

### Build / run
```bash
./gradlew installDebug   # or open in Android Studio and run
```

### Notable files
- `SelectionManager.kt` — core selection state
- `ZipStreamServer.kt` — HTTP server streaming ZIP
- `TransferService.kt` — foreground service
- `FileScanner.kt` / `MediaScanner.kt` — async enumeration + MediaStore
- `ReadyToSendActivity.kt` — transfer orchestration
- `UI_ABSTRACTION_LAYER.md` — 1300+ line UI reference

### Permissions
`MANAGE_EXTERNAL_STORAGE`, media perms, `INTERNET`, WiFi state, foreground service, wake lock, notifications, location (for hotspot), contacts/SMS (for backup), Bluetooth (fallback discovery).

---

## 2. DeCloud-Desktop (Windows Desktop)

Cross-platform (Windows-focused) desktop app. Discovers the phone, receives files, or pushes files back to the phone.

### Tech stack
- **Electron** 28.0.0 + **electron-builder** 24.9.1 (NSIS installer & portable EXE)
- Vanilla JS / HTML / CSS (no framework)
- Node APIs: `http`, `https`, `child_process` (ADB), `fs`, `net`
- Version **2.0.0**

### Architecture (3 processes)
1. **Main** (`src/main.js`, ~3500 LOC) — window + IPC handlers, HTTP agents (keep-alive, 48 sockets max), ADB orchestration, transfer state machine.
2. **Preload** (`src/preload.js`) — `contextBridge` exposing ~20 safe APIs to the renderer.
3. **Renderer** (`src/renderer.js`, ~1200 LOC) — single-page UI with WiFi/ADB toggle, Receive/Send toggle, progress, logs.

### Protocol / modes
| Mode | Android role | PC role | Transport |
|---|---|---|---|
| WiFi | HTTP server (64666) | HTTP client | Phone hotspot |
| ADB | Server via `adb reverse` | Client (`adb` CLI) | USB |
| Direct Push | Client | Server | USB |

### Key features
- Auto-scan gateway IPs, Bluetooth fallback discovery, manual IP entry
- ADB device detection, bundled ADB executables, path configuration UI
- Bidirectional transfer (Receive / Send)
- 24 parallel downloads with connection pooling
- TAR bundling for small files (<1MB) to cut overhead
- Batch mode threshold (5000 files), 200MB manifest cap
- Windows `MAX_PATH` (260) middle-truncation for long names
- Retries (3× initial, 2× per file, 3× cancel), 10s heartbeat, progress throttled every 500ms

### Layout
```
DeCloud-Desktop/
├── src/
│   ├── main.js       Electron main (~170KB)
│   ├── preload.js    IPC bridge
│   ├── renderer.js   UI logic
│   ├── index.html
│   └── styles.css    Dark theme (~22KB)
├── resources/adb/    Bundled ADB binaries
├── assets/icon.ico
├── package.json
├── build.bat / start.bat / check.bat
└── gen-icons.js
```

### Build / run
```bash
npm install
npm start                    # dev
npm run build:portable       # → dist/DeCloud-Portable-*.exe
npm run build:installer      # → dist/DeCloud-Setup-*.exe
```

---

## 3. How the two projects interact

1. **Pairing** — Android starts `TransferService`, launches `ZipStreamServer` on port **64666**, and shows a QR code / IP. PC scans or enters the IP.
2. **Handshake** — PC issues `GET /info` to validate the phone is ready and fetch metadata.
3. **Transfer**
   - **Phone → PC:** PC issues `GET /download`; Android streams selected files as ZIP.
   - **PC → Phone (WiFi):** Electron serves files; Android `ReceiveService` pulls them.
   - **PC → Phone (ADB):** Electron spawns `adb` commands with `adb reverse tcp:5555` forwarding.
4. **State machine** — both sides implement: Ready → Waiting → Transferring → Complete / Error, with retry + partial-failure handling.
5. **Shared constants** — port `64666`, common progress-callback pattern, symmetric selection model (`SelectionManager` on Android ↔ send-file list on Electron).

---

## 4. Stats

| Aspect | Android | Electron |
|---|---|---|
| Code size | ~45 Kotlin files, ~10K LOC | 3 JS files, ~3.5K LOC |
| Transfer modes | WiFi, ADB, Direct Push | WiFi, ADB |
| Architecture | 5-layer | 3-process |
| Key component | `SelectionManager` + `ZipStreamServer` | `main.js` (monolithic) |
| Port | 64666 | 64666 |
