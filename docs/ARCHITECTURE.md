# DeCloud Architecture

How a file actually gets from your phone to your PC, end to end. Three views below: the simple journey, the protocol-level sequence, and the component map.

Every diagram uses the same concrete example throughout: **1,745 image files totaling 3.3 GB**, exactly what the walkthrough video demonstrates.

---

## 1. The user journey (both modes, side by side)

```mermaid
flowchart LR
    User([👤 User])
    User --> Pick[Pick files<br/>or 'Full Backup']
    Pick --> Mode{Choose<br/>transfer mode}

    Mode -->|Wi-Fi mode| W1[Phone creates hotspot<br/>or uses shared Wi-Fi]
    Mode -->|USB mode| U1[Plug USB cable<br/>USB debugging on]

    W1 --> W2[Phone boots an HTTP server<br/>on port 8080]
    W2 --> W3[Phone broadcasts its<br/>identity over UDP 8081]
    W3 --> W4[Desktop auto-scans<br/>and locates the phone]
    W4 --> W5[Phone streams all<br/>selected files as one<br/>ZIP over HTTP]

    U1 --> U2[Desktop opens an ADB<br/>session to the phone]
    U2 --> U3[ADB forwards a TCP<br/>socket to the same<br/>HTTP server]
    U3 --> U4[Phone streams the<br/>files as one ZIP over<br/>the forwarded socket]

    W5 --> Done([✅ Files saved to chosen folder<br/>1,745 files in 8.14 s])
    U4 --> Done
```

**Reading it:** the only difference between Wi-Fi mode and USB mode is *how the network reaches the phone's HTTP server*. The phone is always the server. The PC is always the client. The transfer itself is identical at the application layer.

---

## 2. What happens behind the scenes (Wi-Fi mode, second by second)

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant P as 📱 Phone (DeCloud App)
    participant N as 🌐 Local Network
    participant D as 💻 PC (DeCloud Desktop)

    U->>P: Select 1,745 images (3.3 GB) and tap Start Transfer
    P->>P: Acquire WakeLock + start Foreground Service
    P->>P: Boot NanoHTTPD on port 8080
    P->>P: Start UDP broadcaster on port 8081

    loop every 1 second
        P-)N: UDP broadcast: "DECLOUD|192.168.43.1|8080|Pixel-7"
    end

    U->>D: Open DeCloud Desktop, click Auto-Scan
    D->>N: Listen on UDP port 8081
    N-->>D: Receive broadcast packet
    D->>D: Parse "DECLOUD|..." prefix, extract IP and port
    D-->>U: Phone appears in UI as "Pixel-7"

    U->>D: Choose destination folder, click Start Transfer
    D->>P: GET /info (HTTP)
    P-->>D: JSON: { fileCount: 1745, totalBytes: 3,452,000,000 }

    D->>P: GET /download (HTTP)
    P->>P: Wrap selected files in ZipOutputStream<br/>pipe directly to the response socket

    loop streaming
        P-->>D: ZIP chunk
        D->>D: Unzip on the fly, write to disk
        D-->>U: Update progress bar (byte-accurate)
    end

    P-->>D: End of stream
    D-->>U: ✅ 1,745 files transferred in 8.14 s
    P->>P: Tear down server + release WakeLock
```

**Key technical detail:** step 16. The phone never writes a ZIP to its own storage. It opens a `ZipOutputStream` that writes *directly into the HTTP response socket*. The PC receives bytes and unzips them as they arrive. No temporary files, no double storage. This is why bulk transfers don't depend on the phone having enough free space to hold a temporary archive.

---

## 3. What happens behind the scenes (USB / ADB mode)

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant P as 📱 Phone (DeCloud App)
    participant ADB as 🔌 ADB Bridge (USB)
    participant D as 💻 PC (DeCloud Desktop)

    U->>P: Enable USB debugging once in Developer Options
    U->>D: Plug USB cable
    D->>ADB: adb devices
    ADB-->>D: List of attached devices
    D-->>U: Phone shows up in the device dropdown

    U->>D: Pick files on phone via DeCloud UI<br/>click Start Transfer

    D->>ADB: adb forward tcp:8080 → device tcp:8080
    D->>ADB: adb shell am broadcast<br/>com.decloud.START_TRANSFER
    ADB->>P: Broadcast received by AdbCommandReceiver
    P->>P: Boot the same NanoHTTPD on port 8080
    P->>P: Start AdbTransferService (foreground)

    D->>P: GET http://127.0.0.1:8080/info<br/>(routed through adb forward)
    P-->>D: JSON: { fileCount: 1745, totalBytes: 3,452,000,000 }

    D->>P: GET http://127.0.0.1:8080/download
    P->>P: Same ZipOutputStream piped to socket

    loop streaming over USB
        P-->>D: ZIP chunk (via ADB-forwarded TCP)
        D->>D: Unzip on the fly, write to disk
    end

    P-->>D: End of stream
    D-->>U: ✅ 1,745 files transferred
    D->>ADB: adb forward --remove tcp:8080
```

