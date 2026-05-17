<div align="center">

<img src="DeCloud-Desktop/assets/icon.png" alt="DeCloud" width="120" />

# DeCloud

### **Transfer. <span style="color:#39FF14">Protect.</span> Repeat.**

Move files between your Android phone and your Windows PC over local WiFi or USB.
**No cloud. No accounts. No tracking. No internet required.**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows-lightgrey.svg)]()

</div>

---

## Why DeCloud?

Your phone has 50,000 photos. To move them to your PC, you have three bad options:

1. **Upload to Google Drive / OneDrive / iCloud** — your data ends up on someone else's server.
2. **Email them to yourself** — limited to a few MB at a time.
3. **Use SHAREit / AirDroid / similar** — those apps run ads, request alarming permissions, and have repeatedly been caught siphoning user data.

**DeCloud is option 4.** Files travel directly from your phone to your PC over your home WiFi or a USB cable. Nothing leaves your local network. No company — including me — ever sees your files.

If you doubt that promise, **read the source.** The whole reason this repository exists is so you don't have to take my word for it.

---

## Features

- 📁 **Transfer any file or folder** over WiFi or USB / ADB
- 🔍 **Global search** across all storage (internal + SD card) with type, size, and date filters
- 🖼️ **Quick categories** — Images, Videos, Audio, Documents, Downloads, Apps
- 👤 **Contacts export** to standard VCF
- 📊 **Real-time progress** with byte-accurate percentage (not file-count based)
- 🌗 **Dark / Light themes** with smooth circular-reveal transition
- 🔌 **Three transfer modes** — WiFi hotspot, shared WiFi, USB / ADB
- 🆓 **Zero ads, zero accounts, zero internet**

---

## Install

| Where | Status |
|---|---|
| **Google Play Store** | *coming soon* |
| **GitHub Releases** (sideload APK) | [Latest release](../../releases/latest) |
| **F-Droid** | *coming soon* |

The desktop companion is bundled in the [GitHub Releases](../../releases/latest) as a portable `.exe` (no installer required).

---

## Build from source

### Android app

```bash
cd DeCloud-Android
./gradlew installDebug
```

Requirements: Android Studio, JDK 11, Android SDK 34+.

### Desktop app (Electron)

```bash
cd DeCloud-Desktop
npm install
npm start            # dev mode
npm run build:portable   # build standalone .exe
```

Requirements: Node.js 18+.

---

## Privacy

DeCloud never sends your data anywhere. Period.
Full policy: [PRIVACY.md](./PRIVACY.md).

If you find a bug that affects user privacy, please follow the responsible disclosure process in [SECURITY.md](./SECURITY.md).

---

## Support the creator

DeCloud is built and maintained by one person in their spare time, with no company behind it. There's no donation page — I just want the project to be useful.

If DeCloud saved you time or trouble, the things that genuinely help:

- ⭐ **Star this repository** — costs nothing, helps a lot with visibility
- 🐛 **Report bugs** or suggest features via [Issues](../../issues)
- 💬 **Tell someone** who's struggling with phone-to-PC transfers
- 📧 **Drop me a line** — I read every email: **adityachaudhary703@gmail.com**

---

## Contributing

Bug reports and pull requests are welcome. See [CONTRIBUTING.md](./CONTRIBUTING.md) for guidelines.

---

## License

DeCloud is licensed under the [Apache License 2.0](./LICENSE).

You're free to use, modify, and redistribute it — including in commercial products — provided you keep the copyright notice and follow the terms in [NOTICE](./NOTICE).

---

## Author

Built with care by **Aditya Chaudhary** — [IIT Roorkee](https://iitr.ac.in).
Reach me: **adityachaudhary703@gmail.com**

If DeCloud helped you, a star on this repo ⭐ goes a long way. Thanks for reading.
