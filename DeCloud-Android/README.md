# DeCloud - Android App

Fast file transfer from Android to PC using USB Tethering. Bypasses slow MTP protocol by streaming files as a single ZIP over HTTP.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        ANDROID APP                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │ MainActivity │───▶│BrowseActivity│───▶│ReadyToSend   │      │
│  │   (Home)     │    │(File Explorer)│   │  Activity    │      │
│  └──────────────┘    └──────────────┘    └──────────────┘      │
│                             │                    │              │
│                             ▼                    ▼              │
│                    ┌──────────────┐    ┌──────────────┐        │
│                    │ FileScanner  │    │TransferService│        │
│                    │   (Util)     │    │(Foreground)   │        │
│                    └──────────────┘    └──────────────┘        │
│                                               │                 │
│                    ┌──────────────┐           │                 │
│                    │SelectionMgr  │           ▼                 │
│                    │ (Singleton)  │    ┌──────────────┐        │
│                    └──────────────┘    │ZipStreamServer│        │
│                                        │  (NanoHTTPD)  │        │
│                                        └──────────────┘        │
│                                               │                 │
└───────────────────────────────────────────────│─────────────────┘
                                                │
                                                ▼
                                    HTTP Response (ZIP Stream)
                                    to PC Client
```

## File Structure

```
app/src/main/
├── java/com/decloud/
│   ├── model/
│   │   ├── FileItem.kt          # Data class for file/folder
│   │   └── SelectionManager.kt  # Global selection state (Singleton)
│   │
│   ├── util/
│   │   ├── FileScanner.kt       # Scans storage, lists files
│   │   └── NetworkUtils.kt      # IP detection, tethering check
│   │
│   ├── server/
│   │   └── ZipStreamServer.kt   # HTTP server, streams ZIP
│   │
│   ├── service/
│   │   └── TransferService.kt   # Foreground service (won't die)
│   │
│   └── ui/
│       ├── MainActivity.kt      # Home screen
│       ├── BrowseActivity.kt    # File explorer
│       ├── ReadyToSendActivity.kt # Transfer screen
│       └── adapter/
│           ├── FileAdapter.kt   # RecyclerView for files
│           └── SelectedFileAdapter.kt
│
└── res/
    ├── layout/                  # XML layouts
    ├── drawable/                # Icons
    └── values/                  # Colors, strings, themes
```

## Component Details

### 1. MainActivity (Home Screen)
**Purpose:** Entry point, quick actions

| Function | Description |
|----------|-------------|
| `checkPermissions()` | Request storage access |
| `quickSelectFolder()` | Auto-select DCIM/Videos |
| `updateConnectionStatus()` | Check USB tethering |

### 2. BrowseActivity (File Explorer)
**Purpose:** Browse and select files

| Function | Description |
|----------|-------------|
| `loadDirectory()` | List files in current folder |
| `navigateToDirectory()` | Enter a folder |
| `setupSortSpinner()` | Sort by name/size/date |
| `setupFilterSpinner()` | Filter by type |

### 3. ReadyToSendActivity (Transfer Screen)
**Purpose:** Confirm selection, manage transfer

| Function | Description |
|----------|-------------|
| `loadSelectedFiles()` | Show selection summary |
| `startTransfer()` | Start foreground service |
| `handleStatusChange()` | Update UI based on status |
| `updateProgress()` | Show transfer progress |

### 4. SelectionManager (Singleton)
**Purpose:** Track selected files across activities

| Function | Description |
|----------|-------------|
| `toggleSelection()` | Select/deselect file or folder |
| `selectAllInDirectory()` | Recursive folder selection |
| `getSelectedFiles()` | Get list of File objects |
| `getTotalSize()` | Calculate total bytes |

### 5. TransferService (Foreground Service)
**Purpose:** Keep app alive during transfer

| Function | Description |
|----------|-------------|
| `startServer()` | Launch HTTP server |
| `stopServer()` | Stop server |
| `acquireWakeLock()` | Prevent CPU sleep |
| `createNotification()` | Show ongoing notification |

### 6. ZipStreamServer (HTTP Server)
**Purpose:** Stream files as ZIP to PC

| Endpoint | Response |
|----------|----------|
| `GET /` | Status HTML page |
| `GET /info` | JSON with file count/size |
| `GET /download` | ZIP stream (one handshake!) |

## Data Flow

```
User selects files
        │
        ▼
┌───────────────────┐
│ SelectionManager  │  ◄── Stores file paths in memory
│   (Singleton)     │
└───────────────────┘
        │
        ▼
User taps "Start"
        │
        ▼
┌───────────────────┐
│ TransferService   │  ◄── Foreground service started
│ (startForeground) │      WakeLock acquired
└───────────────────┘
        │
        ▼
┌───────────────────┐
│ ZipStreamServer   │  ◄── HTTP server on port 8080
│ (NanoHTTPD)       │      Listening on USB tethering IP
└───────────────────┘
        │
        ▼
PC connects: GET /download
        │
        ▼
┌───────────────────┐
│ ZipOutputStream   │  ◄── Files streamed on-the-fly
│ (to HTTP socket)  │      No temp file created
└───────────────────┘
        │
        ▼
Transfer complete
```

## Key Design Decisions

| Decision | Reason |
|----------|--------|
| Foreground Service | Android won't kill it during transfer |
| NanoHTTPD library | Lightweight, single JAR, no dependencies |
| On-the-fly ZIP | No double storage needed |
| USB Tethering | Wired = stable, fast, no Wi-Fi needed |
| Singleton SelectionManager | Share state between activities |

## Permissions Required

| Permission | Why |
|------------|-----|
| `MANAGE_EXTERNAL_STORAGE` | Access all user files |
| `FOREGROUND_SERVICE` | Keep service alive |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ requirement |
| `WAKE_LOCK` | Prevent CPU sleep |
| `INTERNET` | HTTP server needs network |
| `POST_NOTIFICATIONS` | Show transfer notification |

## Build & Run

1. Open in Android Studio
2. Sync Gradle
3. Build > Make Project
4. Run on device

**Requirements:**
- Android 8.0+ (API 26+)
- Storage permission granted
- USB Tethering enabled for transfer