**Key technical detail:** step 8. `adb forward tcp:8080` makes the phone's `localhost:8080` reachable from the PC as if it were the PC's own `localhost:8080`. The HTTP transfer code is **literally the same** as Wi-Fi mode. Only the transport changed.

---

## 4. Component map (what runs where)

```mermaid
flowchart TB
    subgraph Phone["📱 Android (Kotlin)"]
        direction TB
        UI[UI Layer<br/>BrowseActivity, ReadyToSend, etc.]
        SM[SelectionManager<br/>singleton, tracks chosen files]
        TS[TransferService<br/>Foreground Service + WakeLock]
        HTTP[ZipStreamServer<br/>NanoHTTPD on port 8080]
        UDP[DiscoveryBroadcaster<br/>UDP on port 8081]
        Hotspot[HotspotManager<br/>toggles tethering]
        ADBR[AdbCommandReceiver<br/>listens for shell broadcasts]
    end

    subgraph PC["💻 Windows (Electron)"]
        direction TB
        Renderer[Renderer Process<br/>HTML/CSS/JS UI]
        Main[Main Process<br/>Node.js]
        UDPListener[UDP Discovery Listener<br/>port 8081]
        ADBClient[ADB subprocess wrapper<br/>spawns bundled adb.exe]
        HTTPClient[HTTP Client<br/>streams + on-the-fly unzip]
        ResAdb[resources/adb/<br/>bundled adb.exe + DLLs]
    end

    UI --> SM
    SM --> TS
    TS --> HTTP
    TS --> UDP
    TS --> Hotspot

    Renderer --> Main
    Main --> UDPListener
    Main --> ADBClient
    Main --> HTTPClient
    ADBClient --> ResAdb

    UDP -.->|"DECLOUD|IP|PORT|NAME"| UDPListener
    HTTPClient -.->|HTTP over Wi-Fi or<br/>HTTP over adb forward| HTTP
    ADBClient -.->|adb broadcast| ADBR
```

**Reading it:** the entire app is two single-process programs talking over standard protocols. No cloud services. No backend. No third-party broker. Two devices, two processes, three protocols (UDP for discovery, HTTP for transfer, ADB as a transport when Wi-Fi isn't available).

---

## 5. The single architectural choice that makes everything fast

Most file transfer apps treat each file as a separate request. For 1,745 files, that means 1,745 TCP setups, 1,745 protocol round trips. The Windows MTP driver does roughly this and that's why Windows estimates 6+ hours for the same files.

DeCloud does *one* request. The phone builds a ZIP stream in memory (no temp file) and writes it directly into the HTTP response socket. The PC reads one continuous stream and unzips on the fly.

```mermaid
flowchart LR
    subgraph MTP[Conventional approach - MTP, AirDroid, etc.]
        direction TB
        F1[File 1] -->|setup+transfer+teardown| H1[Round trip 1]
        F2[File 2] -->|setup+transfer+teardown| H2[Round trip 2]
        F3[File 3] -->|setup+transfer+teardown| H3[Round trip 3]
        FN[...1,745 files...] --> HN[1,745 round trips]
    end

    subgraph DeCloud[DeCloud]
        direction TB
        All[All 1,745 files] -->|ZipOutputStream<br/>piped to socket| One[1 round trip]
    end
```

**Result:** 8.14 seconds instead of 6+ hours, on the same hardware moving the same bytes.

---

## File map (where to find this in the code)

| Component | Path in the repo |
|---|---|
| HTTP server + ZIP streaming | `DeCloud-Android/app/src/main/java/com/decloud/server/ZipStreamServer.kt` |
| UDP discovery broadcaster | `DeCloud-Android/app/src/main/java/com/decloud/util/DiscoveryBroadcaster.kt` |
| Foreground transfer service | `DeCloud-Android/app/src/main/java/com/decloud/service/TransferService.kt` |
| ADB command receiver | `DeCloud-Android/app/src/main/java/com/decloud/receiver/AdbCommandReceiver.kt` |
| Hotspot manager | `DeCloud-Android/app/src/main/java/com/decloud/hotspot/HotspotManager.kt` |
| Desktop UDP listener + HTTP client + ADB wrapper | `DeCloud-Desktop/src/main.js` |
| Bundled ADB binaries | `DeCloud-Desktop/resources/adb/` |

Read the source. The promise of "nothing leaves your local network" is verifiable in about an hour of reading.
