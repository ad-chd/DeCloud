# DeCloud Desktop (Electron)

Professional desktop app for transferring files from Android to PC.

## Features
- **WiFi Mode**: Transfer over hotspot (auto-discovery)
- **ADB Mode**: Transfer over USB cable
- **Device Selection**: Handles multiple connected devices
- **Structure Preservation**: Maintains folder hierarchy
- **Modern UI**: Dark theme, progress tracking

## Build Instructions

### Requirements
- [Node.js](https://nodejs.org/) (LTS version recommended)

### Build Portable EXE
```bash
# Double-click build.bat
# OR run manually:
npm install
npm run build:portable
```

Output: `dist/DeCloud-Portable.exe`

### Test During Development
```bash
npm start
# OR double-click start.bat
```

## Usage

### WiFi Mode
1. On phone: Open DeCloud, select files, tap Start
2. Connect PC to phone's WiFi hotspot
3. In PC app: Click "Auto-Scan" to find phone
4. Click "Start Transfer"

### ADB Mode
1. Enable USB Debugging on phone
2. Connect via USB cable
3. In PC app: Click "Refresh" to detect device
4. Select your device from dropdown
5. Click "Start Transfer"
6. On phone: Start the ADB transfer

## Troubleshooting

**"Phone not found"**
- Make sure PC is connected to phone's WiFi hotspot
- Ensure phone app is running and ready

**"No ADB devices"**
- Enable USB debugging in Developer Options
- Try a different USB cable
- Install ADB drivers for your phone

**"More than one device"**
- Select the correct device from the dropdown
