BUNDLED ADB FOR DECLOUD
=============================

To bundle ADB with DeCloud, download the Android Platform Tools and copy the required files here.

DOWNLOAD:
---------
https://developer.android.com/tools/releases/platform-tools

Direct link (Windows):
https://dl.google.com/android/repository/platform-tools-latest-windows.zip

REQUIRED FILES:
---------------
Copy these 3 files from the downloaded platform-tools folder to this directory:

1. adb.exe           (Main ADB executable)
2. AdbWinApi.dll     (Required Windows API library)
3. AdbWinUsbApi.dll  (Required Windows USB library)

FOLDER STRUCTURE:
-----------------
resources/
  adb/
    adb.exe
    AdbWinApi.dll
    AdbWinUsbApi.dll
    README.txt (this file)

NOTES:
------
- All 3 files are required for ADB to work on Windows
- These files are from Google's official Android SDK
- The platform-tools package is ~10MB, but only these 3 files (~3MB) are needed
- ADB is backward and forward compatible, so any recent version works fine
