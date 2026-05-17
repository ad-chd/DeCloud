# DeCloud - UI Abstraction Layer Documentation
## Complete UI Analysis for Redesign

---

# TABLE OF CONTENTS

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Screen Wireframes](#3-screen-wireframes)
4. [UI Interaction Summary](#4-ui-interaction-summary)
5. [UI-to-Code Mapping](#5-ui-to-code-mapping)
6. [Data Flow Diagrams](#6-data-flow-diagrams)
7. [Component Abstraction](#7-component-abstraction)
8. [Redesign Guidelines](#8-redesign-guidelines)

---

# 1. EXECUTIVE SUMMARY

## App Purpose
DeCloud is an Android file transfer application that enables users to transfer files from their phone to a PC via WiFi hotspot, USB/ADB, or Direct Push methods.

## Current UI Stack
- **View Binding**: All activities use generated binding classes
- **Material Design 3**: Material3 components and theming
- **RecyclerView**: 7 adapters with DiffUtil
- **Theme**: Light/Dark mode support

## Screens Overview
| Screen | Purpose | Layout File | Activity |
|--------|---------|-------------|----------|
| Mode Selection | Choose transfer mode | activity_mode_selection.xml | ModeSelectionActivity.kt |
| Main/Home | File selection hub | activity_main.xml | MainActivity.kt |
| Browse | File system browser | activity_browse.xml | BrowseActivity.kt |
| Category | Media category browser | activity_category.xml | CategoryActivity.kt |
| Search | Advanced file search | activity_search.xml | SearchActivity.kt |
| Selection | Edit selected items | activity_selection.xml | SelectionActivity.kt |
| Ready to Send | Transfer execution | activity_ready_to_send.xml | ReadyToSendActivity.kt |

---

# 2. ARCHITECTURE OVERVIEW

## Layer Separation

```
+------------------------------------------------------------------+
|                         UI LAYER                                  |
|  (Layouts, Activities, Adapters, Custom Views)                   |
+------------------------------------------------------------------+
                              |
                              v
+------------------------------------------------------------------+
|                     STATE MANAGEMENT                              |
|  SelectionManager (Singleton) - Global selection state           |
|  ViewTypeManager - View preference persistence                    |
|  ThemeManager - Theme preference persistence                      |
+------------------------------------------------------------------+
                              |
                              v
+------------------------------------------------------------------+
|                    BUSINESS LOGIC LAYER                           |
|  FileScanner, MediaScanner, BackupManager, StorageUtils          |
|  ThumbnailLoader, NetworkUtils, QRCodeGenerator                  |
+------------------------------------------------------------------+
                              |
                              v
+------------------------------------------------------------------+
|                     SERVICE LAYER                                 |
|  TransferService (WiFi), AdbTransferService (USB)                |
|  DirectStreamServer, DirectPushClient                            |
+------------------------------------------------------------------+
```

## Key Dependency Map

```
ModeSelectionActivity
       |
       v
MainActivity -----> SelectionManager (Singleton)
       |                    |
       +--------------------+
       |
       v
+------+-------+--------+
|      |       |        |
v      v       v        v
Browse Category Search Selection
Activity Activity Activity Activity
       |       |       |        |
       +-------+-------+--------+
                   |
                   v
          ReadyToSendActivity
                   |
                   v
    +-------------+-------------+
    |             |             |
    v             v             v
TransferService AdbTransfer DirectPush
                Service      Client
```

---

# 3. SCREEN WIREFRAMES

## 3.1 Mode Selection Screen (Entry Point)

```
+----------------------------------------+
|                                        |
|            [Transfer Icon]             |
|                                        |
|            DeCloud               |
|         Select Transfer Mode           |
|                                        |
| +------------------------------------+ |
| | [WiFi Icon]                    ( ) | |
| | WiFi / Hotspot                     | |
| | 30-50 MB/s                         | |
| | No cable needed                    | |
| +------------------------------------+ |
|                                        |
| +------------------------------------+ |
| | [USB Icon]                     ( ) | |
| | USB / ADB                          | |
| | 40-50 MB/s                         | |
| | USB debugging required             | |
| +------------------------------------+ |
|                                        |
| +------------------------------------+ |
| | [Upload Icon]                  ( ) | |
| | Direct Push                        | |
| | 50-60 MB/s                         | |
| | Fastest USB transfer               | |
| +------------------------------------+ |
|                                        |
| +====================================+ |
| |            CONTINUE                | |
| +====================================+ |
|                                        |
|               v3.0                     |
+----------------------------------------+
```

### UI Elements
| Element ID | Type | Purpose |
|------------|------|---------|
| cardWifiMode | MaterialCardView | WiFi mode selection card |
| cardAdbMode | MaterialCardView | ADB mode selection card |
| cardDirectPushMode | MaterialCardView | Direct push selection card |
| radioWifi | RadioButton | WiFi mode indicator |
| radioAdb | RadioButton | ADB mode indicator |
| radioDirectPush | RadioButton | Direct push indicator |
| btnContinue | MaterialButton | Proceed to main screen |


## 3.2 Main/Home Screen

```
+----------------------------------------+
| DeCloud              [Theme] |
| Fast file transfer to PC               |
| [WiFi Mode - Phone creates hotspot]    |
+----------------------------------------+
|                                        |
| +====================================+ |
| | [Backup]  Full Backup          [>] | |
| |           Backup entire storage    | |
| +====================================+ |
|                                        |
| +------------------------------------+ |
| | [Folder]  Browse Files         [>] | |
| |           Select files to transfer | |
| +------------------------------------+ |
|                                        |
| Categories                             |
| +----------+ +----------+ +----------+ |
| | [Image]  | | [Video]  | | [Audio]  | |
| |  Images  | |  Videos  | |  Audio   | |
| +----------+ +----------+ +----------+ |
| +----------+ +----------+ +----------+ |
| | [Doc]    | | [Down]   | | [APK]    | |
| | Documents| | Downloads| |   Apps   | |
| +----------+ +----------+ +----------+ |
|                                        |
+----------------------------------------+
| No files selected                  [>] |
| +--------+              +------------+ |
| | Clear  |              |  Continue  | |
| +--------+              +------------+ |
+----------------------------------------+
```

### UI Elements
| Element ID | Type | Purpose |
|------------|------|---------|
| btnThemeToggle | ImageButton | Toggle dark/light theme |
| connectionStatus | TextView | Show current mode & status |
| btnFullBackup | LinearLayout | Full backup action |
| btnBrowseFiles | LinearLayout | Open file browser |
| btnImages | LinearLayout | Images category |
| btnVideos | LinearLayout | Videos category |
| btnAudio | LinearLayout | Audio category |
| btnDocuments | LinearLayout | Documents category |
| btnDownloads | LinearLayout | Downloads category |
| btnApps | LinearLayout | Applications category |
| selectionStatus | TextView | Selection summary |
| selectionInfoArea | LinearLayout | Clickable selection area |
| ivEditSelection | ImageView | Edit selection indicator |
| btnClearSelection | Button | Clear all selections |
| btnContinue | Button | Proceed to transfer |
| loadingOverlay | FrameLayout | Loading state overlay |
| progressBarLoading | ProgressBar | Loading indicator |
| tvLoadingMessage | TextView | Loading message |
| tvLoadingDetail | TextView | Loading detail text |
| btnCancelLoading | Button | Cancel loading operation |


## 3.3 Browse Files Screen

```
+----------------------------------------+
| [<]  Browse Files              [Search]|
+----------------------------------------+
| [Storage] Internal Storage             |
+----------------------------------------+
| Root > DCIM > Camera >                 |
+----------------------------------------+
| Select All   Deselect   [View] [Sort v]|
+----------------------------------------+
|                                        |
| +------------------------------------+ |
| | [x] [Folder] Documents         [>] | |
| |              12 items              | |
| +------------------------------------+ |
| | [ ] [Folder] Downloads         [>] | |
| |              8 items               | |
| +------------------------------------+ |
| | [x] [Image]  photo_001.jpg         | |
| |              2.5 MB  Today         | |
| +------------------------------------+ |
| | [ ] [Video]  video_001.mp4         | |
| |              125 MB  Yesterday     | |
| +------------------------------------+ |
|                                        |
|                 ...                    |
|                                        |
+----------------------------------------+
| 3 folders, 5 files (127.5 MB)          |
| Tap to view selection   [Ready to Send]|
+----------------------------------------+
```

### UI Elements
| Element ID | Type | Purpose |
|------------|------|---------|
| toolbar | Toolbar | Navigation bar with back button |
| btnSearch | ImageButton | Open search screen |
| tvStorageIndicator | TextView | Current storage name |
| breadcrumbScroll | HorizontalScrollView | Breadcrumb container |
| breadcrumbContainer | LinearLayout | Breadcrumb items |
| btnSelectAll | Button | Select all in current folder |
| btnDeselectAll | Button | Deselect all in current folder |
| btnViewType | ImageButton | Toggle list/grid view |
| spinnerSort | Spinner | Sort options dropdown |
| swipeRefresh | SwipeRefreshLayout | Pull to refresh |
| recyclerView | RecyclerView | File list |
| tvEmptyState | TextView | Empty folder message |
| selectionBar | LinearLayout | Bottom selection bar |
| selectionInfoArea | LinearLayout | Selection info (clickable) |
| tvSelectionCount | TextView | Selection count display |
| tvSelectionHint | TextView | "Tap to view selection" |
| btnReadyToSend | Button | Proceed to transfer |
| loadingOverlay | FrameLayout | Loading overlay |


## 3.4 Category Screen

```
+----------------------------------------+
| [<]  Images                            |
+----------------------------------------+
| 256 files    Select All  Deselect [Sort]|
+----------------------------------------+
|                                        |
| +------------------------------------+ |
| | [Thumb] photo_vacation_001.jpg     | |
| | [x]     4.2 MB  Jul 15, 2024       | |
| +------------------------------------+ |
| | [Thumb] screenshot_20240715.png    | |
| | [ ]     1.1 MB  Jul 15, 2024       | |
| +------------------------------------+ |
| | [Thumb] profile_picture.jpg        | |
| | [x]     2.8 MB  Jul 10, 2024       | |
| +------------------------------------+ |
|                                        |
|                 ...                    |
|                                        |
+----------------------------------------+
| 12 files selected (45.2 MB)            |
|                         [Ready to Send]|
+----------------------------------------+
```

### UI Elements
| Element ID | Type | Purpose |
|------------|------|---------|
| toolbar | Toolbar | Navigation with category title |
| tvFileCount | TextView | Total file count |
| btnSelectAll | Button | Select all files |
| btnDeselectAll | Button | Deselect all files |
| btnSort | ImageButton | Sort options popup |
| swipeRefresh | SwipeRefreshLayout | Pull to refresh |
| recyclerView | RecyclerView | Category file list |
| progressBar | ProgressBar | Loading indicator |
| tvEmptyState | TextView | No files message |
| selectionBar | LinearLayout | Bottom selection bar |
| tvSelectionCount | TextView | Selection summary |
| btnReadyToSend | Button | Proceed to transfer |
| loadingOverlay | FrameLayout | Loading overlay |


## 3.5 Search Screen

```
+----------------------------------------+
| [<] [____Search files...____] [X] [F]  |
| Searching in: Internal Storage         |
+----------------------------------------+
| ADVANCED SEARCH PANEL (Expandable)     |
| +------------------------------------+ |
| | Type:    [All Files         v]     | |
| | Match:   [ ] Case sensitive        | |
| | Size:    [Any size          v]     | |
| | Date:    [Any time          v]     | |
| | Storage: [All storage       v]     | |
| | Depth:   [All levels        v]     | |
| |                           [Apply]  | |
| +------------------------------------+ |
+----------------------------------------+
| [Spinner] Searching...                 |
+----------------------------------------+
|                                        |
| +------------------------------------+ |
| | [x] [File] report_2024.pdf         | |
| |            Documents > Work        | |
| +------------------------------------+ |
| | [ ] [File] notes_meeting.txt       | |
| |            Documents > Notes       | |
| +------------------------------------+ |
|                                        |
|        [Search Icon]                   |
|     Search for files                   |
|    Type to start searching             |
|                                        |
+----------------------------------------+
| Select All  Deselect  0 selected       |
|                      [Add to Transfer] |
+----------------------------------------+
```

### UI Elements
| Element ID | Type | Purpose |
|------------|------|---------|
| btnBack | ImageButton | Navigate back |
| etSearch | EditText | Search input field |
| btnClear | ImageButton | Clear search text |
| btnAdvancedSearch | ImageButton | Toggle advanced panel |
| tvSearchScope | TextView | Search scope indicator |
| advancedSearchPanel | LinearLayout | Advanced filters panel |
| spinnerTypeFilter | Spinner | File type filter |
| cbCaseSensitive | CheckBox | Case sensitive option |
| spinnerSizeFilter | Spinner | File size filter |
| spinnerDateFilter | Spinner | Date filter |
| spinnerStorageFilter | Spinner | Storage filter |
| spinnerDepthFilter | Spinner | Search depth filter |
| btnResetFilters | Button | Reset all filters |
| tvActiveFilters | TextView | Active filter summary |
| btnApplyFilters | Button | Apply filters |
| statusBar | LinearLayout | Search status bar |
| progressBar | ProgressBar | Search progress |
| tvStatus | TextView | Search status text |
| recyclerResults | RecyclerView | Search results list |
| emptyState | LinearLayout | Empty state container |
| ivEmptyIcon | ImageView | Empty state icon |
| tvEmptyTitle | TextView | Empty state title |
| tvEmptySubtitle | TextView | Empty state subtitle |
| selectionBar | LinearLayout | Bottom selection bar |
| btnSelectAll | Button | Select all results |
| btnDeselectAll | Button | Deselect all results |
| tvSelectionCount | TextView | Selection count |
| btnAddToTransfer | Button | Add to transfer queue |


## 3.6 Selection Editor Screen

```
+----------------------------------------+
| [<]  Edit Selection                    |
+----------------------------------------+
| 3 folders, 8 files                     |
| 256.7 MB                    [Clear All]|
+----------------------------------------+
| [   All   ] [ Folders ] [  Files  ]    |
+----------------------------------------+
|                                        |
| +------------------------------------+ |
| | [x] [Folder] Documents             | |
| |              /storage/emulated/0/  | |
| +------------------------------------+ |
| | [x] [Folder] DCIM/Camera           | |
| |              /storage/emulated/0/  | |
| +------------------------------------+ |
| | [x] [File]   report.pdf            | |
| |              Documents/Work        | |
| +------------------------------------+ |
|                                        |
+----------------------------------------+
| Tap item to deselect       [Continue]  |
+----------------------------------------+
```

### UI Elements
| Element ID | Type | Purpose |
|------------|------|---------|
| toolbar | Toolbar | Navigation bar |
| tvSelectionSummary | TextView | "X folders, Y files" |
| tvTotalSize | TextView | Total size display |
| btnClearAll | Button | Clear all selections |
| tabLayout | TabLayout | All/Folders/Files tabs |
| recyclerView | RecyclerView | Selection items list |
| tvEmptyState | TextView | No items message |
| btnContinue | Button | Proceed to transfer |


## 3.7 Ready to Send Screen

```
+----------------------------------------+
| [<]  Ready to Send                     |
+----------------------------------------+
| [Warning: No network connection]       |
+----------------------------------------+
| +------------------+------------------+ |
| |       150        |      2.5 GB     | |
| |      Files       |    Total Size   | |
| +------------------+------------------+ |
+----------------------------------------+
| Selected Files                         |
| +------------------------------------+ |
| | photo_001.jpg              2.5 MB  | |
| | video_001.mp4              125 MB  | |
| | document.pdf               1.2 MB  | |
| |              ...                   | |
| +------------------------------------+ |
+----------------------------------------+
| +------------+        +==============+ |
| |Edit Select |        |Start Transfer| |
| +------------+        +==============+ |
+----------------------------------------+

--- WAITING STATE ---
+----------------------------------------+
|         [Spinner]                      |
|   Waiting for PC to connect...         |
|   http://192.168.42.129:8080          |
|                                        |
|         [QR CODE]                      |
|   Scan QR or enter IP on PC           |
|                                        |
|          [Cancel]                      |
+----------------------------------------+

--- TRANSFERRING STATE ---
+----------------------------------------+
|    72%                    45 MB/s     |
|                           2m 30s left |
| [====================>              ] |
|                                        |
| Sending: photo_vacation_001.jpg       |
| File 108 of 150                       |
+----------------------------------------+

--- COMPLETE STATE ---
+----------------------------------------+
| [Success]  Transfer Complete       [X] |
|            150 files transferred       |
| +----------+----------+----------+     |
| |   150    |    0     |  1m 30s  |     |
| |Transferred| Failed  | Duration |     |
| +----------+----------+----------+     |
|                                        |
| File Details                           |
| +------------------------------------+ |
| | [ok] photo_001.jpg                 | |
| | [ok] video_001.mp4                 | |
| +------------------------------------+ |
|                                        |
| [Save Log] [New Transfer] [  Done  ]   |
+----------------------------------------+

--- ERROR STATE ---
+----------------------------------------+
|              [X]                       |
|         [Error Icon]                   |
|       Transfer Failed                  |
|   Connection lost unexpectedly         |
|                                        |
|         [Save Log]                     |
|         [Try Again]                    |
+----------------------------------------+
```

### UI Elements
| Element ID | Type | Purpose |
|------------|------|---------|
| toolbar | Toolbar | Navigation bar |
| connectionWarning | TextView | Connection warning banner |
| tvFileCount | TextView | File count display |
| tvTotalSize | TextView | Total size display |
| recyclerViewFiles | RecyclerView | Selected files preview |
| layoutReady | LinearLayout | Ready state container |
| btnEditSelection | Button | Edit selection |
| btnStart | Button | Start transfer |
| layoutWaiting | LinearLayout | Waiting state container |
| tvStatus | TextView | Status message |
| tvServerAddress | TextView | Server IP address |
| ivQrCode | ImageView | QR code display |
| tvQrHint | TextView | QR hint text |
| btnCancel | Button | Cancel waiting |
| layoutTransferring | LinearLayout | Transfer progress container |
| tvProgressPercent | TextView | Progress percentage |
| tvSpeed | TextView | Transfer speed |
| tvEta | TextView | Time remaining |
| progressBar | ProgressBar | Progress bar |
| tvCurrentFile | TextView | Current file name |
| tvFileProgress | TextView | File X of Y |
| layoutComplete | LinearLayout | Complete state container |
| ivCompleteIcon | ImageView | Success/partial icon |
| tvCompleteTitle | TextView | Completion title |
| tvCompleteMessage | TextView | Completion summary |
| layoutTransferStats | LinearLayout | Stats container |
| tvTransferredCount | TextView | Transferred count |
| tvFailedCount | TextView | Failed count |
| tvTransferTime | TextView | Duration |
| recyclerViewResults | RecyclerView | Result details list |
| btnSaveLog | Button | Save transfer log |
| btnReset | Button | New transfer |
| btnDone | Button | Finish |
| layoutError | LinearLayout | Error state container |
| tvErrorMessage | TextView | Error description |
| btnSaveErrorLog | Button | Save error log |
| btnRetry | Button | Retry transfer |
| loadingOverlay | LinearLayout | Loading overlay |


---

# 4. UI INTERACTION SUMMARY

## 4.1 User Flow Diagram

```
[Launch App]
      |
      v
+------------------+
| Mode Selection   |  <-- Select: WiFi / ADB / Direct Push
+------------------+
      |
      v (Continue)
+------------------+
|   Main/Home      |  <-- Hub for all file selection methods
+------------------+
      |
      +---> [Full Backup] --> Scans all storage --> ReadyToSend
      |
      +---> [Browse Files] --> Storage selection dialog
      |           |
      |           v
      |    +------------------+
      |    | Browse Activity  |  <-- Navigate folders, select files
      |    +------------------+
      |           |
      +---> [Category]    |
      |      (Images,     |
      |       Videos,     |
      |       Audio,      |
      |       Documents,  |
      |       Downloads,  |
      |       Apps)       |
      |           |       |
      |           v       |
      |    +------------------+
      |    | Category Activity|  <-- View & select media files
      |    +------------------+
      |                   |
      +---> [Selection Info Area] --> SelectionActivity
      |                   |
      |    +--------------+
      |    | Search (from BrowseActivity)
      |    |       |
      |    |       v
      |    | +------------------+
      |    | | Search Activity  |  <-- Advanced search & select
      |    | +------------------+
      |    |       |
      |    +-------+
      |            |
      v            v
+------------------+
| Selection Editor |  <-- Review/edit selections
+------------------+
      |
      v (Continue)
+------------------+
| Ready to Send    |  <-- Start transfer, view progress
+------------------+
      |
      +---> [WiFi Mode] --> TransferService --> Hotspot + HTTP Server
      |
      +---> [ADB Mode] --> AdbTransferService --> USB Transfer
      |
      +---> [Direct Push] --> DirectPushClient --> Direct USB Transfer
```


## 4.2 Interaction Matrix

| Screen | Action | Triggers | Result |
|--------|--------|----------|--------|
| **Mode Selection** | Tap mode card | selectMode() | Updates radio state, card border |
| | Tap Continue | btnContinue.click | Navigate to MainActivity with mode |
| **Main/Home** | Tap Full Backup | showBackupOptionsDialog() | Shows backup options dialog |
| | Tap Browse Files | showStorageSelectionDialog() | Shows storage picker / BrowseActivity |
| | Tap Category | openCategory(category) | Navigate to CategoryActivity |
| | Tap Selection Area | click | Navigate to SelectionActivity |
| | Tap Clear | SelectionManager.deselectAll() | Clear all selections |
| | Tap Continue | Intent(ReadyToSendActivity) | Navigate if has selection |
| | Tap Theme | ThemeManager.toggleTheme() | Recreate with new theme |
| **Browse** | Tap folder | Navigate deeper | Update breadcrumb, load folder |
| | Tap checkbox | SelectionManager.toggleSelection() | Toggle selection state |
| | Tap Select All | SelectionManager.selectAllInList() | Select all visible items |
| | Tap Deselect | SelectionManager.deselectDirectChildrenOf() | Deselect current folder items |
| | Tap View Type | ViewTypeManager.cycleViewType() | Toggle list/grid view |
| | Tap Sort | Show spinner | Apply sort order |
| | Tap Search | Intent(SearchActivity) | Navigate to search |
| | Swipe down | SwipeRefresh | Reload folder contents |
| | Tap Ready to Send | Intent(ReadyToSendActivity) | Navigate to transfer |
| **Category** | Tap file checkbox | SelectionManager.toggleSelection() | Toggle selection |
| | Tap Select All | Select all category files | Update all checkboxes |
| | Tap Sort | PopupMenu | Apply sort order |
| | Swipe down | SwipeRefresh | Reload category |
| **Search** | Type in search | Debounced search | Live results |
| | Tap filter icon | Toggle panel visibility | Show/hide filters |
| | Change filter | Apply filters | Update results |
| | Tap Apply | Execute filtered search | Show filtered results |
| | Tap Reset | Reset all filters | Clear filter state |
| | Tap result checkbox | toggleSelection() | Toggle selection |
| | Tap Add to Transfer | finish() | Return to previous screen |
| **Selection** | Tap tab | Filter list | Show all/folders/files |
| | Tap item | deselectFile/deselectAllInDirectory | Remove from selection |
| | Tap Clear All | deselectAll() | Clear all selections |
| | Tap Continue | Intent(ReadyToSendActivity) | Navigate to transfer |
| **Ready to Send** | Tap Start Transfer | startTransferService() | Begin transfer |
| | Tap Edit Selection | Intent(SelectionActivity) | Navigate to edit |
| | Tap Cancel | stopService() | Cancel transfer |
| | Tap Save Log | TransferLogger.saveLog() | Save to file |
| | Tap New Transfer | deselectAll() + finish() | Reset state |
| | Tap Done | finish() | Close screen |
| | Tap Retry | restartTransfer() | Retry failed transfer |


## 4.3 State Transitions

### Selection State Machine
```
+-------------+     toggleSelection()    +-------------+
|  Unselected | -----------------------> |  Selected   |
+-------------+ <----------------------- +-------------+
                   toggleSelection()
                         |
                         | (async for folders)
                         v
                 +---------------+
                 |  Selecting... |
                 +---------------+
                    |         |
        onComplete()|         | cancelSelection()
                    v         v
             +----------+  +-------------+
             | Selected |  | Unselected  |
             +----------+  | (restored)  |
                           +-------------+
```

### Transfer State Machine
```
+--------+  startTransfer()  +---------+  onServerReady()  +-------------+
| Ready  | ----------------> | Waiting | ----------------> | Transferring|
+--------+                   +---------+                   +-------------+
    ^                            |                              |
    |                            | cancel()                     | onProgress()
    |                            v                              v
    |                       +--------+                    +-------------+
    |                       | Ready  |                    | Transferring|
    |                       +--------+                    +-------------+
    |                                                           |
    | reset()                                    onComplete() / |
    |                                            onError()      |
    |    +----------+                                          |
    +--- | Complete | <----------------------------------------+
         +----------+
              |
              | (if partial failure)
              v
         +----------+
         | Error    |
         +----------+
```

---

# 5. UI-TO-CODE MAPPING

## 5.1 Complete View-to-Handler Mapping

### ModeSelectionActivity.kt

| View ID | View Type | Handler Method | Business Logic |
|---------|-----------|----------------|----------------|
| cardWifiMode | MaterialCardView | setOnClickListener | selectWifiMode() |
| cardAdbMode | MaterialCardView | setOnClickListener | selectAdbMode() |
| cardDirectPushMode | MaterialCardView | setOnClickListener | selectDirectPushMode() |
| radioWifi | RadioButton | (controlled by card) | Updates selectedMode |
| radioAdb | RadioButton | (controlled by card) | Updates selectedMode |
| radioDirectPush | RadioButton | (controlled by card) | Updates selectedMode |
| btnContinue | MaterialButton | setOnClickListener | Intent(MainActivity) with EXTRA_TRANSFER_MODE |

### MainActivity.kt

| View ID | View Type | Handler Method | Business Logic |
|---------|-----------|----------------|----------------|
| btnThemeToggle | ImageButton | setOnClickListener | ThemeManager.toggleTheme() |
| btnFullBackup | LinearLayout | setOnClickListener | showBackupOptionsDialog() -> BackupManager.startFullBackup() |
| btnBrowseFiles | LinearLayout | setOnClickListener | showStorageSelectionDialog() -> Intent(BrowseActivity) |
| btnImages | LinearLayout | setOnClickListener | openCategory(MediaScanner.Category.IMAGES) |
| btnVideos | LinearLayout | setOnClickListener | openCategory(MediaScanner.Category.VIDEOS) |
| btnAudio | LinearLayout | setOnClickListener | openCategory(MediaScanner.Category.AUDIO) |
| btnDocuments | LinearLayout | setOnClickListener | openCategory(MediaScanner.Category.DOCUMENTS) |
| btnDownloads | LinearLayout | setOnClickListener | openCategory(MediaScanner.Category.DOWNLOADS) |
| btnApps | LinearLayout | setOnClickListener | openCategory(MediaScanner.Category.APPLICATIONS) |
| selectionInfoArea | LinearLayout | setOnClickListener | Intent(SelectionActivity) |
| btnClearSelection | Button | setOnClickListener | SelectionManager.deselectAll() |
| btnContinue | Button | setOnClickListener | Intent(ReadyToSendActivity) |
| btnCancelLoading | Button | setOnClickListener | backupJob?.cancel(), BackupManager.cancelBackup() |
| connectionStatus | TextView | - | Updated by updateConnectionStatus() |
| selectionStatus | TextView | - | Updated by updateSelectionStatus() |
| loadingOverlay | FrameLayout | - | Controlled by showLoadingOverlay(), hideLoadingOverlay() |

### BrowseActivity.kt (Expected handlers based on layout)

| View ID | View Type | Handler Method | Business Logic |
|---------|-----------|----------------|----------------|
| toolbar | Toolbar | setNavigationOnClickListener | finish() |
| btnSearch | ImageButton | setOnClickListener | Intent(SearchActivity) |
| btnSelectAll | Button | setOnClickListener | SelectionManager.selectAllInList() |
| btnDeselectAll | Button | setOnClickListener | SelectionManager.deselectDirectChildrenOf() |
| btnViewType | ImageButton | setOnClickListener | ViewTypeManager.cycleViewType() |
| spinnerSort | Spinner | onItemSelectedListener | Apply SortType to adapter |
| swipeRefresh | SwipeRefreshLayout | setOnRefreshListener | Reload current directory |
| recyclerView | RecyclerView | FileAdapter.onItemClick | Navigate or toggle selection |
| selectionInfoArea | LinearLayout | setOnClickListener | Intent(SelectionActivity) |
| btnReadyToSend | Button | setOnClickListener | Intent(ReadyToSendActivity) |
| breadcrumbContainer | LinearLayout | child.setOnClickListener | Navigate to path |
| btnCancelLoading | Button | setOnClickListener | Cancel async selection |

### CategoryActivity.kt (Expected handlers)

| View ID | View Type | Handler Method | Business Logic |
|---------|-----------|----------------|----------------|
| toolbar | Toolbar | setNavigationOnClickListener | finish() |
| btnSelectAll | Button | setOnClickListener | Select all in category |
| btnDeselectAll | Button | setOnClickListener | Deselect all in category |
| btnSort | ImageButton | setOnClickListener | Show sort PopupMenu |
| swipeRefresh | SwipeRefreshLayout | setOnRefreshListener | Reload category |
| recyclerView | RecyclerView | CategoryFileAdapter.onItemClick | Toggle selection |
| btnReadyToSend | Button | setOnClickListener | Intent(ReadyToSendActivity) |

### SearchActivity.kt (Expected handlers)

| View ID | View Type | Handler Method | Business Logic |
|---------|-----------|----------------|----------------|
| btnBack | ImageButton | setOnClickListener | finish() |
| etSearch | EditText | addTextChangedListener | Debounced FileScanner.liveSearchAsyncWithFilters() |
| btnClear | ImageButton | setOnClickListener | Clear search text |
| btnAdvancedSearch | ImageButton | setOnClickListener | Toggle advancedSearchPanel visibility |
| btnResetFilters | Button | setOnClickListener | Reset all filter spinners |
| btnApplyFilters | Button | setOnClickListener | Execute filtered search |
| spinnerTypeFilter | Spinner | onItemSelectedListener | Set type filter |
| cbCaseSensitive | CheckBox | setOnCheckedChangeListener | Set case sensitivity |
| spinnerSizeFilter | Spinner | onItemSelectedListener | Set size filter |
| spinnerDateFilter | Spinner | onItemSelectedListener | Set date filter |
| spinnerStorageFilter | Spinner | onItemSelectedListener | Set storage filter |
| spinnerDepthFilter | Spinner | onItemSelectedListener | Set depth filter |
| recyclerResults | RecyclerView | SearchResultSelectableAdapter.onClick | Toggle selection |
| btnSelectAll | Button | setOnClickListener | Select all results |
| btnDeselectAll | Button | setOnClickListener | Deselect all results |
| btnAddToTransfer | Button | setOnClickListener | finish() with selection |

### SelectionActivity.kt (Expected handlers)

| View ID | View Type | Handler Method | Business Logic |
|---------|-----------|----------------|----------------|
| toolbar | Toolbar | setNavigationOnClickListener | finish() |
| btnClearAll | Button | setOnClickListener | SelectionManager.deselectAll() |
| tabLayout | TabLayout | addOnTabSelectedListener | Filter list by type |
| recyclerView | RecyclerView | SelectionItemAdapter.onClick | Deselect item |
| btnContinue | Button | setOnClickListener | Intent(ReadyToSendActivity) |

### ReadyToSendActivity.kt (Expected handlers)

| View ID | View Type | Handler Method | Business Logic |
|---------|-----------|----------------|----------------|
| toolbar | Toolbar | setNavigationOnClickListener | finish() |
| btnEditSelection | Button | setOnClickListener | Intent(SelectionActivity) |
| btnStart | Button | setOnClickListener | startTransfer() based on mode |
| btnCancel | Button | setOnClickListener | stopService() |
| btnSaveLog | Button | setOnClickListener | TransferLogger.saveLog() |
| btnReset | Button | setOnClickListener | SelectionManager.deselectAll(), reset UI |
| btnDone | Button | setOnClickListener | finish() |
| btnRetry | Button | setOnClickListener | Restart transfer |
| btnCloseComplete | ImageButton | setOnClickListener | Reset to ready state |
| btnCloseError | ImageButton | setOnClickListener | Reset to ready state |
| btnSaveErrorLog | Button | setOnClickListener | TransferLogger.saveLog() |


## 5.2 Adapter-to-Data Mapping

| Adapter Class | Data Type | ViewHolder | Interaction |
|---------------|-----------|------------|-------------|
| FileAdapter | List<FileItem> | FileViewHolder | Click: navigate/select, LongClick: info |
| CategoryFileAdapter | List<FileItem> | CategoryFileViewHolder | Click: toggle selection |
| SearchResultAdapter | List<FileItem> | SearchResultViewHolder | Click: none (read-only) |
| SearchResultSelectableAdapter | List<FileItem> | SelectableResultViewHolder | Click: toggle selection |
| SelectedFileAdapter | List<FileItem> | SelectedFileViewHolder | Click: show file info |
| SelectionItemAdapter | List<SelectionItem> | SelectionItemViewHolder | Click: deselect item |
| TransferResultAdapter | List<TransferResult> | TransferResultViewHolder | Click: expand details |


## 5.3 Service/Receiver-to-UI Mapping

| Service/Receiver | UI Updates Via | Target Elements |
|------------------|----------------|-----------------|
| TransferService | Callbacks | layoutWaiting, layoutTransferring, layoutComplete |
| TransferService | onStatusChanged() | tvStatus |
| TransferService | onProgressChanged() | progressBar, tvProgressPercent, tvCurrentFile |
| TransferService | onHotspotReady() | tvServerAddress, ivQrCode |
| AdbTransferService | Callbacks | Same as TransferService |
| DirectPushClient | Callbacks | Same as TransferService |

---

# 6. DATA FLOW DIAGRAMS

## 6.1 Selection Data Flow

```
User Interaction (tap checkbox)
           |
           v
+------------------------+
| Activity (Browse/      |
| Category/Search)       |
+------------------------+
           |
           | toggleSelection(fileItem, scope)
           v
+------------------------+
| SelectionManager       |
| (Singleton)            |
+------------------------+
           |
    +------+-------+
    |              |
    v              v
File?           Directory?
    |              |
    v              v
selectedFiles    selectedDirectories
.add(path)       .add(path)
                      |
                      v
              selectAllInDirectoryAsync()
              (recursive, async)
                      |
                      v
              progressListener.onProgress()
                      |
           +----------+-----------+
           |                      |
           v                      v
     selectionJob          notifyListeners()
     (cancellable)               |
                                 v
                    +------------------------+
                    | Activity.onSelection-  |
                    | Changed()              |
                    +------------------------+
                           |
                           v
                    Update UI:
                    - tvSelectionCount
                    - btnReadyToSend.isEnabled
                    - checkbox states
```


## 6.2 Transfer Data Flow

```
+------------------------+
| ReadyToSendActivity    |
| btnStart.click         |
+------------------------+
           |
           | Check MainActivity.currentTransferMode
           |
     +-----+------+------+
     |            |      |
     v            v      v
  "wifi"       "adb"  "direct_push"
     |            |      |
     v            v      v
+----------+  +-------+  +------------+
|Transfer- |  |AdbTr- |  |DirectPush- |
|Service   |  |ansfer |  |Client      |
|          |  |Service|  |            |
+----------+  +-------+  +------------+
     |            |            |
     v            v            v
+----------+  +---------+  +---------+
|Hotspot-  |  |ADB      |  |Direct   |
|Manager   |  |Commands |  |Socket   |
+----------+  +---------+  +---------+
     |
     v
+-------------+
|DirectStream-|
|Server       |
+-------------+
     |
     v
[Files streamed to PC]
     |
     | Callbacks:
     | - onStatusChanged()
     | - onProgressChanged()
     | - onComplete()
     | - onError()
     v
+------------------------+
| ReadyToSendActivity    |
| Update UI state        |
+------------------------+
     |
     +-> layoutWaiting (show QR, IP)
     +-> layoutTransferring (show progress)
     +-> layoutComplete (show stats)
     +-> layoutError (show error)
```


## 6.3 File Scanning Data Flow

```
Browse/Category Activity
           |
           | loadDirectory() / loadCategory()
           v
+------------------------+
| FileScanner /          |
| MediaScanner           |
+------------------------+
           |
           | listFilesAsync() /
           | getFilesByCategory()
           v
+------------------------+
| LRU Cache Check        |
| (10s validity)         |
+------------------------+
           |
    +------+------+
    |             |
    v             v
  Cache Hit    Cache Miss
    |             |
    v             v
  Return      Scan filesystem
  cached      / MediaStore
              query
                  |
                  | (max 1000 items
                  |  per batch)
                  v
           +------------------------+
           | List<FileItem>         |
           +------------------------+
                  |
                  v
           +------------------------+
           | Adapter.submitList()   |
           | (AsyncListDiffer)      |
           +------------------------+
                  |
                  | DiffUtil calculates
                  | minimal changes
                  v
           +------------------------+
           | RecyclerView updates   |
           | only changed items     |
           +------------------------+
```

---

# 7. COMPONENT ABSTRACTION

## 7.1 Reusable UI Components

### SelectionBottomBar
**Purpose**: Consistent selection status bar across screens

```kotlin
interface SelectionBottomBar {
    fun updateSelectionCount(count: Int, totalSize: String)
    fun setActionButtonEnabled(enabled: Boolean)
    fun setActionButtonText(text: String)
    fun setOnActionClickListener(listener: () -> Unit)
    fun setOnSelectionInfoClickListener(listener: () -> Unit)
}

// Current implementations:
// - activity_main.xml: selectionInfoArea + btnClearSelection + btnContinue
// - activity_browse.xml: selectionBar + tvSelectionCount + btnReadyToSend
// - activity_category.xml: selectionBar + tvSelectionCount + btnReadyToSend
// - activity_search.xml: selectionBar + tvSelectionCount + btnAddToTransfer
```

### LoadingOverlay
**Purpose**: Consistent loading state with cancel support

```kotlin
interface LoadingOverlay {
    fun show(message: String, detail: String = "")
    fun update(message: String, detail: String = "")
    fun hide()
    fun setOnCancelListener(listener: () -> Unit)
}

// Current implementations:
// - activity_main.xml: loadingOverlay
// - activity_browse.xml: loadingOverlay
// - activity_category.xml: loadingOverlay
```

### FileListItem
**Purpose**: Consistent file/folder display across list types

```kotlin
interface FileListItem {
    fun bind(fileItem: FileItem, isSelected: Boolean, viewType: ViewType)
    fun setOnClickListener(listener: (FileItem) -> Unit)
    fun setOnLongClickListener(listener: (FileItem) -> Boolean)
    fun setOnCheckboxClickListener(listener: (FileItem, Boolean) -> Unit)
}

// Current implementations:
// - item_file.xml (list view)
// - item_file_grid.xml (grid view)
// - item_file_compact.xml (compact view)
// - item_category_file.xml (category view)
// - item_search_result.xml (read-only)
// - item_search_result_selectable.xml (selectable)
```

### TransferModeCard
**Purpose**: Mode selection card with radio indicator

```kotlin
interface TransferModeCard {
    fun setSelected(selected: Boolean)
    fun setIcon(iconRes: Int, tintColor: Int)
    fun setTitle(title: String)
    fun setSpeed(speed: String)
    fun setDescription(description: String)
    fun setOnClickListener(listener: () -> Unit)
}

// Current implementation:
// - activity_mode_selection.xml: cardWifiMode, cardAdbMode, cardDirectPushMode
```


## 7.2 State Objects

### SelectionState
```kotlin
data class SelectionState(
    val selectedFiles: Set<String>,
    val selectedDirectories: Set<String>,
    val topLevelFiles: Set<String>,
    val topLevelFolders: Set<String>,
    val totalSize: Long,
    val isSelectionInProgress: Boolean,
    val isBackupMode: Boolean
) {
    val fileCount: Int get() = selectedFiles.size
    val folderCount: Int get() = topLevelFolders.size
    val hasSelection: Boolean get() = selectedFiles.isNotEmpty()

    fun getSummary(): String
    fun getShortSummary(): String
    fun getFormattedSize(): String
}
```

### TransferState
```kotlin
sealed class TransferState {
    object Ready : TransferState()
    data class Waiting(val serverAddress: String?, val qrCode: Bitmap?) : TransferState()
    data class Transferring(
        val progress: Int,
        val speed: String,
        val eta: String,
        val currentFile: String,
        val fileIndex: Int,
        val totalFiles: Int
    ) : TransferState()
    data class Complete(
        val transferred: Int,
        val failed: Int,
        val duration: String,
        val results: List<TransferResult>
    ) : TransferState()
    data class Error(val message: String, val hasPartialResults: Boolean) : TransferState()
}
```

### BrowseState
```kotlin
data class BrowseState(
    val currentPath: String,
    val breadcrumbs: List<BreadcrumbItem>,
    val files: List<FileItem>,
    val sortType: SortType,
    val viewType: ViewType,
    val isLoading: Boolean,
    val isEmpty: Boolean,
    val storageInfo: StorageInfo?
)

data class BreadcrumbItem(
    val name: String,
    val path: String
)
```


## 7.3 Event Contracts

### Selection Events (Activity -> Manager)
```kotlin
sealed class SelectionEvent {
    data class ToggleFile(val fileItem: FileItem) : SelectionEvent()
    data class ToggleFolder(val fileItem: FileItem, val scope: CoroutineScope) : SelectionEvent()
    data class SelectAll(val items: List<FileItem>, val scope: CoroutineScope) : SelectionEvent()
    data class DeselectAll(val directory: File?) : SelectionEvent()
    object ClearAll : SelectionEvent()
    object CancelSelection : SelectionEvent()
}
```

### UI Update Events (Manager -> Activity)
```kotlin
sealed class SelectionUIEvent {
    data class SelectionChanged(val state: SelectionState) : SelectionUIEvent()
    object SelectionStarted : SelectionUIEvent()
    data class SelectionProgress(val scannedFiles: Int) : SelectionUIEvent()
    data class SelectionComplete(val totalFiles: Int) : SelectionUIEvent()
}
```

### Transfer Events
```kotlin
sealed class TransferEvent {
    object Start : TransferEvent()
    object Cancel : TransferEvent()
    object Retry : TransferEvent()
    object SaveLog : TransferEvent()
    object Reset : TransferEvent()
}

sealed class TransferUIEvent {
    data class StateChanged(val state: TransferState) : TransferUIEvent()
}
```

---

# 8. REDESIGN GUIDELINES

## 8.1 Safe UI Replacement Rules

### DO NOT MODIFY
1. **SelectionManager.kt** - Core selection logic
2. **FileItem.kt** - Data model
3. **FileScanner.kt** - File system operations
4. **MediaScanner.kt** - MediaStore queries
5. **TransferService.kt** - WiFi transfer service
6. **AdbTransferService.kt** - ADB transfer service
7. **DirectPushClient.kt** - Direct push client
8. **NetworkUtils.kt** - Network detection
9. **HotspotManager.kt** - Hotspot management
10. **BackupManager.kt** - Backup operations

### CAN SAFELY REPLACE
1. All layout XML files
2. All drawable resources
3. All color/style resources
4. UI-related code in Activities (but preserve business logic calls)
5. Adapter implementations (maintain data contracts)

### MUST PRESERVE (Interface Points)
```kotlin
// Activity -> SelectionManager
SelectionManager.toggleSelection(fileItem, scope)
SelectionManager.selectAllInList(items, scope)
SelectionManager.deselectAll()
SelectionManager.cancelSelection()
SelectionManager.isSelected(fileItem)
SelectionManager.getSelectedCount()
SelectionManager.getSelectionSummary()
SelectionManager.hasSelection()

// Activity -> Services
TransferService.Callback.onStatusChanged(status)
TransferService.Callback.onProgressChanged(progress, speed, eta, file, index, total)
TransferService.Callback.onHotspotReady(address, qrBitmap)

// Activity -> Utilities
FileScanner.listFilesAsync(path, sortType, callback)
MediaScanner.getFilesByCategory(category, sortType)
ViewTypeManager.cycleViewType(context)
ThemeManager.toggleTheme(activity)
```


## 8.2 Recommended Redesign Approach

### Phase 1: Theme & Colors
- Update `colors.xml` and `themes.xml`
- No code changes required

### Phase 2: Component Styling
- Update drawable shapes and selectors
- Update item layouts
- Maintain view IDs for data binding

### Phase 3: Screen Layouts
- Replace activity layouts
- Keep same view IDs OR update binding references
- Maintain interaction patterns

### Phase 4: Navigation & Animation
- Add transitions
- Update navigation patterns
- Preserve Intent extras and data passing


## 8.3 Testing Checklist After UI Changes

| Feature | Test Case |
|---------|-----------|
| Mode Selection | All 3 modes selectable, Continue navigates correctly |
| Theme Toggle | Theme persists, all screens respect theme |
| File Browse | Navigation works, breadcrumbs update, back works |
| Selection | Single file, folder (async), select all, deselect |
| Selection Cancel | Cancel mid-selection restores previous state |
| Category Browse | All 6 categories load, selection works |
| Search | Live search, filters, selection works |
| Selection Editor | Tabs filter correctly, deselection works |
| Transfer WiFi | Server starts, QR shows, progress updates, complete |
| Transfer ADB | Service starts, progress updates, complete |
| Transfer Direct | Client connects, progress updates, complete |
| Error Handling | Network error shows, retry works |
| Backup Mode | Full backup scans correctly, transfers with prefixes |

---

# APPENDIX: Quick Reference

## File Locations
```
DeCloud/app/src/main/
├── java/com/decloud/
│   ├── ui/                    <- Activities (7)
│   │   └── adapter/           <- Adapters (7)
│   ├── model/                 <- FileItem, SelectionManager
│   ├── service/               <- TransferService, AdbTransferService, DirectPushClient
│   ├── server/                <- DirectStreamServer, ZipStreamServer
│   ├── hotspot/               <- HotspotManager
│   ├── receiver/              <- AdbCommandReceiver
│   └── util/                  <- Utilities (9 files)
└── res/
    ├── layout/                <- Layouts (19)
    ├── drawable/              <- Icons & shapes (56)
    ├── values/                <- colors, strings, themes
    └── values-night/          <- Dark theme colors
```

## Key Constants
```kotlin
// Transfer modes
ModeSelectionActivity.MODE_WIFI = "wifi"
ModeSelectionActivity.MODE_ADB = "adb"
ModeSelectionActivity.MODE_DIRECT_PUSH = "direct_push"

// Intent extras
ModeSelectionActivity.EXTRA_TRANSFER_MODE
BrowseActivity.EXTRA_STORAGE_INDEX
CategoryActivity.EXTRA_CATEGORY

// View types
ViewType.LIST, ViewType.GRID

// Sort types
SortType.NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC

// Categories
MediaScanner.Category.IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS, APPLICATIONS
```

---

*Document generated for DeCloud v3.0*
*This abstraction layer enables complete UI redesign without breaking business logic.*
