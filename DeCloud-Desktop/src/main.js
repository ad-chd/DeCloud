const { app, BrowserWindow, ipcMain, dialog, shell, Menu } = require('electron');
const path = require('path');
const { spawn, exec, execSync } = require('child_process');
const fs = require('fs');
const http = require('http');
const https = require('https');
const net = require('net');
const os = require('os');

// HTTP keep-alive agent - reuses TCP connections for reliability and speed
let keepAliveAgent = createHttpAgent();

function createHttpAgent() {
    return new http.Agent({
        keepAlive: true,
        keepAliveMsecs: 10000,
        maxSockets: 48,
        maxFreeSockets: 24,
        timeout: 60000
    });
}

/**
 * Hard-kill all active HTTP connections and create a fresh agent.
 * Every in-flight download gets ECONNRESET instantly.
 */
function destroyAllConnections() {
    keepAliveAgent.destroy();
    keepAliveAgent = createHttpAgent();
}

// Configuration - Optimized for large file counts (200,000+ files)
const CONFIG = {
    DEFAULT_PORT: 64666,
    ADB_PORT: 5555,
    PARALLEL_DOWNLOADS: 24,
    CONNECTION_TIMEOUT: 60000,     // 60 seconds for large manifests
    HEARTBEAT_INTERVAL: 10000,     // 10 seconds (less frequent for large transfers)
    HEARTBEAT_TIMEOUT: 5000,       // 5 seconds timeout
    CANCEL_RETRY_COUNT: 3,
    CANCEL_RETRY_DELAY: 200,
    MAX_MANIFEST_SIZE: 200 * 1024 * 1024,  // 200MB for 200,000+ files
    PROGRESS_THROTTLE: 500,        // Update progress every 500ms (not every file)
    PROGRESS_NOTIFY_INTERVAL: 500, // Notify phone every 500 files (reduces HTTP overhead)
    // Batch transfer settings
    BATCH_MODE_THRESHOLD: 5000,    // Use batch mode if more than 5000 files
    BATCH_SIZE: 1000,              // Files per batch (must match server)
    // Connection reliability settings
    CONNECT_RETRY_COUNT: 3,        // Retry initial connection 3 times
    CONNECT_RETRY_DELAY: 2000,     // 2 seconds between retries
    FILE_RETRY_COUNT: 2,           // Retry each failed file download
    FILE_RETRY_DELAY: 1000         // 1 second between file retries
};

// ==================== TAR BUNDLING FOR SMALL FILES ====================
const TAR_SMALL_FILE_THRESHOLD = 1 * 1024 * 1024;  // 1MB — files below this get tar-bundled
const TAR_BUNDLE_SIZE = 500;                         // files per tar bundle
const TAR_HEADER_SIZE = 512;
const TAR_MIN_FILES = 20;                            // minimum small files to use tar bundling

// ==================== PATH SAFETY FOR WINDOWS MAX_PATH ====================
// Windows MAX_PATH is 260 characters. Truncate filenames to prevent ENOENT errors.
const MAX_PATH = 250; // Leave margin for safety

/**
 * Middle-truncate a string to maxLen, keeping both start and end visible.
 * "VeryLongFileNameHere" with maxLen=15 → "VeryLo~meHere"
 */
function middleTruncate(name, maxLen) {
    if (name.length <= maxLen) return name;
    const sep = '~';
    const available = maxLen - sep.length;
    if (available < 2) return name.substring(0, maxLen);
    const frontLen = Math.ceil(available / 2);
    const backLen = available - frontLen;
    return name.substring(0, frontLen) + sep + name.substring(name.length - backLen);
}

/**
 * Ensure a destination file path doesn't exceed Windows MAX_PATH limit.
 * Middle-truncates the base name (preserving extension and both ends of the name).
 * "C:\...\SomeLongName.png" → "C:\...\SomeLo~Name.png" — extension always safe.
 */
function safeDestPath(destPath) {
    if (destPath.length <= MAX_PATH) return destPath;

    const dir = path.dirname(destPath);
    const ext = path.extname(destPath);
    const base = path.basename(destPath, ext);

    // Calculate max allowed base name length
    // dir + separator + base + ext must be <= MAX_PATH
    const maxBaseLen = MAX_PATH - dir.length - 1 - ext.length;

    if (maxBaseLen < 10) {
        // Directory path itself is too long — flatten to destination root + filename
        const flatDir = dir.split(path.sep).slice(0, 4).join(path.sep);
        const flatMaxBase = Math.max(20, MAX_PATH - ext.length - flatDir.length - 1);
        return path.join(flatDir, middleTruncate(base, flatMaxBase) + ext);
    }

    const truncated = path.join(dir, middleTruncate(base, maxBaseLen) + ext);
    console.log(`Path truncated: ${destPath.length} -> ${truncated.length} chars`);
    return truncated;
}

/**
 * Sanitize a relative path for Windows filesystem.
 * Replaces characters illegal in Windows filenames: < > : " | ? *
 * Applied per path segment to preserve directory structure.
 */
function sanitizeRelPath(relPath) {
    return relPath.split('/').map(segment =>
        segment.replace(/[<>:"|?*]/g, '_')
    ).join('/');
}

// ADB Management - Path differs between dev and production
function getBundledADBPath() {
    if (app.isPackaged) {
        // Production: extraResources are in resources/adb/
        return path.join(process.resourcesPath, 'adb', 'adb.exe');
    } else {
        // Development: resources folder in project root
        return path.join(__dirname, '..', 'resources', 'adb', 'adb.exe');
    }
}
let SETTINGS_FILE = null; // Set after app is ready
let cachedADBPath = null; // Cache detected ADB path

let mainWindow;

// ==================== ISOLATED STATE PER TRANSFER MODE ====================
// Each mode has completely independent state - no interference between modes

const wifiState = {
    inProgress: false,
    cancelRequested: false,
    sessionId: 0,          // Incremented on each new transfer — prevents stale cleanup from corrupting new transfers
    connection: null,      // { ip, port }
    heartbeatInterval: null,
    phoneUnreachable: false,
    lastActivityAt: 0      // Timestamp of last data received — heartbeat checks this, not /info pings
};

const adbState = {
    inProgress: false,
    cancelRequested: false,
    socket: null,
    isAborted: false,
    activeChildren: new Map(),
    server: null,
    device: null
};

/**
 * Get state object for a specific mode
 */
function getStateForMode(mode) {
    switch (mode) {
        case 'wifi': return wifiState;
        case 'adb': return adbState;
        default: return null;
    }
}

/**
 * Check if ANY transfer is in progress (for UI feedback)
 */
function isAnyTransferInProgress() {
    return wifiState.inProgress || adbState.inProgress;
}

/**
 * Get which mode is currently transferring (or null if none)
 */
function getActiveTransferMode() {
    if (wifiState.inProgress) return 'wifi';
    if (adbState.inProgress) return 'adb';
    return null;
}

// ==================== ADB DETECTION SYSTEM ====================

/**
 * Initialize settings file path (call after app ready)
 */
function initSettingsPath() {
    SETTINGS_FILE = path.join(app.getPath('userData'), 'settings.json');
}

/**
 * Load settings from file
 */
function loadSettings() {
    try {
        if (SETTINGS_FILE && fs.existsSync(SETTINGS_FILE)) {
            return JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'));
        }
    } catch (e) {
        console.log('Error loading settings:', e.message);
    }
    return {};
}

/**
 * Save settings to file
 */
function saveSettings(settings) {
    try {
        if (SETTINGS_FILE) {
            fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2));
        }
    } catch (e) {
        console.log('Error saving settings:', e.message);
    }
}

/**
 * Get user-configured ADB path from settings
 */
function getUserConfiguredADB() {
    const settings = loadSettings();
    if (settings.adbPath && fs.existsSync(settings.adbPath)) {
        return settings.adbPath;
    }
    return null;
}

/**
 * Save user-configured ADB path
 */
function setUserADBPath(adbPath) {
    const settings = loadSettings();
    settings.adbPath = adbPath;
    saveSettings(settings);
    cachedADBPath = null; // Clear cache to re-detect
}

/**
 * Find ADB in system PATH using 'where' command
 */
function findADBInPath() {
    try {
        const result = execSync('where adb 2>nul', {
            encoding: 'utf8',
            timeout: 5000,
            windowsHide: true
        });
        const adbPath = result.trim().split('\n')[0].trim();
        if (adbPath && fs.existsSync(adbPath)) {
            return adbPath;
        }
    } catch (e) {}

    // Fallback: PowerShell Get-Command
    try {
        const result = execSync(
            'powershell -Command "(Get-Command adb -ErrorAction SilentlyContinue).Source"',
            { encoding: 'utf8', timeout: 5000, windowsHide: true }
        );
        const adbPath = result.trim();
        if (adbPath && fs.existsSync(adbPath)) {
            return adbPath;
        }
    } catch (e) {}

    return null;
}

/**
 * Find ADB from running process
 */
function findRunningADB() {
    try {
        const result = execSync(
            'wmic process where "name=\'adb.exe\'" get ExecutablePath /value 2>nul',
            { encoding: 'utf8', timeout: 5000, windowsHide: true }
        );
        const match = result.match(/ExecutablePath=(.+\.exe)/i);
        if (match) {
            const adbPath = match[1].trim();
            if (fs.existsSync(adbPath)) {
                return adbPath;
            }
        }
    } catch (e) {}
    return null;
}

/**
 * Find ADB - No assumptions, proper detection
 * Priority: User Config > ANDROID_HOME > PATH > Running Process > Bundled
 */
function findADB() {
    // Return cached path if available
    if (cachedADBPath && fs.existsSync(cachedADBPath)) {
        return { path: cachedADBPath, source: 'cached' };
    }

    console.log('=== ADB DETECTION ===');

    // Priority 1: User configured path (settings)
    const userPath = getUserConfiguredADB();
    if (userPath) {
        console.log('Priority 1 - User configured:', userPath);
        cachedADBPath = userPath;
        return { path: userPath, source: 'user-configured' };
    }

    // Priority 2: ANDROID_HOME / ANDROID_SDK_ROOT environment variable
    const androidHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
    if (androidHome) {
        const adbPath = path.join(androidHome, 'platform-tools', 'adb.exe');
        if (fs.existsSync(adbPath)) {
            console.log('Priority 2 - ANDROID_HOME:', adbPath);
            cachedADBPath = adbPath;
            return { path: adbPath, source: 'android-home' };
        }
    }

    // Priority 3: System PATH
    const pathADB = findADBInPath();
    if (pathADB) {
        console.log('Priority 3 - System PATH:', pathADB);
        cachedADBPath = pathADB;
        return { path: pathADB, source: 'system-path' };
    }

    // Priority 4: Running ADB process
    const runningADB = findRunningADB();
    if (runningADB) {
        console.log('Priority 4 - Running process:', runningADB);
        cachedADBPath = runningADB;
        return { path: runningADB, source: 'running-process' };
    }

    // Priority 5: Bundled ADB (fallback)
    const bundledADB = getBundledADBPath();
    if (fs.existsSync(bundledADB)) {
        console.log('Priority 5 - Bundled:', bundledADB);
        cachedADBPath = bundledADB;
        return { path: bundledADB, source: 'bundled' };
    }

    console.log('No ADB found!');
    return { path: null, source: 'none' };
}

/**
 * Verify ADB works and get version
 */
function verifyADB(adbPath) {
    try {
        const result = execSync(`"${adbPath}" version`, {
            encoding: 'utf8',
            timeout: 5000,
            windowsHide: true
        });
        const match = result.match(/(\d+\.\d+\.\d+)/);
        return {
            works: true,
            version: match ? match[1] : 'unknown'
        };
    } catch (e) {
        return { works: false, error: e.message };
    }
}

/**
 * Get ADB path for use in commands
 */
function getADBPath() {
    const adb = findADB();
    if (adb.path) {
        return `"${adb.path}"`;
    }
    return 'adb'; // Fallback to system command
}

/**
 * Get full ADB status for UI
 */
function getADBStatus() {
    const adb = findADB();

    if (!adb.path) {
        return {
            available: false,
            path: null,
            source: 'none',
            version: null,
            error: 'ADB not found. Install Android SDK or set path manually.'
        };
    }

    const verify = verifyADB(adb.path);

    return {
        available: verify.works,
        path: adb.path,
        source: adb.source,
        version: verify.version || null,
        error: verify.works ? null : verify.error
    };
}

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 700,
        height: 800,
        resizable: false,
        maximizable: false,
        fullscreenable: false,
        show: false,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            devTools: false
        },
        icon: path.join(__dirname, '../assets/icon.png'),
        title: 'DeCloud',
        backgroundColor: '#000000',
        autoHideMenuBar: true
    });

    mainWindow.once('ready-to-show', () => {
        mainWindow.show();
    });

    // Remove the application menu entirely
    mainWindow.setMenu(null);
    mainWindow.setMenuBarVisibility(false);

    mainWindow.loadFile(path.join(__dirname, 'index.html'));

    // Block all keyboard shortcuts that open DevTools or reload
    mainWindow.webContents.on('before-input-event', (event, input) => {
        // Block F12, Ctrl+Shift+I, Ctrl+Shift+J, Ctrl+Shift+C (DevTools)
        if (input.key === 'F12') {
            event.preventDefault();
        }
        if (input.control && input.shift && (input.key === 'I' || input.key === 'i' ||
            input.key === 'J' || input.key === 'j' ||
            input.key === 'C' || input.key === 'c')) {
            event.preventDefault();
        }
        // Block Ctrl+R / Ctrl+Shift+R (reload)
        if (input.control && (input.key === 'R' || input.key === 'r')) {
            event.preventDefault();
        }
        // Block Ctrl+U (view source)
        if (input.control && (input.key === 'U' || input.key === 'u')) {
            event.preventDefault();
        }
    });

    // Block right-click context menu
    mainWindow.webContents.on('context-menu', (event) => {
        event.preventDefault();
    });

    // Prevent navigation to external URLs
    mainWindow.webContents.on('will-navigate', (event) => {
        event.preventDefault();
    });

    // Block new window creation
    mainWindow.webContents.setWindowOpenHandler(() => {
        return { action: 'deny' };
    });
}

app.whenReady().then(() => {
    initSettingsPath();

    // Remove application menu globally — no File/Edit/View/Help menus
    Menu.setApplicationMenu(null);

    createWindow();

    // Log ADB status on startup
    const adbStatus = getADBStatus();
    console.log('=== ADB STATUS ON STARTUP ===');
    console.log('Available:', adbStatus.available);
    console.log('Path:', adbStatus.path);
    console.log('Source:', adbStatus.source);
    console.log('Version:', adbStatus.version);
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
    }
});

// ==================== IPC Handlers ====================

// ==================== ADB Settings Handlers ====================

ipcMain.handle('get-adb-status', () => {
    return getADBStatus();
});

ipcMain.handle('browse-adb', async () => {
    const result = await dialog.showOpenDialog(mainWindow || BrowserWindow.getFocusedWindow(), {
        title: 'Select adb.exe',
        filters: [{ name: 'ADB Executable', extensions: ['exe'] }],
        properties: ['openFile']
    });

    if (result.filePaths[0]) {
        const adbPath = result.filePaths[0];

        // Verify it's actually ADB
        const verify = verifyADB(adbPath);
        if (verify.works) {
            setUserADBPath(adbPath);
            cachedADBPath = null; // Clear cache
            return {
                success: true,
                path: adbPath,
                version: verify.version,
                source: 'user-configured'
            };
        } else {
            return {
                success: false,
                error: 'Selected file is not a valid ADB executable: ' + verify.error
            };
        }
    }
    return { success: false, error: 'No file selected' };
});

ipcMain.handle('reset-adb-path', () => {
    setUserADBPath(null);
    cachedADBPath = null; // Clear cache to re-detect
    return getADBStatus();
});

ipcMain.handle('refresh-adb-status', () => {
    cachedADBPath = null; // Clear cache to force re-detection
    return getADBStatus();
});

// ==================== Send to Phone Handlers (PC → Phone) ====================

/**
 * Select files to send to phone
 */
ipcMain.handle('select-files-to-send', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: 'Select Files to Send to Phone',
        properties: ['openFile', 'multiSelections'],
        filters: [
            { name: 'All Files', extensions: ['*'] }
        ]
    });

    if (result.canceled || result.filePaths.length === 0) {
        return { success: false, files: [] };
    }

    const files = result.filePaths.map(filePath => {
        const stats = fs.statSync(filePath);
        return {
            path: filePath,
            name: path.basename(filePath),
            size: stats.size,
            isFolder: false
        };
    });

    return { success: true, files };
});

/**
 * Select folder to send to phone
 */
ipcMain.handle('select-folder-to-send', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: 'Select Folder to Send to Phone',
        properties: ['openDirectory']
    });

    if (result.canceled || result.filePaths.length === 0) {
        return { success: false, folder: null };
    }

    const folderPath = result.filePaths[0];

    // Calculate folder size and file count
    let totalSize = 0;
    let fileCount = 0;

    function calculateFolder(dirPath) {
        try {
            const entries = fs.readdirSync(dirPath, { withFileTypes: true });
            for (const entry of entries) {
                const entryPath = path.join(dirPath, entry.name);
                if (entry.isDirectory()) {
                    calculateFolder(entryPath);
                } else if (entry.isFile()) {
                    try {
                        const stats = fs.statSync(entryPath);
                        totalSize += stats.size;
                        fileCount++;
                    } catch (e) {}
                }
            }
        } catch (e) {}
    }

    calculateFolder(folderPath);

    return {
        success: true,
        folder: {
            path: folderPath,
            name: path.basename(folderPath),
            size: totalSize,
            fileCount,
            isFolder: true
        }
    };
});

/**
 * Resolve dropped file/folder paths into the standard file info format
 */
ipcMain.handle('resolve-dropped-paths', async (event, droppedPaths) => {
    const items = [];
    for (const itemPath of droppedPaths) {
        try {
            const stats = fs.statSync(itemPath);
            if (stats.isDirectory()) {
                let totalSize = 0;
                let fileCount = 0;
                function calcDir(dirPath) {
                    try {
                        const entries = fs.readdirSync(dirPath, { withFileTypes: true });
                        for (const entry of entries) {
                            const ep = path.join(dirPath, entry.name);
                            if (entry.isDirectory()) calcDir(ep);
                            else if (entry.isFile()) {
                                try { totalSize += fs.statSync(ep).size; fileCount++; } catch (e) {}
                            }
                        }
                    } catch (e) {}
                }
                calcDir(itemPath);
                items.push({ path: itemPath, name: path.basename(itemPath), size: totalSize, fileCount, isFolder: true });
            } else {
                items.push({ path: itemPath, name: path.basename(itemPath), size: stats.size, isFolder: false });
            }
        } catch (e) {}
    }
    return { success: true, items };
});

/**
 * Send files to phone via ADB push
 */
ipcMain.handle('send-to-phone-adb', async (event, { device, files }) => {
    const adbPath = getADBPath();
    if (!adbPath) {
        return { success: false, error: 'ADB not found' };
    }

    const startTime = Date.now();
    // /storage/emulated/0 = Internal Storage (same as /sdcard/)
    const phoneDestination = '/storage/emulated/0/DeCloud';
    let completed = 0;
    let failed = [];
    // Precompute total bytes (f.size is supplied by the file-picker for both files and folders).
    const totalSize = (files || []).reduce((sum, f) => sum + (f.size || 0), 0);
    let bytesTransferred = 0;

    // Helper to send ADB broadcast to phone (fire-and-forget)
    const sendPhoneBroadcast = (action, extras = '') => {
        try {
            const cmd = `"${adbPath}" -s "${device}" shell am broadcast -a com.decloud.${action} ${extras}`;
            exec(cmd, { windowsHide: true, timeout: 5000 });
        } catch (_) {}
    };

    try {
        // First, create destination folder on phone
        await execPromise(`"${adbPath}" -s "${device}" shell mkdir -p "${phoneDestination}"`);

        // Notify phone that USB receive is starting
        sendPhoneBroadcast('USB_RECEIVE_START', `--ei total_files ${files.length}`);

        // Push each file/folder
        for (let i = 0; i < files.length; i++) {
            const item = files[i];

            if (adbState.cancelRequested) {
                break;
            }

            mainWindow.webContents.send('transfer-progress', {
                completed,
                total: files.length,
                bytesTransferred,
                totalBytes: totalSize,
                currentFile: item.name
            });

            try {
                // Use adb push (works for both files and folders)
                const destPath = `${phoneDestination}/${item.name}`;
                const cmd = `"${adbPath}" -s "${device}" push "${item.path}" "${destPath}"`;

                console.log(`Pushing: ${item.path} -> ${destPath}`);
                await execPromise(cmd);
                completed++;
                bytesTransferred += (item.size || 0);

                mainWindow.webContents.send('transfer-progress', {
                    completed,
                    total: files.length,
                    bytesTransferred,
                    totalBytes: totalSize,
                    currentFile: item.name
                });

                // Notify phone about this file (sanitize filename for ADB shell)
                const safeName = item.name.replace(/[^\w.\-_ ]/g, '_').substring(0, 200);
                sendPhoneBroadcast('USB_FILE_RECEIVED', `--es file_name "${safeName}" --ei index ${completed} --ei total ${files.length}`);

            } catch (e) {
                console.error(`Failed to push ${item.name}: ${e.message}`);
                failed.push({ name: item.name, error: e.message });
            }
        }

        // Send final 100% progress
        mainWindow.webContents.send('transfer-progress', {
            completed,
            total: files.length,
            bytesTransferred,
            totalBytes: totalSize,
            currentFile: completed >= files.length ? 'All files sent' : `${completed} of ${files.length} done`
        });

        // Notify phone that transfer is done
        sendPhoneBroadcast('USB_RECEIVE_DONE', `--ei total_files ${completed} --ei failed ${failed.length}`);

        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

        if (completed === files.length) {
            return {
                success: true,
                completed,
                elapsed,
                message: `All ${completed} items pushed to phone`
            };
        } else {
            return {
                success: false,
                error: `${failed.length} items failed`,
                completed,
                failed: failed.length,
                elapsed
            };
        }

    } catch (e) {
        return { success: false, error: e.message };
    }
});

/**
 * Send files to phone via WiFi (HTTP upload)
 */
ipcMain.handle('send-to-phone-wifi', async (event, { ip, port, files }) => {
    const usePort = port || CONFIG.DEFAULT_PORT;
    const startTime = Date.now();
    let completed = 0;
    let failed = [];
    let totalBytesTransferred = 0;

    try {
        // First, check if phone supports upload
        let serverInfo;
        try {
            serverInfo = await httpGet(`http://${ip}:${usePort}/info`, 5000);
        } catch (e) {
            throw new Error(`Cannot connect to phone: ${e.message}`);
        }

        // Initialize upload on phone
        const totalSize = files.reduce((sum, f) => sum + f.size, 0);
        const fileCount = files.reduce((sum, f) => sum + (f.isFolder ? f.fileCount : 1), 0);

        mainWindow.webContents.send('status-update', `Sending ${files.length} items (${formatSize(totalSize)})...`);

        // Process each file/folder
        for (let i = 0; i < files.length; i++) {
            const item = files[i];

            if (wifiState.cancelRequested) {
                break;
            }

            if (item.isFolder) {
                // Upload all files in folder
                const folderFiles = getAllFilesInFolder(item.path);
                for (const filePath of folderFiles) {
                    const relativePath = path.relative(path.dirname(item.path), filePath).replace(/\\/g, '/');

                    mainWindow.webContents.send('transfer-progress', {
                        completed,
                        total: fileCount,
                        bytesTransferred: totalBytesTransferred,
                        totalBytes: totalSize,
                        currentFile: relativePath
                    });

                    try {
                        const bytes = await uploadFile(ip, usePort, filePath, relativePath);
                        totalBytesTransferred += bytes;
                        completed++;
                        mainWindow.webContents.send('transfer-progress', {
                            completed,
                            total: fileCount,
                            bytesTransferred: totalBytesTransferred,
                            totalBytes: totalSize,
                            currentFile: relativePath
                        });
                    } catch (e) {
                        failed.push({ path: relativePath, error: e.message });
                    }
                }
            } else {
                // Upload single file
                mainWindow.webContents.send('transfer-progress', {
                    completed,
                    total: fileCount,
                    bytesTransferred: totalBytesTransferred,
                    totalBytes: totalSize,
                    currentFile: item.name
                });

                try {
                    const bytes = await uploadFile(ip, usePort, item.path, item.name);
                    totalBytesTransferred += bytes;
                    completed++;
                    mainWindow.webContents.send('transfer-progress', {
                        completed,
                        total: fileCount,
                        bytesTransferred: totalBytesTransferred,
                        totalBytes: totalSize,
                        currentFile: item.name
                    });
                } catch (e) {
                    failed.push({ path: item.name, error: e.message });
                }
            }
        }

        // Send final 100% progress
        mainWindow.webContents.send('transfer-progress', {
            completed,
            total: fileCount,
            bytesTransferred: totalBytesTransferred,
            totalBytes: totalSize,
            currentFile: completed >= fileCount ? 'All files sent' : `${completed} of ${fileCount} done`
        });

        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

        if (failed.length === 0) {
            return {
                success: true,
                completed,
                elapsed,
                message: `All ${completed} files sent to phone`
            };
        } else {
            return {
                success: false,
                error: `${failed.length} files failed`,
                completed,
                failed: failed.length,
                elapsed
            };
        }

    } catch (e) {
        return { success: false, error: e.message };
    }
});

/**
 * Get all files in a folder recursively
 */
function getAllFilesInFolder(folderPath) {
    const files = [];

    function walk(dir) {
        try {
            const entries = fs.readdirSync(dir, { withFileTypes: true });
            for (const entry of entries) {
                const fullPath = path.join(dir, entry.name);
                if (entry.isDirectory()) {
                    walk(fullPath);
                } else if (entry.isFile()) {
                    files.push(fullPath);
                }
            }
        } catch (e) {}
    }

    walk(folderPath);
    return files;
}

/**
 * Upload a single file to phone via HTTP POST
 */
function uploadFile(ip, port, filePath, relativePath) {
    return new Promise((resolve, reject) => {
        const fileSize = fs.statSync(filePath).size;
        const fileStream = fs.createReadStream(filePath);

        const options = {
            hostname: ip,
            port: port,
            path: `/upload?path=${encodeURIComponent(relativePath)}`,
            method: 'POST',
            headers: {
                'Content-Type': 'application/octet-stream',
                'Content-Length': fileSize,
                'X-File-Name': encodeURIComponent(path.basename(filePath)),
                'X-Relative-Path': encodeURIComponent(relativePath)
            },
            timeout: 300000  // 5 minute timeout for large files
        };

        const req = http.request(options, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                if (res.statusCode === 200) {
                    resolve(fileSize);
                } else {
                    reject(new Error(`Upload failed: HTTP ${res.statusCode}`));
                }
            });
        });

        req.on('error', reject);
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('Upload timeout'));
        });

        fileStream.on('error', (err) => {
            req.destroy();
            reject(new Error(`File read error: ${err.message}`));
        });

        fileStream.pipe(req);
    });
}

/**
 * Promise wrapper for exec
 */
function execPromise(cmd) {
    return new Promise((resolve, reject) => {
        exec(cmd, { maxBuffer: 50 * 1024 * 1024 }, (error, stdout, stderr) => {
            if (error) {
                reject(new Error(stderr || error.message));
            } else {
                resolve(stdout);
            }
        });
    });
}

// ==================== Other Handlers ====================

ipcMain.handle('browse-folder', async (event, currentPath) => {
    const options = {
        properties: ['openDirectory', 'createDirectory']
    };
    // If a current path is provided, open the dialog there
    if (currentPath && fs.existsSync(currentPath)) {
        options.defaultPath = currentPath;
    }
    const result = await dialog.showOpenDialog(mainWindow || BrowserWindow.getFocusedWindow(), options);
    return result.filePaths[0] || null;
});

ipcMain.handle('get-default-path', () => {
    return path.join(os.homedir(), 'Downloads', 'DeCloud');
});

// ==================== UDP Discovery Listener ====================
const dgram = require('dgram');

/**
 * Listen for UDP discovery broadcasts from phone
 * Phone broadcasts: DECLOUD|IP|PORT|DEVICE_NAME on port 8081 every 1s
 * @param {number} timeoutMs - How long to listen (default 5s = 5 broadcast cycles)
 * @returns {Promise<{success: boolean, ip?: string, port?: number, deviceName?: string}>}
 */
function listenForUDPDiscovery(timeoutMs = 5000) {
    return new Promise((resolve) => {
        let resolved = false;
        let socket;

        const cleanup = () => {
            try { socket?.close(); } catch (e) {}
        };

        const done = (result) => {
            if (resolved) return;
            resolved = true;
            clearTimeout(timer);
            cleanup();
            resolve(result);
        };

        const timer = setTimeout(() => {
            console.log('UDP discovery: timeout after', timeoutMs, 'ms');
            done({ success: false });
        }, timeoutMs);

        try {
            socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

            socket.on('error', (err) => {
                console.log('UDP discovery socket error:', err.message);
                done({ success: false });
            });

            socket.on('message', (msg, rinfo) => {
                const message = msg.toString('utf8').trim();
                console.log(`UDP discovery received: "${message}" from ${rinfo.address}:${rinfo.port}`);

                // Parse: DECLOUD|IP|PORT|DEVICE_NAME
                const parts = message.split('|');
                if (parts.length >= 3 && parts[0] === 'DECLOUD') {
                    let ip = parts[1];
                    const port = parseInt(parts[2], 10);
                    const deviceName = parts[3] || 'Android Device';

                    // Reject invalid IPs (0.0.0.0) — use the actual UDP source IP instead
                    if (!ip || ip === '0.0.0.0' || ip === '127.0.0.1') {
                        console.log(`UDP discovery: broadcast IP "${ip}" is invalid, using source IP ${rinfo.address}`);
                        ip = rinfo.address;
                    }

                    if (ip && ip !== '0.0.0.0' && port > 0) {
                        console.log(`UDP discovery: found phone at ${ip}:${port} (${deviceName})`);
                        done({ success: true, ip, port, deviceName });
                    }
                }
            });

            socket.bind(8081, () => {
                console.log('UDP discovery: listening on port 8081');
            });
        } catch (e) {
            console.log('UDP discovery: failed to create socket:', e.message);
            done({ success: false });
        }
    });
}

ipcMain.handle('scan-phone', async () => {
    console.log('=== AUTO-SCAN STARTED ===');

    // Run UDP listener AND gateway detection in parallel
    // UDP is more reliable (phone broadcasts exact IP+port), gateway is fallback
    const [udpResult, gatewayResult] = await Promise.all([
        listenForUDPDiscovery(5000),
        tryGatewayIP()
    ]);

    // Prefer UDP result (has exact IP + port from the phone itself)
    if (udpResult.success) {
        console.log('SUCCESS: Found phone via UDP broadcast:', udpResult.ip, ':', udpResult.port);
        return {
            success: true,
            ip: udpResult.ip,
            port: udpResult.port,
            deviceName: udpResult.deviceName,
            method: 'udp'
        };
    }

    // Fallback to gateway detection
    if (gatewayResult.success) {
        console.log('SUCCESS: Found phone at gateway IP:', gatewayResult.ip);
        return { ...gatewayResult, method: 'gateway' };
    }

    // Both failed
    console.log('All scan methods failed. Try BT Scan or enter IP manually.');
    return {
        success: false,
        error: 'Phone not found via WiFi. Try "BT Scan" if devices are paired, or enter IP manually.'
    };
});

// Method 1: Gateway IP detection
async function tryGatewayIP() {
    try {
        console.log('Getting gateway IP from ipconfig...');
        const gatewayIP = await getGatewayIP();

        if (!gatewayIP) {
            console.log('No gateway IP found');
            return { success: false };
        }

        console.log('Gateway IP found:', gatewayIP);
        console.log(`Testing DeCloud server at http://${gatewayIP}:${CONFIG.DEFAULT_PORT}/info...`);

        try {
            const info = await httpGet(`http://${gatewayIP}:${CONFIG.DEFAULT_PORT}/info`, 3000);
            console.log('Server response:', JSON.stringify(info));

            if (info && info.status === 'ready') {
                console.log('SUCCESS: Phone server is ready at gateway!');
                return { success: true, ip: gatewayIP, deviceName: info.deviceName || 'Android Device' };
            } else {
                console.log('Server responded but not ready:', info?.status);
            }
        } catch (httpError) {
            console.log(`HTTP check failed for ${gatewayIP}: ${httpError.message}`);
        }
    } catch (e) {
        console.log('Gateway detection error:', e.message);
    }
    return { success: false };
}

// Get default gateway IP - THIS IS THE PHONE when connected to hotspot!
function getGatewayIP() {
    return new Promise(async (resolve) => {
        const { exec } = require('child_process');

        console.log('=== GATEWAY DETECTION STARTED ===');

        // Hotspot patterns - prioritize these
        const hotspotPatterns = [
            '192.168.43.',  // Most Android hotspots
            '192.168.49.',  // Some Android
            '172.20.10.',   // iOS hotspot
            '192.168.137.', // Windows ICS
            '10.42.0.',     // Linux
            '192.168.1.',   // Common router range (fallback)
            '192.168.0.',   // Common router range (fallback)
        ];

        const allGateways = [];

        // Method 1: Use 'route print' command - most reliable for getting default gateway
        try {
            const routeResult = await new Promise((res) => {
                exec('route print 0.0.0.0', { encoding: 'utf8', timeout: 5000 }, (error, stdout) => {
                    if (error) {
                        console.log('route print error:', error.message);
                        res(null);
                        return;
                    }

                    console.log('=== PARSING ROUTE PRINT OUTPUT ===');

                    // Parse route table for default gateway (0.0.0.0 destination)
                    // Format: Network Destination    Netmask          Gateway           Interface        Metric
                    const lines = stdout.split(/\r?\n/);

                    for (const line of lines) {
                        // Look for lines starting with 0.0.0.0 (default route)
                        if (line.trim().startsWith('0.0.0.0')) {
                            // Extract all IPs from this line
                            const ips = line.match(/\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/g);
                            if (ips && ips.length >= 2) {
                                // Gateway is usually the 2nd IP (after 0.0.0.0 netmask)
                                const gateway = ips.find(ip => ip !== '0.0.0.0' && !ip.startsWith('255.'));
                                if (gateway) {
                                    console.log(`Route table gateway: ${gateway}`);
                                    allGateways.push(gateway);
                                }
                            }
                        }
                    }

                    res(allGateways.length > 0 ? allGateways[0] : null);
                });
            });

            if (routeResult) {
                console.log('Found gateway via route command:', routeResult);
            }
        } catch (e) {
            console.log('Route method failed:', e.message);
        }

        // Method 2: Use 'netsh' to get interface info with gateway
        try {
            await new Promise((res) => {
                exec('netsh interface ip show config', { encoding: 'utf8', timeout: 5000 }, (error, stdout) => {
                    if (error) {
                        console.log('netsh error:', error.message);
                        res(null);
                        return;
                    }

                    console.log('=== PARSING NETSH OUTPUT ===');

                    // Look for Default Gateway entries
                    const lines = stdout.split(/\r?\n/);
                    for (const line of lines) {
                        if (line.toLowerCase().includes('default gateway') ||
                            line.toLowerCase().includes('gateway:')) {
                            const ipMatch = line.match(/(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})/);
                            if (ipMatch && ipMatch[1] !== '0.0.0.0') {
                                console.log(`netsh gateway: ${ipMatch[1]}`);
                                if (!allGateways.includes(ipMatch[1])) {
                                    allGateways.push(ipMatch[1]);
                                }
                            }
                        }
                    }
                    res(null);
                });
            });
        } catch (e) {
            console.log('Netsh method failed:', e.message);
        }

        // Method 3: Use ipconfig as fallback
        try {
            await new Promise((res) => {
                exec('ipconfig', { encoding: 'utf8', timeout: 5000 }, (error, stdout) => {
                    if (error) {
                        console.log('ipconfig error:', error.message);
                        res(null);
                        return;
                    }

                    console.log('=== PARSING IPCONFIG OUTPUT ===');

                    const normalizedOutput = stdout.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
                    const lines = normalizedOutput.split('\n');

                    for (let i = 0; i < lines.length; i++) {
                        const line = lines[i];
                        const lineLower = line.toLowerCase();

                        // Look for "Default Gateway" line (multiple language support)
                        if (lineLower.includes('default gateway') ||
                            lineLower.includes('standardgateway') ||
                            lineLower.includes('passerelle par défaut') ||
                            lineLower.includes('gateway predefinito') ||
                            lineLower.includes('puerta de enlace') ||
                            line.includes('默认网关') ||
                            line.includes('預設閘道')) {

                            let ipMatch = line.match(/(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})/);

                            if (!ipMatch && i + 1 < lines.length) {
                                ipMatch = lines[i + 1].match(/^\s*(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})/);
                            }

                            if (ipMatch && ipMatch[1] !== '0.0.0.0') {
                                console.log(`ipconfig gateway: ${ipMatch[1]}`);
                                if (!allGateways.includes(ipMatch[1])) {
                                    allGateways.push(ipMatch[1]);
                                }
                            }
                        }
                    }
                    res(null);
                });
            });
        } catch (e) {
            console.log('ipconfig method failed:', e.message);
        }

        // Method 4: Use Node.js os.networkInterfaces() as additional source
        try {
            const interfaces = os.networkInterfaces();
            console.log('=== CHECKING NETWORK INTERFACES ===');

            for (const [name, addrs] of Object.entries(interfaces)) {
                for (const addr of addrs) {
                    if (addr.family === 'IPv4' && !addr.internal) {
                        // Get the network gateway for this interface
                        // Gateway is typically .1 of the network (common pattern)
                        const parts = addr.address.split('.');
                        if (parts.length === 4) {
                            const likelyGateway = `${parts[0]}.${parts[1]}.${parts[2]}.1`;
                            console.log(`Interface ${name}: IP=${addr.address}, likely gateway=${likelyGateway}`);

                            // For hotspot connections, the gateway is often .1 or .43.1
                            if (!allGateways.includes(likelyGateway)) {
                                // Only add if it looks like a hotspot pattern
                                if (hotspotPatterns.some(p => likelyGateway.startsWith(p))) {
                                    allGateways.push(likelyGateway);
                                }
                            }
                        }
                    }
                }
            }
        } catch (e) {
            console.log('Network interfaces method failed:', e.message);
        }

        console.log(`=== TOTAL GATEWAYS FOUND: ${allGateways.length} ===`, allGateways);

        // Remove duplicates
        const uniqueGateways = [...new Set(allGateways)];

        // Priority 1: Hotspot-like gateways
        const hotspotGateway = uniqueGateways.find(ip =>
            hotspotPatterns.slice(0, 5).some(pattern => ip.startsWith(pattern))  // First 5 are hotspot-specific
        );

        if (hotspotGateway) {
            console.log('SELECTED: Hotspot gateway:', hotspotGateway);
            resolve(hotspotGateway);
            return;
        }

        // Priority 2: Any private IP gateway
        const privateGateway = uniqueGateways.find(ip =>
            ip.startsWith('192.168.') ||
            ip.startsWith('172.') ||
            ip.startsWith('10.')
        );

        if (privateGateway) {
            console.log('SELECTED: Private gateway:', privateGateway);
            resolve(privateGateway);
            return;
        }

        // Priority 3: First gateway found
        if (uniqueGateways.length > 0) {
            console.log('SELECTED: First available gateway:', uniqueGateways[0]);
            resolve(uniqueGateways[0]);
            return;
        }

        console.log('No gateway found by any method');
        resolve(null);
    });
}

// ==================== Bluetooth Discovery (PC Side) ====================

/**
 * Scan for DeCloud phone via Bluetooth RFCOMM.
 * 1. Enumerate paired Bluetooth devices using PowerShell
 * 2. For each paired device, try connecting to the DeCloud RFCOMM UUID
 *    via a TCP socket on the Bluetooth COM port
 *
 * Since Node.js doesn't have native Bluetooth RFCOMM support, we use PowerShell
 * to find paired devices and attempt a serial port connection.
 */
async function scanBluetoothForPhone() {
    console.log('=== BLUETOOTH SCAN STARTED ===');

    try {
        // Use PowerShell to enumerate paired Bluetooth devices
        const psScript = `
            try {
                $devices = Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'OK' -and $_.FriendlyName -ne $null -and $_.FriendlyName -ne '' }
                if ($devices) {
                    $devices | ForEach-Object { Write-Output "$($_.FriendlyName)|$($_.InstanceId)" }
                }
            } catch { }
        `;

        const pairedDevices = await new Promise((resolve) => {
            exec(`powershell -NoProfile -Command "${psScript.replace(/\n/g, ' ')}"`,
                { encoding: 'utf8', timeout: 10000, windowsHide: true },
                (error, stdout) => {
                    if (error) {
                        console.log('PowerShell BT enum error:', error.message);
                        resolve([]);
                        return;
                    }
                    const devices = stdout.trim().split('\n')
                        .map(line => line.trim())
                        .filter(line => line.includes('|'))
                        .map(line => {
                            const parts = line.split('|');
                            return { name: parts[0], instanceId: parts[1] || '' };
                        });
                    resolve(devices);
                }
            );
        });

        console.log(`Found ${pairedDevices.length} paired BT device(s):`, pairedDevices.map(d => d.name));

        if (pairedDevices.length === 0) {
            return {
                success: false,
                error: 'No paired Bluetooth devices found. Pair your phone first in Windows Bluetooth settings.'
            };
        }

        // Try to find DeCloud's Bluetooth RFCOMM service via COM ports
        // Enumerate BT COM ports
        const comPorts = await new Promise((resolve) => {
            exec(
                'powershell -NoProfile -Command "Get-WmiObject Win32_SerialPort | Where-Object { $_.PNPDeviceID -like \'*BTHENUM*\' } | ForEach-Object { Write-Output \\"$($_.DeviceID)|$($_.PNPDeviceID)|$($_.Name)\\" }"',
                { encoding: 'utf8', timeout: 10000, windowsHide: true },
                (error, stdout) => {
                    if (error) {
                        console.log('COM port enum error:', error.message);
                        resolve([]);
                        return;
                    }
                    const ports = stdout.trim().split('\n')
                        .map(line => line.trim())
                        .filter(line => line.includes('|'))
                        .map(line => {
                            const parts = line.split('|');
                            return { port: parts[0], pnpId: parts[1] || '', name: parts[2] || '' };
                        });
                    resolve(ports);
                }
            );
        });

        console.log(`Found ${comPorts.length} BT COM port(s):`, comPorts.map(p => `${p.port} (${p.name})`));

        // Try reading from each BT COM port to see if DeCloud responds
        const targetUuid = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

        for (const comPort of comPorts) {
            try {
                console.log(`Trying BT COM port: ${comPort.port} (${comPort.name})`);

                const data = await new Promise((resolve, reject) => {
                    const timer = setTimeout(() => reject(new Error('timeout')), 3000);

                    // Use PowerShell to open serial port and read data
                    const readCmd = `powershell -NoProfile -Command "try { $port = New-Object System.IO.Ports.SerialPort('${comPort.port}', 9600); $port.ReadTimeout = 2000; $port.Open(); $line = $port.ReadLine(); $port.Close(); Write-Output $line } catch { Write-Error $_.Exception.Message }"`;

                    exec(readCmd, { encoding: 'utf8', timeout: 5000, windowsHide: true }, (error, stdout) => {
                        clearTimeout(timer);
                        if (error) {
                            reject(error);
                        } else {
                            resolve(stdout.trim());
                        }
                    });
                });

                // Check if this is a DeCloud response
                if (data.startsWith('DECLOUD|')) {
                    const parts = data.split('|');
                    const ip = parts[1];
                    const port = parseInt(parts[2], 10);
                    const deviceName = parts[3] || comPort.name;

                    console.log(`SUCCESS: Found DeCloud via BT! ${ip}:${port} (${deviceName})`);
                    return {
                        success: true,
                        ip,
                        port,
                        deviceName,
                        method: 'bluetooth',
                        btDevice: comPort.name
                    };
                }
            } catch (e) {
                console.log(`COM port ${comPort.port} failed: ${e.message}`);
            }
        }

        // COM port approach didn't work, return device list so user knows devices are paired
        return {
            success: false,
            error: `Found ${pairedDevices.length} paired device(s) but couldn't connect to DeCloud service. Make sure the phone app is running and transfer is started.`,
            pairedDevices: pairedDevices.map(d => d.name)
        };

    } catch (e) {
        console.log('Bluetooth scan error:', e.message);
        return {
            success: false,
            error: `Bluetooth scan failed: ${e.message}`
        };
    }
}

ipcMain.handle('scan-phone-bluetooth', async () => {
    return await scanBluetoothForPhone();
});

ipcMain.handle('get-adb-devices', async () => {
    // Get detected ADB path
    const adbPath = getADBPath();
    const adbStatus = getADBStatus();

    if (!adbStatus.available) {
        return {
            success: false,
            error: adbStatus.error || 'ADB not available',
            adbStatus
        };
    }

    try {
        const output = execSync(`"${adbPath}" devices`, { encoding: 'utf-8', timeout: 10000, windowsHide: true });
        const lines = output.split('\n').slice(1);
        const devices = [];

        for (const line of lines) {
            if (line.includes('\tdevice')) {
                const serial = line.split('\t')[0];
                let model = serial;
                try {
                    model = execSync(`"${adbPath}" -s "${serial}" shell getprop ro.product.model`,
                        { encoding: 'utf-8', timeout: 5000, windowsHide: true }).trim() || serial;
                } catch (e) {}
                devices.push({ serial, model });
            }
        }
        return { success: true, devices, adbStatus };
    } catch (e) {
        return { success: false, error: e.message, adbStatus };
    }
});

ipcMain.handle('check-phone', async (event, ip) => {
    try {
        const info = await httpGetWithRetry(`http://${ip}:${CONFIG.DEFAULT_PORT}/info`, 10000, 2);
        return { success: true, info };
    } catch (e) {
        return { success: false, error: e.message };
    }
});

// Manual connection with custom port
ipcMain.handle('check-phone-with-port', async (event, { ip, port }) => {
    try {
        const usePort = port || CONFIG.DEFAULT_PORT;
        const info = await httpGetWithRetry(`http://${ip}:${usePort}/info`, 10000, 2);
        return { success: true, info };
    } catch (e) {
        return { success: false, error: e.message };
    }
});

ipcMain.handle('start-wifi-transfer', async (event, { ip, destination, port }) => {
    const usePort = port || CONFIG.DEFAULT_PORT;

    // Check if THIS mode already has a transfer in progress
    if (wifiState.inProgress) {
        return { success: false, error: 'WiFi transfer already in progress' };
    }

    // Reset WiFi state for new transfer
    wifiState.inProgress = true;
    wifiState.cancelRequested = false;
    wifiState.sessionId++;
    const mySession = wifiState.sessionId; // Capture — used to prevent stale cleanup
    wifiState.connection = { ip, port: usePort };
    wifiState.phoneUnreachable = false;
    wifiState.lastActivityAt = Date.now();

    // NOTE: Heartbeat starts AFTER first successful connection (see below)

    try {
        fs.mkdirSync(destination, { recursive: true });

        sendProgress('Connecting to phone...');

        // First, check file count and get session hash - WITH RETRY for reliability
        let info;
        try {
            info = await httpGetWithRetry(`http://${ip}:${usePort}/info`, CONFIG.CONNECTION_TIMEOUT);
            console.log('Info response:', info);
        } catch (e) {
            throw new Error(`Failed to connect to phone: ${e.message}`);
        }

        // Connection validated - NOW start heartbeat monitoring
        startWifiHeartbeat(ip, usePort, () => {
            if (wifiState.sessionId !== mySession) return; // Stale heartbeat
            wifiState.phoneUnreachable = true;
            wifiState.cancelRequested = true;
            wifiState.inProgress = false;
            wifiState.connection = null;
            destroyAllConnections();
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('transfer-error', {
                    mode: 'wifi',
                    message: 'Phone connection lost. Transfer aborted.'
                });
            }
        });

        const totalFileCount = info.fileCount || 0;
        const totalSize = info.totalSize || 0;
        const useBatchMode = totalFileCount > CONFIG.BATCH_MODE_THRESHOLD;

        console.log(`=== TRANSFER MODE DECISION ===`);
        console.log(`Total files: ${totalFileCount}`);
        console.log(`Batch threshold: ${CONFIG.BATCH_MODE_THRESHOLD}`);
        console.log(`Using batch mode: ${useBatchMode}`);

        if (totalFileCount === 0) {
            throw new Error('No files to transfer. Select files on the phone app first.');
        }

        // ==================== BATCH MODE FOR LARGE FILE COUNTS ====================
        if (useBatchMode) {
            return await performBatchTransfer(ip, usePort, destination, totalFileCount, mySession, totalSize);
        }

        // ==================== NORMAL MODE FOR SMALLER FILE COUNTS ====================
        sendProgress('Fetching file list...');

        // Fetch manifest with retry for reliability
        let manifest;
        try {
            manifest = await httpGetWithRetry(`http://${ip}:${usePort}/manifest`, CONFIG.CONNECTION_TIMEOUT);
            console.log('Manifest received:', JSON.stringify(manifest, null, 2).substring(0, 500));
        } catch (e) {
            throw new Error(`Failed to get manifest: ${e.message}`);
        }

        // Validate manifest structure
        if (!manifest) {
            throw new Error('Empty manifest received from phone');
        }

        const files = manifest.files || [];
        const directories = manifest.directories || [];
        const totalFiles = files.length;
        const skippedOnPhone = manifest.skippedCount || 0;
        const originalCount = manifest.originalCount || totalFiles;

        console.log(`=== MANIFEST VERIFICATION ===`);
        console.log(`Original selection on phone: ${originalCount}`);
        console.log(`Files in manifest: ${totalFiles}`);
        console.log(`Skipped on phone (inaccessible): ${skippedOnPhone}`);
        console.log(`Directories: ${directories.length}`);

        // Warn if files were skipped on phone
        if (skippedOnPhone > 0) {
            console.warn(`WARNING: ${skippedOnPhone} files were skipped on phone (not accessible)`);
            sendProgress(`${skippedOnPhone} files couldn't be accessed`);
        }

        if (totalFiles === 0) {
            throw new Error('No files to transfer. Select files on the phone app first.');
        }

        sendProgress(`Preparing to transfer ${totalFiles} files...`);

        // ==================== STRUCTURE PRESERVATION (EXACT SAME AS ADB MODE) ====================
        // Use directoryMapping and pathMapping from manifest - same as ADB pull mode

        // Get directoryMapping and pathMapping from manifest (same as ADB mode)
        const directoryMapping = manifest.directoryMapping || {};
        const pathMapping = manifest.pathMapping || {};

        console.log('=== STRUCTURE INFO FROM MANIFEST ===');
        console.log('directoryMapping entries:', Object.keys(directoryMapping).length);
        console.log('pathMapping entries:', Object.keys(pathMapping).length);
        console.log('directories array:', directories.length);

        // Step 1: Create directories from directoryMapping (SAME AS ADB MODE)
        for (const [absPath, relPath] of Object.entries(directoryMapping)) {
            if (relPath) {
                const fullPath = path.join(destination, relPath.replace(/\\/g, '/'));
                fs.mkdirSync(fullPath, { recursive: true });
            }
        }

        // Also create directories from the directories array (if provided)
        for (const dir of directories) {
            const cleanDir = dir.replace(/\\/g, '/').replace(/^\/+/, '');
            if (cleanDir && cleanDir !== '.' && cleanDir !== '/') {
                const fullPath = path.join(destination, cleanDir);
                fs.mkdirSync(fullPath, { recursive: true });
            }
        }

        let completed = 0;
        let failed = [];
        const startTime = Date.now();
        let bytesTransferred = 0;
        let lastProgressUpdate = 0;      // Throttle UI updates
        let lastPhoneNotify = 0;          // Throttle phone notifications

        // Mark transfer start for data-flow heartbeat
        wifiState.lastActivityAt = Date.now();

        const totalToProcess = files.length;
        const processedFiles = new Set();
        const successfulFiles = new Set();

        // ==================== HYBRID WORK QUEUE (tar bundles + individual files) ====================
        // Separate small files for tar bundling vs large files for individual download
        // Tar header name field is 100 bytes — files with basename > 95 chars MUST use individual
        // download to avoid name truncation and extension loss in tar headers.
        const smallFileIndices = [];
        const largeFileIndices = [];
        files.forEach((file, index) => {
            const relPath = pathMapping[(file.path || '').replace(/\\/g, '/')] || file.relativePath || '';
            const baseName = path.basename(relPath);
            if ((file.size || 0) < TAR_SMALL_FILE_THRESHOLD && baseName.length <= 95) {
                smallFileIndices.push(index);
            } else {
                largeFileIndices.push(index);
            }
        });

        const useTarBundles = smallFileIndices.length >= TAR_MIN_FILES;

        // Build unified work queue: tar bundles first (continuous flow), then individual large files
        const workQueue = [];
        if (useTarBundles) {
            console.log(`Tar bundling: ${smallFileIndices.length} small files into ${Math.ceil(smallFileIndices.length / TAR_BUNDLE_SIZE)} bundles, ${largeFileIndices.length} large files individual`);
            for (let i = 0; i < smallFileIndices.length; i += TAR_BUNDLE_SIZE) {
                workQueue.push({ type: 'tar', indices: smallFileIndices.slice(i, i + TAR_BUNDLE_SIZE) });
            }
            largeFileIndices.forEach(index => workQueue.push({ type: 'file', index }));
        } else {
            files.forEach((_, index) => workQueue.push({ type: 'file', index }));
        }

        let currentWorkIndex = 0;

        const getNextWork = () => {
            if (currentWorkIndex >= workQueue.length) return null;
            return workQueue[currentWorkIndex++];
        };

        const downloadNext = async () => {
            while (!wifiState.cancelRequested) {
                const work = getNextWork();
                if (!work) break;

                if (work.type === 'tar') {
                    // Build path→index map for tracking
                    const indexByPath = new Map();
                    work.indices.forEach(i => {
                        const file = files[i];
                        indexByPath.set(file.relativePath || '', i);
                    });

                    try {
                        const bundleExtracted = new Set();
                        await downloadTarBundle(ip, usePort, work.indices, destination,
                            (sanitizedPath, rawPath, bytes) => {
                                let idx = indexByPath.get(rawPath);
                                // Fallback: if tar header truncated the name, match by prefix
                                if (idx === undefined && rawPath.length >= 95) {
                                    for (const [relPath, i] of indexByPath) {
                                        if (relPath.startsWith(rawPath) && !bundleExtracted.has(i)) {
                                            idx = i;
                                            break;
                                        }
                                    }
                                }
                                if (idx !== undefined) {
                                    processedFiles.add(idx);
                                    successfulFiles.add(idx);
                                    bundleExtracted.add(idx);
                                    completed++;
                                } else {
                                    console.warn(`TAR: extracted file with unmatched path: "${rawPath}"`);
                                }
                                bytesTransferred += bytes;

                                const now = Date.now();
                                if (now - lastProgressUpdate > CONFIG.PROGRESS_THROTTLE) {
                                    lastProgressUpdate = now;
                                    mainWindow.webContents.send('transfer-progress', {
                                        completed,
                                        total: totalFiles,
                                        bytesTransferred,
                                        totalBytes: totalSize,
                                        currentFile: sanitizedPath
                                    });
                                }

                                if (completed - lastPhoneNotify >= CONFIG.PROGRESS_NOTIFY_INTERVAL) {
                                    notifyPhoneProgress(ip, usePort, completed, totalFiles, sanitizedPath);
                                    lastPhoneNotify = completed;
                                }
                            }
                        );

                        // After successful bundle: detect files the server skipped (inaccessible on phone)
                        // or files where path lookup failed (not matched in indexByPath)
                        const skippedInBundle = work.indices.filter(i => !processedFiles.has(i));
                        if (skippedInBundle.length > 0) {
                            console.warn(`TAR BUNDLE: ${skippedInBundle.length} of ${work.indices.length} files were not extracted (skipped by server or path mismatch)`);
                            for (const i of skippedInBundle) {
                                processedFiles.add(i);
                                failed.push({ index: i, path: files[i]?.relativePath || `index_${i}`, error: 'Skipped by phone (file inaccessible or path mismatch)' });
                            }
                        }
                    } catch (e) {
                        // Bundle failed — mark all unprocessed indices as failed
                        console.error(`TAR BUNDLE FAILED (${work.indices.length} files): ${e.message}`);
                        work.indices.forEach(i => {
                            if (!processedFiles.has(i)) {
                                processedFiles.add(i);
                                failed.push({ index: i, path: files[i]?.relativePath || `index_${i}`, error: e.message });
                                completed++;
                            }
                        });

                        const isNetErr = e.message.includes('ECONNRESET') || e.message.includes('ECONNREFUSED') ||
                                         e.message.includes('EHOSTUNREACH') || e.message.includes('ETIMEDOUT') ||
                                         e.message.includes('socket hang up') || e.message.includes('timeout');
                        if (isNetErr) await handleNetworkFailure(ip, usePort);
                    }
                } else {
                    // Individual file download (unchanged logic)
                    const index = work.index;
                    processedFiles.add(index);
                    const file = files[index];

                    const sourcePath = (file.path || '').replace(/\\/g, '/');
                    const relPath = sanitizeRelPath((pathMapping[sourcePath] || file.relativePath || path.basename(sourcePath)).replace(/\\/g, '/').replace(/^\/+/, ''));
                    const destPath = safeDestPath(path.join(destination, relPath));

                    try {
                        fs.mkdirSync(path.dirname(destPath), { recursive: true });
                        const bytes = await downloadFileByIndex(ip, usePort, index, destPath);
                        bytesTransferred += bytes;
                        completed++;
                        successfulFiles.add(index);

                        const now = Date.now();
                        const shouldUpdateUI = (now - lastProgressUpdate > CONFIG.PROGRESS_THROTTLE) ||
                                              (completed % Math.max(1, Math.floor(totalFiles / 100)) === 0);

                        if (shouldUpdateUI) {
                            lastProgressUpdate = now;
                            mainWindow.webContents.send('transfer-progress', {
                                completed,
                                total: totalFiles,
                                bytesTransferred,
                                totalBytes: totalSize,
                                currentFile: relPath
                            });
                        }

                        const shouldNotifyPhone = (completed - lastPhoneNotify >= CONFIG.PROGRESS_NOTIFY_INTERVAL) ||
                                                 (completed === totalFiles);

                        if (shouldNotifyPhone) {
                            lastPhoneNotify = completed;
                            notifyPhoneProgress(ip, usePort, completed, totalFiles, relPath);
                        }

                    } catch (e) {
                        console.error(`FAILED [${index}] ${relPath}: ${e.message}`);
                        failed.push({ index, path: relPath, size: file.size || 0, error: e.message });

                        // Network error → health check the phone. If dead, abort everything.
                        const isNetErr = e.message.includes('ECONNRESET') || e.message.includes('ECONNREFUSED') ||
                                         e.message.includes('EHOSTUNREACH') || e.message.includes('ETIMEDOUT') ||
                                         e.message.includes('socket hang up') || e.message.includes('timeout');
                        if (isNetErr) await handleNetworkFailure(ip, usePort);

                        mainWindow.webContents.send('transfer-progress', {
                            completed,
                            total: totalFiles,
                            bytesTransferred,
                            totalBytes: totalSize,
                            currentFile: `FAILED: ${relPath}`
                        });
                    }
                }
            }
        };

        const workers = [];
        for (let i = 0; i < CONFIG.PARALLEL_DOWNLOADS; i++) {
            workers.push(downloadNext());
        }

        await Promise.all(workers);

        // ==================== RETRY: Fresh bundle for failed files ====================
        // Failed files were likely transiently locked (media scanner, etc.).
        // Retry failed files individually via direct /file?index=N (no tar — bypasses any tar-specific issues)
        if (failed.length > 0 && failed.length <= 500 && !wifiState.cancelRequested) {
            console.log(`\n=== RETRY: ${failed.length} failed files via direct download ===`);
            sendProgress(`Retrying ${failed.length} failed files individually...`);

            // Wait 1s to let transient locks release (media scanner, etc.)
            await new Promise(r => setTimeout(r, 1000));

            const retryList = [...failed];
            failed = [];

            for (let ri = 0; ri < retryList.length; ri++) {
                const item = retryList[ri];
                if (wifiState.cancelRequested) { failed.push(item); continue; }
                try {
                    const file = files[item.index];
                    if (!file) { failed.push(item); continue; }

                    const sourcePath = (file.path || '').replace(/\\/g, '/');
                    const relPath = sanitizeRelPath((pathMapping[sourcePath] || file.relativePath || path.basename(sourcePath)).replace(/\\/g, '/'));
                    const destPath = safeDestPath(path.join(destination, relPath));
                    fs.mkdirSync(path.dirname(destPath), { recursive: true });

                    mainWindow.webContents.send('transfer-progress', {
                        completed,
                        total: totalFiles,
                        bytesTransferred,
                        totalBytes: totalSize,
                        currentFile: `Retry ${ri + 1}/${retryList.length}: ${relPath}`
                    });

                    const bytes = await downloadFileByIndex(ip, usePort, item.index, destPath, 15000);
                    bytesTransferred += bytes;
                    completed++;
                    successfulFiles.add(item.index);
                    console.log(`Retry OK [${ri + 1}/${retryList.length}]: ${relPath}`);
                } catch (e) {
                    console.error(`Retry FAILED [${ri + 1}/${retryList.length}]: ${item.path}: ${e.message}`);
                    failed.push({ ...item, error: `Retry failed: ${e.message}` });
                }
            }
            console.log(`Retry complete: ${retryList.length - failed.length} recovered, ${failed.length} still failed`);
        }

        // ==================== LAST RESORT: /retry-file via MediaStore search ====================
        // If files STILL fail after /file?index=N retry, the File API can't read them at all.
        // Use /retry-file which searches MediaStore by filename and streams via ContentResolver.
        if (failed.length > 0 && failed.length <= 100 && !wifiState.cancelRequested) {
            console.log(`\n=== LAST RESORT: ${failed.length} files via MediaStore /retry-file ===`);
            sendProgress(`Recovering ${failed.length} files via MediaStore...`);

            await new Promise(r => setTimeout(r, 500));

            const mediaRetryList = [...failed];
            failed = [];

            for (let ri = 0; ri < mediaRetryList.length; ri++) {
                const item = mediaRetryList[ri];
                if (wifiState.cancelRequested) { failed.push(item); continue; }
                try {
                    const file = files[item.index];
                    if (!file) { failed.push(item); continue; }

                    const sourcePath = (file.path || '').replace(/\\/g, '/');
                    const relPath = sanitizeRelPath((pathMapping[sourcePath] || file.relativePath || path.basename(sourcePath)).replace(/\\/g, '/'));
                    const destPath = safeDestPath(path.join(destination, relPath));
                    fs.mkdirSync(path.dirname(destPath), { recursive: true });

                    // Extract just the filename for MediaStore search
                    const fileName = path.basename(sourcePath || relPath);
                    const fileSize = file.size || 0;

                    mainWindow.webContents.send('transfer-progress', {
                        completed,
                        total: totalFiles,
                        bytesTransferred,
                        totalBytes: totalSize,
                        currentFile: `MediaStore recovery ${ri + 1}/${mediaRetryList.length}: ${fileName}`
                    });

                    // Call /retry-file — phone searches MediaStore and streams via ContentResolver
                    const encodedName = encodeURIComponent(fileName);
                    const retryUrl = `http://${ip}:${usePort}/retry-file?name=${encodedName}&size=${fileSize}`;
                    console.log(`MediaStore retry: ${retryUrl}`);

                    const bytes = await new Promise((resolve, reject) => {
                        const fileStream = fs.createWriteStream(destPath);
                        let received = 0;

                        const req = http.get(retryUrl, { timeout: 30000 }, (res) => {
                            if (res.statusCode !== 200) {
                                fileStream.close();
                                try { fs.unlinkSync(destPath); } catch (_) {}
                                reject(new Error(`HTTP ${res.statusCode}: ${res.statusMessage}`));
                                return;
                            }
                            res.on('data', (chunk) => {
                                received += chunk.length;
                            });
                            res.on('error', (e) => {
                                fileStream.close();
                                try { fs.unlinkSync(destPath); } catch (_) {}
                                reject(e);
                            });
                            res.pipe(fileStream);
                            fileStream.on('finish', () => {
                                fileStream.close();
                                resolve(received);
                            });
                        });
                        req.on('error', (e) => {
                            fileStream.close();
                            try { fs.unlinkSync(destPath); } catch (_) {}
                            reject(e);
                        });
                        req.on('timeout', () => {
                            req.destroy();
                            fileStream.close();
                            try { fs.unlinkSync(destPath); } catch (_) {}
                            reject(new Error('Timeout'));
                        });
                    });

                    bytesTransferred += bytes;
                    completed++;
                    successfulFiles.add(item.index);
                    processedFiles.add(item.index);
                    console.log(`MediaStore retry OK [${ri + 1}/${mediaRetryList.length}]: ${fileName} (${bytes} bytes)`);
                } catch (e) {
                    console.error(`MediaStore retry FAILED [${ri + 1}/${mediaRetryList.length}]: ${item.path}: ${e.message}`);
                    failed.push({ ...item, error: `All retry methods failed: ${e.message}` });
                }
            }
            console.log(`MediaStore retry complete: ${mediaRetryList.length - failed.length} recovered, ${failed.length} still failed`);
        }

        // ==================== FINAL VERIFICATION & INVARIANT ENFORCEMENT ====================
        // INVARIANT: completed + failed.length MUST equal totalFiles. No file can vanish.

        // Step 1: Find any files that were never processed by any worker
        const unprocessedCount = totalToProcess - processedFiles.size;
        if (unprocessedCount > 0) {
            console.error(`VERIFICATION: ${unprocessedCount} files were never processed — adding to failed`);
            for (let i = 0; i < totalToProcess; i++) {
                if (!processedFiles.has(i)) {
                    const file = files[i];
                    const sourcePath = (file.path || '').replace(/\\/g, '/');
                    const relPath = pathMapping[sourcePath] || file.relativePath || path.basename(sourcePath);
                    console.error(`  Missed file ${i}: ${relPath} (size: ${file.size || 0})`);
                    failed.push({ path: relPath, error: 'File was never processed by any worker', index: i });
                    processedFiles.add(i);
                }
            }
        }

        // Step 2: Enforce the invariant — catch any remaining gap
        const accountedFor = completed + failed.length;
        if (accountedFor !== totalFiles) {
            const gap = totalFiles - accountedFor;
            console.error(`INVARIANT VIOLATION: completed(${completed}) + failed(${failed.length}) = ${accountedFor} != totalFiles(${totalFiles}). Gap: ${gap}`);
            // This should never happen after the above verification, but safety net
            if (gap > 0) {
                for (let i = 0; i < totalToProcess; i++) {
                    if (!successfulFiles.has(i) && !failed.find(f => f.index === i)) {
                        failed.push({ path: files[i]?.relativePath || `index_${i}`, error: 'Unaccounted file (invariant fix)', index: i });
                        if (completed + failed.length >= totalFiles) break;
                    }
                }
            }
        }

        console.log(`=== TRANSFER VERIFICATION ===`);
        console.log(`Original selection on phone: ${originalCount}`);
        console.log(`Skipped on phone (inaccessible): ${skippedOnPhone}`);
        console.log(`Total in manifest: ${totalFiles}`);
        console.log(`Successfully downloaded: ${completed} (successfulFiles: ${successfulFiles.size})`);
        console.log(`Failed on PC: ${failed.length}`);
        console.log(`Accounted for: ${completed + failed.length} (must equal ${totalFiles})`);
        console.log(`processedFiles.size: ${processedFiles.size}`);

        // Total losses
        const totalLost = skippedOnPhone + failed.length;
        if (totalLost > 0) {
            console.warn(`TOTAL FILES NOT TRANSFERRED: ${totalLost} (${skippedOnPhone} phone-side + ${failed.length} PC-side)`);
        }

        // Send final progress update
        mainWindow.webContents.send('transfer-progress', {
            completed: completed + failed.length, // Show total processed (success+fail) as final count
            total: totalFiles,
            bytesTransferred,
            totalBytes: totalSize,
            currentFile: failed.length === 0 ? 'All files transferred' : `${completed} succeeded, ${failed.length} failed`
        });

        // Only clean up state if we're still the active session
        // (if a new transfer started, don't touch state — it belongs to them now)
        const isStale = wifiState.sessionId !== mySession;
        if (!isStale) {
            stopWifiHeartbeat();
            wifiState.inProgress = false;
            wifiState.connection = null;
        }

        // If a new transfer replaced us, just return silently
        if (isStale) {
            console.log('Transfer session superseded by new transfer — exiting silently');
            return { success: false, error: 'Cancelled', stale: true };
        }

        // ==================== BUILD RESULT ====================
        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        const avgSpeed = bytesTransferred > 0 ? formatSize(bytesTransferred / parseFloat(elapsed)) : '0 B';
        const skippedWarning = skippedOnPhone > 0 ? ` (${skippedOnPhone} files were inaccessible on phone)` : '';

        if (wifiState.cancelRequested) {
            if (wifiState.phoneUnreachable) {
                return {
                    success: false,
                    error: `Phone connection lost. ${completed} of ${totalFiles} files transferred.`,
                    completed,
                    failed: failed.length,
                    skippedOnPhone,
                    originalCount,
                    elapsed,
                    avgSpeed
                };
            } else {
                return {
                    success: false,
                    error: 'Cancelled',
                    completed,
                    failed: failed.length
                };
            }
        }

        // Notify phone of completion
        notifyPhoneComplete(ip, usePort, failed.map(f => f.path || ''));

        if (failed.length === 0) {
            // All files transferred successfully
            const message = skippedOnPhone > 0
                ? `${completed} files transferred. Note: ${skippedOnPhone} files couldn't be read on phone.`
                : `All ${completed} files transferred successfully`;
            return {
                success: skippedOnPhone === 0,
                completed,
                failed: 0,
                skippedOnPhone,
                originalCount,
                elapsed,
                avgSpeed,
                message,
                warning: skippedOnPhone > 0 ? `${skippedOnPhone} files were inaccessible on phone` : undefined
            };
        } else if (completed > 0) {
            // Partial success
            const failedNames = failed.slice(0, 5).map(f => `${f.path} (${f.error || 'unknown'})`).join(', ');
            return {
                success: false,
                error: `${failed.length} of ${totalFiles} files failed${skippedWarning}. First: ${failedNames}`,
                completed,
                failed: failed.length,
                skippedOnPhone,
                originalCount,
                elapsed,
                avgSpeed,
                failedFiles: failed.map(f => f.path)
            };
        } else {
            // Complete failure
            const firstError = failed[0]?.error || 'Unknown error';
            return {
                success: false,
                error: `All ${totalFiles} files failed${skippedWarning}. Error: ${firstError}`,
                completed: 0,
                failed: failed.length,
                skippedOnPhone,
                originalCount,
                elapsed,
                avgSpeed,
                failedFiles: failed.map(f => f.path)
            };
        }
    } catch (e) {
        // Only clean up if we're still the active session
        if (wifiState.sessionId === mySession) {
            stopWifiHeartbeat();
            if (wifiState.connection) {
                notifyPhoneError(wifiState.connection.ip, wifiState.connection.port, e.message);
            }
            wifiState.inProgress = false;
            wifiState.connection = null;
        }

        console.error('WiFi transfer error:', e);
        return { success: false, error: e.message };
    }
});

/**
 * ==================== BATCH TRANSFER MODE ====================
 * For handling 200,000+ files without memory overload
 * Files are processed in batches of BATCH_SIZE (1000) files each
 * Only one batch is in memory at a time - prevents memory exhaustion
 */
async function performBatchTransfer(ip, port, destination, totalFileCount, mySession, totalTransferSize = 0) {
    const startTime = Date.now();
    let totalCompleted = 0;
    let totalFailed = [];
    let totalBytesTransferred = 0;
    let skippedOnPhone = 0;
    let originalCount = totalFileCount;
    // Fall back to batchInfo.totalSize if the caller didn't pass it in.
    let totalBytesExpected = totalTransferSize;

    try {
        // Step 1: Get batch info from phone
        sendProgress('Preparing transfer...');
        console.log('=== BATCH TRANSFER MODE ===');

        let batchInfo;
        try {
            batchInfo = await httpGetWithRetry(`http://${ip}:${port}/batch-info`, CONFIG.CONNECTION_TIMEOUT);
            console.log('Batch info received:', JSON.stringify(batchInfo, null, 2));
        } catch (e) {
            throw new Error(`Failed to get batch info: ${e.message}`);
        }

        const totalBatches = batchInfo.batchCount || 1;
        const batchSize = batchInfo.batchSize || CONFIG.BATCH_SIZE;
        const totalFiles = batchInfo.totalFiles || totalFileCount;
        skippedOnPhone = batchInfo.skippedCount || 0;
        originalCount = batchInfo.originalCount || totalFiles;
        if (!totalBytesExpected && batchInfo.totalSize) totalBytesExpected = batchInfo.totalSize;

        console.log(`Total batches: ${totalBatches}`);
        console.log(`Batch size: ${batchSize}`);
        console.log(`Total files: ${totalFiles}`);
        console.log(`Skipped on phone: ${skippedOnPhone}`);

        sendProgress(`Transferring ${totalFiles} files...`);

        // Step 2: Process each batch sequentially (one at a time to prevent memory overload)
        for (let batchNum = 0; batchNum < totalBatches && !wifiState.cancelRequested; batchNum++) {

            console.log(`\n=== PROCESSING BATCH ${batchNum + 1}/${totalBatches} ===`);

            // Update UI with batch progress
            mainWindow.webContents.send('transfer-progress', {
                completed: totalCompleted,
                total: totalFiles,
                bytesTransferred: totalBytesTransferred,
                totalBytes: totalBytesExpected,
                currentFile: ''
            });

            sendProgress(`Downloading files...`);

            // Get batch manifest
            let batchManifest;
            try {
                batchManifest = await httpGetWithRetry(`http://${ip}:${port}/batch/${batchNum}/manifest`, CONFIG.CONNECTION_TIMEOUT, 2);
                console.log(`Batch ${batchNum} manifest: ${batchManifest.batchSize} files`);
            } catch (e) {
                console.error(`Failed to get batch ${batchNum} manifest: ${e.message}`);
                // Mark all files in this batch as failed and continue
                const batchStart = batchNum * batchSize;
                const batchEnd = Math.min(batchStart + batchSize, totalFiles);
                for (let i = batchStart; i < batchEnd; i++) {
                    totalFailed.push({ index: i, path: `Unknown (batch ${batchNum})`, error: 'Batch manifest failed' });
                }
                continue;
            }

            const batchFiles = batchManifest.files || [];
            const pathMapping = batchManifest.pathMapping || {};

            // Create directories from first batch only
            if (batchNum === 0) {
                const directoryMapping = batchManifest.directoryMapping || {};
                const directories = batchManifest.directories || [];

                // Create directories from directoryMapping
                for (const [absPath, relPath] of Object.entries(directoryMapping)) {
                    if (relPath) {
                        const fullPath = path.join(destination, relPath.replace(/\\/g, '/'));
                        fs.mkdirSync(fullPath, { recursive: true });
                    }
                }

                // Create directories from directories array
                for (const dir of directories) {
                    const cleanDir = dir.replace(/\\/g, '/').replace(/^\/+/, '');
                    if (cleanDir && cleanDir !== '.' && cleanDir !== '/') {
                        const fullPath = path.join(destination, cleanDir);
                        fs.mkdirSync(fullPath, { recursive: true });
                    }
                }
            }

            const filesToDownload = batchFiles.length;

            sendProgress(`Downloading ${filesToDownload} files...`);

            // Download files in this batch using parallel workers
            let batchCompleted = 0;
            let batchFailed = [];
            let lastProgressUpdate = 0;
            const batchProcessed = new Set();

            // ==================== HYBRID BATCH WORK QUEUE (tar bundles + individual files) ====================
            const smallGlobalIndices = [];
            const largeBatchEntries = [];

            batchFiles.forEach((file, localIdx) => {
                const globalIdx = file.globalIndex !== undefined ? file.globalIndex : (batchNum * batchSize + localIdx);
                if ((file.size || 0) < TAR_SMALL_FILE_THRESHOLD) {
                    smallGlobalIndices.push({ globalIndex: globalIdx, localIndex: localIdx, file });
                } else {
                    largeBatchEntries.push({ globalIndex: globalIdx, localIndex: localIdx, file });
                }
            });

            const batchWorkQueue = [];
            if (smallGlobalIndices.length >= TAR_MIN_FILES) {
                console.log(`  Batch ${batchNum}: tar bundling ${smallGlobalIndices.length} small files, ${largeBatchEntries.length} large files individual`);
                // Build tar bundles using global indices (tar-bundle endpoint uses full manifest indices)
                for (let i = 0; i < smallGlobalIndices.length; i += TAR_BUNDLE_SIZE) {
                    const chunk = smallGlobalIndices.slice(i, i + TAR_BUNDLE_SIZE);
                    batchWorkQueue.push({
                        type: 'tar',
                        indices: chunk.map(x => x.globalIndex),
                        entries: chunk
                    });
                }
                largeBatchEntries.forEach(entry => batchWorkQueue.push({ type: 'batchFile', ...entry }));
            } else {
                batchFiles.forEach((file, localIdx) => {
                    const globalIdx = file.globalIndex !== undefined ? file.globalIndex : (batchNum * batchSize + localIdx);
                    batchWorkQueue.push({ type: 'batchFile', globalIndex: globalIdx, localIndex: localIdx, file });
                });
            }

            let batchWorkIndex = 0;

            const getNextBatchWork = () => {
                if (batchWorkIndex >= batchWorkQueue.length) return null;
                return batchWorkQueue[batchWorkIndex++];
            };

            const downloadBatchFile = async () => {
                while (!wifiState.cancelRequested) {
                    const work = getNextBatchWork();
                    if (!work) break;

                    if (work.type === 'tar') {
                        // Build path→entry map for tracking
                        const entryByPath = new Map();
                        work.entries.forEach(e => {
                            entryByPath.set(e.file.relativePath || '', e);
                        });

                        try {
                            await downloadTarBundle(ip, port, work.indices, destination,
                                (sanitizedPath, rawPath, bytes) => {
                                    const entry = entryByPath.get(rawPath);
                                    if (entry) {
                                        batchProcessed.add(entry.globalIndex);
                                        batchCompleted++;
                                        totalCompleted++;
                                    } else {
                                        console.warn(`BATCH TAR: extracted file with unmatched path: "${rawPath}"`);
                                    }
                                    totalBytesTransferred += bytes;

                                    const now = Date.now();
                                    if (now - lastProgressUpdate > CONFIG.PROGRESS_THROTTLE) {
                                        lastProgressUpdate = now;
                                        mainWindow.webContents.send('transfer-progress', {
                                            completed: totalCompleted,
                                            total: totalFiles,
                                            bytesTransferred: totalBytesTransferred,
                                            totalBytes: totalBytesExpected,
                                            currentFile: sanitizedPath
                                        });
                                    }
                                }
                            );

                            // After successful bundle: detect files server skipped
                            const skippedEntries = work.entries.filter(e => !batchProcessed.has(e.globalIndex));
                            if (skippedEntries.length > 0) {
                                console.warn(`  Batch ${batchNum} TAR: ${skippedEntries.length} files skipped by server`);
                                for (const entry of skippedEntries) {
                                    batchProcessed.add(entry.globalIndex);
                                    batchFailed.push({ index: entry.globalIndex, localIndex: entry.localIndex, path: entry.file.relativePath || `index_${entry.globalIndex}`, error: 'Skipped by phone (file inaccessible)' });
                                }
                            }
                        } catch (e) {
                            console.error(`TAR BUNDLE FAILED in batch ${batchNum}: ${e.message}`);
                            work.entries.forEach(entry => {
                                if (!batchProcessed.has(entry.globalIndex)) {
                                    batchProcessed.add(entry.globalIndex);
                                    batchFailed.push({ index: entry.globalIndex, localIndex: entry.localIndex, path: entry.file.relativePath || `index_${entry.globalIndex}`, error: e.message });
                                    totalCompleted++;
                                }
                            });

                            const isNetErr = e.message.includes('ECONNRESET') || e.message.includes('ECONNREFUSED') ||
                                             e.message.includes('EHOSTUNREACH') || e.message.includes('ETIMEDOUT') ||
                                             e.message.includes('socket hang up') || e.message.includes('timeout');
                            if (isNetErr) await handleNetworkFailure(ip, port);
                        }
                    } else {
                        // Individual batch file download (unchanged logic)
                        const { file, localIndex, globalIndex } = work;
                        const sourcePath = (file.path || '').replace(/\\/g, '/');
                        const relPath = sanitizeRelPath((pathMapping[sourcePath] || file.relativePath || path.basename(sourcePath)).replace(/\\/g, '/').replace(/^\/+/, ''));
                        const destPath = safeDestPath(path.join(destination, relPath));

                        try {
                            fs.mkdirSync(path.dirname(destPath), { recursive: true });
                            const bytes = await downloadBatchFileByIndex(ip, port, batchNum, localIndex, destPath);
                            totalBytesTransferred += bytes;
                            batchCompleted++;
                            totalCompleted++;

                            const now = Date.now();
                            if (now - lastProgressUpdate > CONFIG.PROGRESS_THROTTLE || batchCompleted === filesToDownload) {
                                lastProgressUpdate = now;
                                mainWindow.webContents.send('transfer-progress', {
                                    completed: totalCompleted,
                                    total: totalFiles,
                                    bytesTransferred: totalBytesTransferred,
                                    totalBytes: totalBytesExpected,
                                    currentFile: relPath
                                });
                            }
                        } catch (e) {
                            console.error(`FAILED [Batch ${batchNum}, Local ${localIndex}] ${relPath}: ${e.message}`);
                            batchFailed.push({ index: globalIndex, localIndex, path: relPath, error: e.message });

                            const isNetErr = e.message.includes('ECONNRESET') || e.message.includes('ECONNREFUSED') ||
                                             e.message.includes('EHOSTUNREACH') || e.message.includes('ETIMEDOUT') ||
                                             e.message.includes('socket hang up') || e.message.includes('timeout');
                            if (isNetErr) await handleNetworkFailure(ip, port);
                        }
                    }
                }
            };

            // Run parallel downloads for this batch
            const workers = [];
            for (let i = 0; i < CONFIG.PARALLEL_DOWNLOADS; i++) {
                workers.push(downloadBatchFile());
            }
            await Promise.all(workers);

            // Add batch failures to total
            totalFailed.push(...batchFailed);

            console.log(`Batch ${batchNum} complete: ${batchCompleted}/${filesToDownload} files (${batchFailed.length} failed)`);

            // Mark batch as complete on phone
            try {
                await httpGet(`http://${ip}:${port}/batch/${batchNum}/complete`, 5000);
            } catch (e) {
                console.log(`Failed to notify batch ${batchNum} complete: ${e.message}`);
            }

            // Notify phone of overall progress
            notifyPhoneProgress(ip, port, totalCompleted, totalFiles, `Batch ${batchNum + 1} complete`);

            // Clear batch data from memory (help GC)
            batchManifest = null;
        }

        // ==================== BATCH RETRY: Individual /file + MediaStore /retry-file ====================
        if (totalFailed.length > 0 && totalFailed.length <= 500 && !wifiState.cancelRequested) {
            console.log(`\n=== BATCH RETRY: ${totalFailed.length} failed files via direct download ===`);
            sendProgress(`Retrying ${totalFailed.length} failed files...`);

            await new Promise(r => setTimeout(r, 1000));

            const retryList = [...totalFailed];
            totalFailed = [];

            // Pass 1: Try /file?index=N (direct download, bypasses tar)
            const stillFailed = [];
            for (let ri = 0; ri < retryList.length; ri++) {
                const item = retryList[ri];
                if (wifiState.cancelRequested) { stillFailed.push(item); continue; }
                try {
                    const globalIndex = item.index;
                    if (globalIndex === undefined) { stillFailed.push(item); continue; }

                    // Compute batch and local index from global index
                    const batchNum = Math.floor(globalIndex / (batchInfo.batchSize || CONFIG.BATCH_SIZE));
                    const localIndex = globalIndex % (batchInfo.batchSize || CONFIG.BATCH_SIZE);

                    const relPath = sanitizeRelPath((item.path || `file_${globalIndex}`).replace(/\\/g, '/'));
                    const destPath = safeDestPath(path.join(destination, relPath));
                    fs.mkdirSync(path.dirname(destPath), { recursive: true });

                    mainWindow.webContents.send('transfer-progress', {
                        completed: totalCompleted,
                        total: totalFiles,
                        bytesTransferred: totalBytesTransferred,
                        totalBytes: totalBytesExpected,
                        currentFile: `Retry ${ri + 1}/${retryList.length}: ${relPath}`
                    });

                    const bytes = await downloadBatchFileByIndex(ip, port, batchNum, localIndex, destPath, 15000);
                    totalBytesTransferred += bytes;
                    totalCompleted++;
                    console.log(`Batch retry OK [${ri + 1}/${retryList.length}]: ${relPath}`);
                } catch (e) {
                    console.error(`Batch retry FAILED [${ri + 1}/${retryList.length}]: ${item.path}: ${e.message}`);
                    stillFailed.push({ ...item, error: `Retry failed: ${e.message}` });
                }
            }

            // Pass 2: MediaStore /retry-file for files still failing
            if (stillFailed.length > 0 && !wifiState.cancelRequested) {
                console.log(`\n=== BATCH LAST RESORT: ${stillFailed.length} files via MediaStore ===`);
                sendProgress(`Recovering ${stillFailed.length} files via MediaStore...`);
                await new Promise(r => setTimeout(r, 500));

                for (let ri = 0; ri < stillFailed.length; ri++) {
                    const item = stillFailed[ri];
                    if (wifiState.cancelRequested) { totalFailed.push(item); continue; }
                    try {
                        const relPath = sanitizeRelPath((item.path || `file_${item.index}`).replace(/\\/g, '/'));
                        const destPath = safeDestPath(path.join(destination, relPath));
                        fs.mkdirSync(path.dirname(destPath), { recursive: true });

                        const fileName = path.basename(relPath);
                        const encodedName = encodeURIComponent(fileName);
                        const retryUrl = `http://${ip}:${port}/retry-file?name=${encodedName}&size=0`;

                        mainWindow.webContents.send('transfer-progress', {
                            completed: totalCompleted,
                            total: totalFiles,
                            bytesTransferred: totalBytesTransferred,
                            totalBytes: totalBytesExpected,
                            currentFile: `MediaStore ${ri + 1}/${stillFailed.length}: ${fileName}`
                        });

                        const bytes = await new Promise((resolve, reject) => {
                            const fileStream = fs.createWriteStream(destPath);
                            let received = 0;
                            const req = http.get(retryUrl, { timeout: 30000 }, (res) => {
                                if (res.statusCode !== 200) {
                                    fileStream.close();
                                    try { fs.unlinkSync(destPath); } catch (_) {}
                                    reject(new Error(`HTTP ${res.statusCode}`));
                                    return;
                                }
                                res.on('data', (chunk) => { received += chunk.length; });
                                res.on('error', (e) => { fileStream.close(); try { fs.unlinkSync(destPath); } catch (_) {} reject(e); });
                                res.pipe(fileStream);
                                fileStream.on('finish', () => { fileStream.close(); resolve(received); });
                            });
                            req.on('error', (e) => { fileStream.close(); try { fs.unlinkSync(destPath); } catch (_) {} reject(e); });
                            req.on('timeout', () => { req.destroy(); fileStream.close(); try { fs.unlinkSync(destPath); } catch (_) {} reject(new Error('Timeout')); });
                        });

                        totalBytesTransferred += bytes;
                        totalCompleted++;
                        console.log(`MediaStore retry OK [${ri + 1}/${stillFailed.length}]: ${fileName} (${bytes} bytes)`);
                    } catch (e) {
                        console.error(`MediaStore retry FAILED [${ri + 1}/${stillFailed.length}]: ${item.path}: ${e.message}`);
                        totalFailed.push({ ...item, error: `All methods failed: ${e.message}` });
                    }
                }
            } else {
                totalFailed.push(...stillFailed);
            }

            console.log(`Batch retry complete: ${retryList.length - totalFailed.length} recovered, ${totalFailed.length} still failed`);
        }

        // ==================== BATCH INVARIANT ENFORCEMENT ====================
        // Ensure totalCompleted + totalFailed.length === totalFiles
        const batchAccountedFor = totalCompleted + totalFailed.length;
        if (batchAccountedFor !== totalFiles) {
            const batchGap = totalFiles - batchAccountedFor;
            console.error(`BATCH INVARIANT VIOLATION: completed(${totalCompleted}) + failed(${totalFailed.length}) = ${batchAccountedFor} != totalFiles(${totalFiles}). Gap: ${batchGap}`);
            if (batchGap > 0) {
                for (let i = 0; i < batchGap; i++) {
                    totalFailed.push({ path: `Unknown (gap file ${i})`, error: 'Unaccounted file in batch transfer' });
                }
            }
        }

        // Send final progress
        mainWindow.webContents.send('transfer-progress', {
            completed: totalCompleted + totalFailed.length,
            total: totalFiles,
            bytesTransferred: totalBytesTransferred,
            totalBytes: totalBytesExpected,
            currentFile: totalFailed.length === 0 ? 'All files transferred' : `${totalCompleted} succeeded, ${totalFailed.length} failed`
        });

        // Only clean up if we're still the active session
        const isStale = wifiState.sessionId !== mySession;
        if (!isStale) {
            stopWifiHeartbeat();
            wifiState.inProgress = false;
            wifiState.connection = null;
        }
        if (isStale) {
            console.log('Batch transfer session superseded — exiting silently');
            return { success: false, error: 'Cancelled', stale: true };
        }

        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        const avgSpeed = totalBytesTransferred > 0 ? formatSize(totalBytesTransferred / parseFloat(elapsed)) : '0 B';
        const skippedWarning = skippedOnPhone > 0 ? ` (${skippedOnPhone} files were inaccessible on phone)` : '';

        if (wifiState.cancelRequested) {
            if (wifiState.phoneUnreachable) {
                return {
                    success: false,
                    error: `Phone connection lost. ${totalCompleted} of ${totalFiles} files transferred.`,
                    completed: totalCompleted,
                    failed: totalFailed.length,
                    batchMode: true,
                    elapsed,
                    avgSpeed
                };
            } else {
                return {
                    success: false,
                    error: 'Cancelled',
                    completed: totalCompleted,
                    failed: totalFailed.length,
                    batchMode: true
                };
            }
        }

        // Final verification log
        console.log(`\n=== BATCH TRANSFER VERIFICATION ===`);
        console.log(`Total batches: ${totalBatches}`);
        console.log(`Original count: ${originalCount}`);
        console.log(`Skipped on phone: ${skippedOnPhone}`);
        console.log(`Total files in batches: ${totalFiles}`);
        console.log(`Successfully downloaded: ${totalCompleted}`);
        console.log(`Failed: ${totalFailed.length}`);
        console.log(`Accounted for: ${totalCompleted + totalFailed.length} (must equal ${totalFiles})`);
        console.log(`Elapsed: ${elapsed}s, Speed: ${avgSpeed}/s`);

        // Notify phone of completion
        notifyPhoneComplete(ip, port, totalFailed.map(f => f.path || ''));

        if (totalFailed.length === 0) {
            const message = skippedOnPhone > 0
                ? `${totalCompleted} files transferred in ${totalBatches} batches. Note: ${skippedOnPhone} files couldn't be read on phone.`
                : `All ${totalCompleted} files transferred successfully in ${totalBatches} batches`;
            return {
                success: skippedOnPhone === 0,
                completed: totalCompleted,
                failed: 0,
                skippedOnPhone,
                originalCount,
                elapsed,
                avgSpeed,
                message,
                batchMode: true,
                totalBatches,
                warning: skippedOnPhone > 0 ? `${skippedOnPhone} files were inaccessible on phone` : undefined
            };
        } else if (totalCompleted > 0) {
            const failedNames = totalFailed.slice(0, 5).map(f => `${f.path} (${f.error || 'unknown'})`).join(', ');
            return {
                success: false,
                error: `${totalFailed.length} of ${totalFiles} files failed${skippedWarning}. First: ${failedNames}`,
                completed: totalCompleted,
                failed: totalFailed.length,
                skippedOnPhone,
                originalCount,
                elapsed,
                avgSpeed,
                batchMode: true,
                totalBatches,
                failedFiles: totalFailed.map(f => f.path)
            };
        } else {
            return {
                success: false,
                error: `All ${totalFiles} files failed${skippedWarning}`,
                completed: 0,
                failed: totalFailed.length,
                skippedOnPhone,
                originalCount,
                elapsed,
                avgSpeed,
                batchMode: true,
                totalBatches,
                failedFiles: totalFailed.map(f => f.path)
            };
        }

    } catch (e) {
        if (wifiState.sessionId === mySession) {
            stopWifiHeartbeat();
            if (wifiState.connection) {
                notifyPhoneError(wifiState.connection.ip, wifiState.connection.port, e.message);
            }
            wifiState.inProgress = false;
            wifiState.connection = null;
        }

        console.error('Batch transfer error:', e);
        return { success: false, error: e.message, batchMode: true };
    }
}

/**
 * Download a file from a specific batch using local index
 */
async function downloadBatchFileByIndex(ip, port, batchNum, localIndex, destPath, timeout = 300000) {
    let lastError;

    for (let attempt = 1; attempt <= CONFIG.FILE_RETRY_COUNT; attempt++) {
        // Break out of retry loop if transfer was cancelled or phone is unreachable
        if (wifiState.cancelRequested) {
            throw new Error('Transfer cancelled');
        }

        try {
            return await _downloadBatchFileOnce(ip, port, batchNum, localIndex, destPath, timeout);
        } catch (e) {
            lastError = e;

            const isNetworkError = e.message.includes('EHOSTUNREACH') ||
                                   e.message.includes('ECONNRESET') ||
                                   e.message.includes('ECONNREFUSED') ||
                                   e.message.includes('ETIMEDOUT') ||
                                   e.message.includes('timeout') ||
                                   e.message.includes('socket hang up');
            if (!isNetworkError || attempt >= CONFIG.FILE_RETRY_COUNT) break;
            await new Promise(r => setTimeout(r, CONFIG.FILE_RETRY_DELAY));
        }
    }
    throw lastError;
}

function _downloadBatchFileOnce(ip, port, batchNum, localIndex, destPath, timeout) {
    return new Promise((resolve, reject) => {
        const url = `http://${ip}:${port}/batch/${batchNum}/file?index=${localIndex}`;
        let settled = false;
        const settle = (fn, val) => { if (!settled) { settled = true; fn(val); } };

        const file = fs.createWriteStream(destPath, { highWaterMark: 2 * 1024 * 1024 });
        let bytes = 0;

        // Register error handler IMMEDIATELY — before file open completes
        file.on('error', (e) => {
            fs.unlink(destPath, () => {});
            settle(reject, e);
        });

        const req = http.get(url, { timeout, agent: keepAliveAgent }, (res) => {
            if (res.statusCode !== 200) {
                file.close();
                fs.unlink(destPath, () => {});
                settle(reject, new Error(`HTTP ${res.statusCode} for batch ${batchNum} file ${localIndex}`));
                return;
            }

            res.on('data', (chunk) => {
                bytes += chunk.length;
                wifiState.lastActivityAt = Date.now(); // Data flowing = phone alive
            });

            // CRITICAL: pipe does NOT propagate errors from readable to writable.
            // Without this, if socket dies mid-download, Promise hangs forever.
            res.on('error', (e) => {
                file.close();
                fs.unlink(destPath, () => {});
                settle(reject, new Error(friendlyNetworkError(e, ip)));
            });

            res.pipe(file);

            file.on('finish', () => {
                file.close();
                settle(resolve, bytes);
            });
        });

        req.on('error', (e) => {
            file.close();
            fs.unlink(destPath, () => {});
            settle(reject, new Error(friendlyNetworkError(e, ip)));
        });

        req.on('timeout', () => {
            req.destroy();
            file.close();
            fs.unlink(destPath, () => {});
            settle(reject, new Error('Download timeout'));
        });
    });
}

ipcMain.handle('start-adb-transfer', async (event, { device, destination }) => {
    // Check if THIS mode already has a transfer in progress
    if (adbState.inProgress) {
        return { success: false, error: 'ADB transfer already in progress' };
    }

    // Get detected ADB path
    const adbPath = getADBPath();
    const adbStatus = getADBStatus();

    if (!adbStatus.available) {
        return { success: false, error: adbStatus.error || 'ADB not available' };
    }

    // Reset ADB state for new transfer
    adbState.inProgress = true;
    adbState.cancelRequested = false;
    adbState.socket = null;
    adbState.isAborted = false;
    adbState.activeChildren = new Map();
    adbState.server = null;
    adbState.device = device;

    // Parallel transfer configuration
    const MAX_CONCURRENT_TRANSFERS = 6;  // Optimal for USB 2.0/3.0
    const BASE_TRANSFER_TIMEOUT = 120000; // 2 minutes - used by watchdog to detect no file growth
    const WATCHDOG_INTERVAL = 5000;      // Check for stuck processes every 5 seconds
    const MAX_CONSECUTIVE_FAILURES = 20; // Abort if too many failures in a row
    const RETRY_COUNT = 2;               // Retry failed files up to 2 times

    /**
     * Get timeout for a file transfer.
     * We can't know file size before pulling, so use a generous fixed timeout.
     * The watchdog (checks dest file growth) handles truly stuck transfers.
     */
    const FILE_TRANSFER_TIMEOUT = 1800000; // 30 minutes max per file (handles 100+ GB at slow speeds)

    /**
     * Force kill a child process and all its descendants
     */
    const forceKillProcess = (child, pid) => {
        try {
            if (process.platform === 'win32') {
                // Windows: use taskkill to kill process tree
                require('child_process').execSync(`taskkill /pid ${pid} /T /F`, {
                    windowsHide: true,
                    timeout: 5000
                });
            } else {
                // Unix: kill process group
                process.kill(-pid, 'SIGKILL');
            }
        } catch (e) {
            // Process already dead, ignore
        }
        try {
            child.kill('SIGKILL');
        } catch (e) {}
    };

    /**
     * Worker pool for parallel ADB pulls
     * FAIL-PROOF: Multiple layers of protection against hangs
     */
    const pullFileAsync = (sourcePath, destPath, relPath, retryAttempt = 0) => {
        return new Promise((resolve) => {
            if (adbState.cancelRequested || adbState.isAborted) {
                resolve({ success: false, skipped: true });
                return;
            }

            const { exec } = require('child_process');
            let resolved = false;
            let child = null;
            let watchdogTimer = null;
            let timeoutTimer = null;

            const cleanup = () => {
                resolved = true;
                if (watchdogTimer) clearInterval(watchdogTimer);
                if (timeoutTimer) clearTimeout(timeoutTimer);
                if (child && child.pid) {
                    adbState.activeChildren.delete(child.pid);
                }
            };

            const safeResolve = (result) => {
                if (!resolved) {
                    cleanup();
                    resolve(result);
                }
            };

            try {
                child = exec(
                    `"${adbPath}" -s "${device}" pull "${sourcePath}" "${destPath}"`,
                    {
                        timeout: FILE_TRANSFER_TIMEOUT,
                        windowsHide: true,
                        maxBuffer: 10 * 1024 * 1024,
                        killSignal: 'SIGKILL'
                    },
                    (error) => {
                        if (resolved) return;

                        if (error) {
                            // Check if we should retry
                            if (retryAttempt < RETRY_COUNT && !adbState.cancelRequested && !adbState.isAborted) {
                                cleanup();
                                // Retry after short delay
                                setTimeout(() => {
                                    pullFileAsync(sourcePath, destPath, relPath, retryAttempt + 1)
                                        .then(resolve);
                                }, 500);
                                return;
                            }
                            safeResolve({ success: false, error: error.message, relPath });
                        } else {
                            safeResolve({ success: true, relPath });
                        }
                    }
                );

                if (child.pid) {
                    adbState.activeChildren.set(child.pid, { child, sourcePath, startTime: Date.now(), lastFileSize: 0 });
                }

                // Smart watchdog: check if destination file is still growing
                // Kills truly stuck processes while allowing large files to transfer
                watchdogTimer = setInterval(() => {
                    if (resolved) return;

                    const info = adbState.activeChildren.get(child.pid);
                    if (!info) return;

                    try {
                        const currentSize = fs.existsSync(destPath) ? fs.statSync(destPath).size : 0;
                        if (currentSize > info.lastFileSize) {
                            // File is still growing — transfer is active, reset tracking
                            info.lastFileSize = currentSize;
                            info.lastGrowth = Date.now();
                        } else if (info.lastGrowth && Date.now() - info.lastGrowth > BASE_TRANSFER_TIMEOUT) {
                            // File hasn't grown for 2 minutes — truly stuck
                            console.log(`Watchdog: File stopped growing for ${relPath} (${currentSize} bytes) — killing`);
                            forceKillProcess(child, child.pid);
                            safeResolve({ success: false, error: 'Process stuck - no file growth', relPath });
                        }
                    } catch (e) {
                        // Can't stat file yet, that's fine — transfer just started
                    }
                }, WATCHDOG_INTERVAL);

                // Hard timeout backup (in case exec timeout fails)
                timeoutTimer = setTimeout(() => {
                    if (!resolved) {
                        console.log(`Hard timeout: Force killing process for ${relPath}`);
                        forceKillProcess(child, child.pid);
                        safeResolve({ success: false, error: 'Hard timeout exceeded', relPath });
                    }
                }, FILE_TRANSFER_TIMEOUT + 10000);

                // Handle process errors
                child.on('error', (err) => {
                    safeResolve({ success: false, error: err.message, relPath });
                });

            } catch (e) {
                safeResolve({ success: false, error: e.message, relPath });
            }
        });
    };

    /**
     * Kill all active child processes (for cleanup/cancellation)
     */
    const killAllActiveProcesses = () => {
        console.log(`Killing ${adbState.activeChildren.size} active processes...`);
        for (const [pid, info] of adbState.activeChildren) {
            forceKillProcess(info.child, pid);
        }
        adbState.activeChildren.clear();
    };

    /**
     * Process files in parallel with controlled concurrency
     * FAIL-PROOF version with multiple safety mechanisms
     */
    const processFilesParallel = async (files, pathMapping, destination, socket, totalFiles, startTime, onFileComplete = null, initialCompleted = 0, totalBytes = 0, initialBytesTransferred = 0) => {
        let completed = initialCompleted;
        let bytesTransferred = initialBytesTransferred;
        let failed = [];
        let queue = [...files];
        let inFlight = new Map();  // Track active transfers
        let lastProgressUpdate = 0;
        let consecutiveFailures = 0;
        let socketAlive = true;
        let loopWatchdog = null;

        // Monitor socket health - phone disconnect means immediate stop
        socket.on('close', () => {
            if (!socketAlive) return; // Already handled
            socketAlive = false;
            console.log('Socket closed (phone disconnected) - killing all active transfers');
            adbState.cancelRequested = true;
            killAllActiveProcesses();
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('transfer-error', {
                    mode: 'adb',
                    message: 'Phone cancelled or disconnected. Transfer stopped.'
                });
            }
        });
        socket.on('error', () => {
            if (!socketAlive) return; // Already handled
            socketAlive = false;
            console.log('Socket error (phone disconnected) - killing all active transfers');
            adbState.cancelRequested = true;
            killAllActiveProcesses();
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('transfer-error', {
                    mode: 'adb',
                    message: 'Phone cancelled or disconnected. Transfer stopped.'
                });
            }
        });

        const updateProgress = (relPath, forceUpdate = false) => {
            if (!socketAlive) return;

            const now = Date.now();
            // Throttle progress updates to every 100ms to prevent UI flooding
            if (forceUpdate || now - lastProgressUpdate > 100 || completed === totalFiles) {
                lastProgressUpdate = now;

                // Use whichever metric is further along. Byte-based is more accurate but
                // updates only on file completion; file-count gives smooth early progress.
                // Taking the max keeps the bar always moving forward.
                const filePercent = totalFiles > 0 ? Math.round((completed / totalFiles) * 100) : 0;
                const bytePercent = totalBytes > 0 ? Math.round((bytesTransferred / totalBytes) * 100) : 0;
                const percent = Math.min(100, Math.max(filePercent, bytePercent));
                const elapsed = (now - startTime) / 1000;
                // ETA — prefer bytes when available, fall back to file count.
                let eta = 0;
                if (totalBytes > 0 && bytesTransferred > initialBytesTransferred) {
                    const bytesPerSec = (bytesTransferred - initialBytesTransferred) / elapsed || 0;
                    eta = bytesPerSec > 0 ? Math.round((totalBytes - bytesTransferred) / bytesPerSec) : 0;
                } else {
                    const filesThisRun = completed - initialCompleted;
                    const filesPerSec = filesThisRun / elapsed || 0;
                    eta = filesPerSec > 0 ? Math.round((totalFiles - completed) / filesPerSec) : 0;
                }

                try {
                    socket.write(JSON.stringify({
                        type: 'PROGRESS',
                        percent,
                        currentFile: relPath,
                        completed,
                        total: totalFiles,
                        bytesTransferred,
                        totalBytes,
                        failed: failed.length,
                        eta
                    }) + '\n');
                } catch (e) {
                    socketAlive = false;
                }

                mainWindow.webContents.send('transfer-progress', {
                    completed,
                    total: totalFiles,
                    bytesTransferred,
                    totalBytes,
                    currentFile: relPath
                });
            }
        };

        // Watchdog for the main processing loop (detect if loop is stuck)
        // Uses 5-minute threshold since large files can keep workers busy for extended periods
        let lastLoopActivity = Date.now();
        loopWatchdog = setInterval(() => {
            const stuckTime = Date.now() - lastLoopActivity;
            if (stuckTime > 300000) { // 5 minutes with no loop activity
                console.error('Main loop appears stuck - forcing abort');
                adbState.isAborted = true;
                killAllActiveProcesses();
            }
        }, 10000);

        try {
            // Process queue with worker pool
            while ((queue.length > 0 || inFlight.size > 0) && !adbState.cancelRequested && !adbState.isAborted) {
                lastLoopActivity = Date.now();

                // Check socket health
                if (!socketAlive) {
                    console.log('Socket dead - stopping transfer');
                    break;
                }

                // Check consecutive failures (indicates systemic problem like ADB crash)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    console.error(`Too many consecutive failures (${consecutiveFailures}) - aborting`);
                    adbState.isAborted = true;
                    mainWindow.webContents.send('transfer-error', {
                        mode: 'adb',
                        message: 'Transfer failed. Please check your USB connection and try again.'
                    });
                    break;
                }

                // Start new workers up to MAX_CONCURRENT_TRANSFERS
                while (queue.length > 0 && inFlight.size < MAX_CONCURRENT_TRANSFERS && !adbState.cancelRequested && !adbState.isAborted && socketAlive) {
                    const sourcePath = queue.shift();
                    const relPath = sanitizeRelPath((pathMapping[sourcePath] || path.basename(sourcePath)).replace(/\\/g, '/'));
                    const destPath = safeDestPath(path.join(destination, relPath));

                    // Ensure directory exists
                    try {
                        fs.mkdirSync(path.dirname(destPath), { recursive: true });
                    } catch (e) {
                        // Check for disk full
                        if (e.code === 'ENOSPC') {
                            console.error('Disk full - aborting transfer');
                            adbState.isAborted = true;
                            mainWindow.webContents.send('transfer-error', {
                                mode: 'adb',
                                message: 'Transfer aborted: Disk is full'
                            });
                            break;
                        }
                    }

                    // Start async transfer
                    const promise = pullFileAsync(sourcePath, destPath, relPath)
                        .then((result) => {
                            inFlight.delete(sourcePath);

                            if (result.success) {
                                completed++;
                                // Attribute the pulled bytes so percent tracks data, not file count.
                                try { bytesTransferred += fs.statSync(destPath).size; } catch (_) {}
                                consecutiveFailures = 0; // Reset on success
                                updateProgress(relPath);
                                // Notify state tracker of completed file
                                if (onFileComplete) {
                                    onFileComplete(sourcePath, relPath, completed);
                                }
                            } else if (!result.skipped) {
                                failed.push(relPath);
                                consecutiveFailures++;
                                console.log(`Failed: ${relPath} (${consecutiveFailures} consecutive failures)`);
                            }
                            return result;
                        })
                        .catch((err) => {
                            // Catch any unhandled promise errors
                            inFlight.delete(sourcePath);
                            failed.push(relPath);
                            consecutiveFailures++;
                            console.error(`Unhandled error for ${relPath}:`, err);
                            return { success: false, error: err.message };
                        });

                    inFlight.set(sourcePath, promise);
                }

                // Wait for at least one worker to complete before continuing
                // Use timeout to prevent infinite wait
                if (inFlight.size > 0) {
                    const raceTimeout = new Promise((resolve) =>
                        setTimeout(() => resolve({ timeout: true }), 5000)
                    );

                    await Promise.race([...inFlight.values(), raceTimeout]);
                }
            }

            // Wait for all remaining transfers to complete (with overall timeout)
            if (inFlight.size > 0 && !adbState.isAborted) {
                console.log(`Waiting for ${inFlight.size} remaining transfers...`);

                const remainingTimeout = new Promise((resolve) =>
                    setTimeout(() => {
                        console.log('Timeout waiting for remaining transfers');
                        resolve({ timeout: true });
                    }, FILE_TRANSFER_TIMEOUT)
                );

                await Promise.race([
                    Promise.all(inFlight.values()),
                    remainingTimeout
                ]);
            }

        } finally {
            // Cleanup
            if (loopWatchdog) clearInterval(loopWatchdog);
            killAllActiveProcesses();
        }

        return { completed, failed, aborted: adbState.isAborted };
    };

    try {
        fs.mkdirSync(destination, { recursive: true });

        sendProgress('Connecting via USB...');
        try {
            execSync(`"${adbPath}" -s "${device}" reverse tcp:${CONFIG.ADB_PORT} tcp:${CONFIG.ADB_PORT}`, { timeout: 10000, windowsHide: true });
        } catch (e) {
            throw new Error(`ADB setup failed: ${e.message}`);
        }

        sendProgress('Waiting for phone to connect...');

        // Store server ref outside Promise so catch block can close it on error
        let adbServer = null;

        const result = await new Promise((resolve, reject) => {
            // Connection timeout - only for initial phone connection, cleared once manifest arrives
            let connectionTimeout = null;

            const server = net.createServer(async (socket) => {
                // Reject stale connections (e.g. phone reconnects after PC cancelled)
                if (adbState.cancelRequested || !adbState.inProgress) {
                    console.log('Rejecting stale connection - transfer already cancelled/completed');
                    socket.destroy();
                    return;
                }

                // Phone connected! Clear connection timeout immediately
                if (connectionTimeout) {
                    clearTimeout(connectionTimeout);
                    connectionTimeout = null;
                    console.log('Phone connected, connection timeout cleared');
                }
                // Track socket for cancellation notification (ADB-specific)
                adbState.socket = socket;

                let data = '';
                // 200MB max for manifest - supports 500,000+ files
                const MAX_MANIFEST_SIZE = CONFIG.MAX_MANIFEST_SIZE;
                let manifestReceived = false;

                socket.on('data', async (chunk) => {
                    // Prevent unbounded string growth
                    if (manifestReceived) return;

                    if (data.length + chunk.length > MAX_MANIFEST_SIZE) {
                        console.error('Manifest too large, rejecting');
                        socket.destroy(new Error('Manifest size exceeds limit'));
                        return;
                    }

                    data += chunk.toString();

                    if (data.includes('\n')) {
                        manifestReceived = true;

                        // Redundant clear (already cleared on socket connection) - safety net
                        if (connectionTimeout) {
                            clearTimeout(connectionTimeout);
                            connectionTimeout = null;
                        }
                        try {
                            const manifest = JSON.parse(data.split('\n')[0]);
                            const totalFiles = manifest.totalFiles || 0;
                            const totalBytes = manifest.totalSize || 0;

                            // Create directories from mapping
                            const dirMapping = manifest.directoryMapping || {};
                            for (const [absPath, relPath] of Object.entries(dirMapping)) {
                                if (relPath) {
                                    const fullPath = path.join(destination, relPath.replace(/\\/g, '/'));
                                    try {
                                        fs.mkdirSync(fullPath, { recursive: true });
                                    } catch (e) {}
                                }
                            }

                            const pathMapping = manifest.pathMapping || {};
                            const batches = manifest.batches || [];
                            const startTime = Date.now();

                            // Flatten all file paths from batches
                            const allFiles = [];
                            for (const batch of batches) {
                                for (const sourcePath of batch.paths || []) {
                                    allFiles.push(sourcePath);
                                }
                            }

                            const filesToTransfer = allFiles;

                            // Use actual file count from batches if manifest count differs
                            // (manifest.totalFiles may include files skipped during symlink creation)
                            if (filesToTransfer.length !== totalFiles) {
                                console.log(`File count mismatch: manifest says ${totalFiles}, batches contain ${filesToTransfer.length}. Using batch count.`);
                            }
                            const actualTotalFiles = filesToTransfer.length;
                            sendProgress(`Transferring ${actualTotalFiles} files...`);

                            console.log(`Starting parallel transfer of ${actualTotalFiles} files with ${MAX_CONCURRENT_TRANSFERS} workers`);

                            // Process files in parallel with controlled concurrency
                            const { completed, failed, aborted } = await processFilesParallel(
                                filesToTransfer,
                                pathMapping,
                                destination,
                                socket,
                                actualTotalFiles,
                                startTime,
                                null,
                                0,
                                totalBytes,
                                0
                            );

                            // completed already includes the offset from initialCompleted
                            const totalCompleted = completed;

                            // Send final progress to PC UI
                            mainWindow.webContents.send('transfer-progress', {
                                completed: totalCompleted,
                                total: actualTotalFiles,
                                bytesTransferred: totalBytes,
                                totalBytes,
                                currentFile: totalCompleted >= actualTotalFiles ? 'All files transferred' : `${totalCompleted} of ${actualTotalFiles} done`
                            });

                            const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
                            const avgSpeed = (totalCompleted / parseFloat(elapsed) || 0).toFixed(1);

                            // Send completion message (even if aborted, report what was done)
                            try {
                                socket.write(JSON.stringify({
                                    type: aborted ? 'ABORTED' : 'COMPLETE',
                                    filesTransferred: totalCompleted,
                                    filesFailed: failed.length,
                                    failedFiles: failed,
                                    elapsed,
                                    avgSpeed: `${avgSpeed} files/sec`,
                                    reason: aborted ? 'Transfer was aborted due to errors' : null
                                }) + '\n');
                            } catch (e) {
                                // Socket might be dead
                            }

                            try {
                                socket.end();
                            } catch (e) {}
                            server.close();

                            try {
                                execSync(`"${adbPath}" -s "${device}" reverse --remove tcp:${CONFIG.ADB_PORT}`, { timeout: 5000, windowsHide: true });
                            } catch (e) {}

                            const userCancelled = adbState.cancelRequested && !adbState.isAborted;

                            resolve({
                                success: !aborted && !userCancelled && failed.length === 0 && totalCompleted >= actualTotalFiles,
                                completed: totalCompleted,
                                failed: failed.length,
                                elapsed,
                                aborted: aborted || false,
                                cancelled: userCancelled,
                                message: userCancelled
                                    ? 'Cancelled'
                                    : aborted
                                        ? `Transfer aborted after ${totalCompleted} files (${failed.length} failed)`
                                        : `Completed ${totalCompleted} files in ${elapsed}s`
                            });
                        } catch (e) {
                            console.error('Manifest processing error:', e);
                            killAllActiveProcesses(); // Cleanup on error
                            reject(e);
                        }
                    }
                });

                socket.on('error', (e) => {
                    console.error('Socket error:', e);
                    reject(e);
                });
            });

            server.on('error', reject);
            server.listen(CONFIG.ADB_PORT, '127.0.0.1');
            adbServer = server;  // Store ref for cleanup in catch block
            adbState.server = server;  // Also store on state for cancel handler access

            connectionTimeout = setTimeout(() => {
                if (adbState.socket) {
                    // Phone is connected, don't timeout
                    console.log('Connection timeout fired but phone is connected - ignoring');
                    return;
                }
                server.close();
                reject(new Error('Timeout waiting for phone'));
            }, 300000);
        });

        // Reset ADB state
        adbState.inProgress = false;
        adbState.isAborted = false;
        adbState.socket = null;
        adbState.server = null;
        adbState.device = null;
        return result;
    } catch (e) {
        // Reset ADB state
        adbState.inProgress = false;
        adbState.isAborted = false;
        adbState.socket = null;
        adbState.server = null;
        adbState.device = null;
        killAllActiveProcesses();  // Cleanup any stuck processes
        // Close the ADB server to release the port (prevents EADDRINUSE on retry)
        try { if (adbServer) adbServer.close(); } catch (ex) {}
        try { execSync(`"${adbPath}" -s "${device}" reverse --remove tcp:${CONFIG.ADB_PORT}`, { timeout: 5000, windowsHide: true }); } catch (ex) {}
        return { success: false, error: e.message };
    }
});

/**
 * Cancel transfer for a specific mode
 * Each mode has isolated state - cancelling one doesn't affect others
 */
ipcMain.handle('cancel-transfer', async (event, { mode, confirmed }) => {
    // If confirmation not provided, return info about what needs confirmation
    if (!confirmed) {
        const activeMode = getActiveTransferMode();
        if (!activeMode) {
            return { success: false, error: 'No transfer in progress' };
        }
        // Return that confirmation is needed
        return {
            success: false,
            needsConfirmation: true,
            mode: mode || activeMode,
            message: `Cancel ${(mode || activeMode).toUpperCase()} transfer?`
        };
    }

    // Determine which mode to cancel
    const targetMode = mode || getActiveTransferMode();
    if (!targetMode) {
        return { success: false, error: 'No transfer in progress' };
    }

    console.log(`Cancelling ${targetMode} transfer (confirmed)...`);

    if (targetMode === 'wifi') {
        wifiState.cancelRequested = true;
        wifiState.inProgress = false;
        wifiState.phoneUnreachable = false;
        stopWifiHeartbeat();

        // Kill all active HTTP connections — every in-flight download dies instantly
        destroyAllConnections();

        // Notify phone (best effort, new agent)
        if (wifiState.connection) {
            console.log('Notifying phone of WiFi cancellation...');
            notifyPhoneCancel(wifiState.connection.ip, wifiState.connection.port).catch(() => {});
            wifiState.connection = null;
        }

        return { success: true, mode: 'wifi' };

    } else if (targetMode === 'adb') {
        adbState.cancelRequested = true;
        adbState.inProgress = false;

        // Notify phone via socket (phone will cleanup its staging dir)
        if (adbState.socket) {
            console.log('Notifying phone of ADB cancellation...');
            try {
                adbState.socket.write(JSON.stringify({
                    type: 'CANCELLED',
                    reason: 'User cancelled transfer on PC'
                }) + '\n');
            } catch (e) {
                console.log('Failed to send cancel message:', e.message);
            }
            // Close the socket immediately so phone can't send more data
            try { adbState.socket.destroy(); } catch (e) {}
        }

        // Kill all active adb pull processes immediately
        if (adbState.activeChildren && adbState.activeChildren.size > 0) {
            console.log(`Killing ${adbState.activeChildren.size} active ADB processes...`);
            for (const [pid, info] of adbState.activeChildren) {
                try {
                    if (process.platform === 'win32') {
                        require('child_process').execSync(`taskkill /pid ${pid} /T /F`, {
                            windowsHide: true,
                            timeout: 5000
                        });
                    } else {
                        process.kill(-pid, 'SIGKILL');
                    }
                } catch (e) {}
                try { info.child.kill('SIGKILL'); } catch (e) {}
            }
            adbState.activeChildren.clear();
        }

        // Close TCP server immediately to prevent new connections
        if (adbState.server) {
            console.log('Closing ADB TCP server...');
            try { adbState.server.close(); } catch (e) {}
            adbState.server = null;
        }

        // Remove adb reverse port mapping so phone can't reconnect
        if (adbState.device) {
            try {
                const adbPath = getADBPath();
                require('child_process').execSync(
                    `"${adbPath}" -s "${adbState.device}" reverse --remove tcp:${CONFIG.ADB_PORT}`,
                    { timeout: 5000, windowsHide: true }
                );
                console.log('ADB reverse port mapping removed');
            } catch (e) {
                console.log('Failed to remove adb reverse:', e.message);
            }
        }

        return { success: true, mode: 'adb' };
    }

    return { success: false, error: 'Unknown mode' };
});

/**
 * Get current transfer status for all modes
 */
ipcMain.handle('get-transfer-status', () => {
    return {
        wifi: { inProgress: wifiState.inProgress },
        adb: { inProgress: adbState.inProgress },
        anyActive: isAnyTransferInProgress(),
        activeMode: getActiveTransferMode()
    };
});

ipcMain.handle('open-folder', (event, folderPath) => {
    shell.openPath(folderPath);
});

// ==================== Helper Functions ====================

function sendProgress(message) {
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('status-update', message);
    }
}

/**
 * Notify phone about download progress (for sync display)
 * Fire-and-forget for performance
 */
function notifyPhoneProgress(ip, port, done, total, currentFile) {
    const encodedFile = encodeURIComponent(currentFile);
    const url = `http://${ip}:${port}/progress?done=${done}&total=${total}&current=${encodedFile}`;

    // Fire and forget - don't wait for response
    http.get(url, { timeout: 2000, agent: keepAliveAgent }, () => {}).on('error', () => {});
}

/**
 * Notify phone that transfer is COMPLETE
 * Uses multiple attempts for reliability
 */
function notifyPhoneComplete(ip, port, failedFiles = []) {
    // Build URL with failed files info if any
    let url = `http://${ip}:${port}/complete`;
    if (failedFiles.length > 0) {
        const failedParam = encodeURIComponent(JSON.stringify(failedFiles));
        url += `?failedFiles=${failedParam}`;
    }

    // Fire and forget - INSTANT notification, don't wait for response
    http.get(url, { timeout: 2000, agent: keepAliveAgent }, () => {}).on('error', () => {});

    // Send multiple times to ensure delivery (UDP-style reliability)
    setTimeout(() => {
        http.get(url, { timeout: 1000, agent: keepAliveAgent }, () => {}).on('error', () => {});
    }, 100);

    // Also clear session on phone after a short delay (ensures complete is processed first)
    setTimeout(() => {
        notifyPhoneClearSession(ip, port);
    }, 500);
}

/**
 * Notify phone to clear all session data
 * Called after successful transfer to remove all traces
 */
function notifyPhoneClearSession(ip, port) {
    const url = `http://${ip}:${port}/clear-session`;

    // Fire and forget
    http.get(url, { timeout: 2000, agent: keepAliveAgent }, (res) => {
        res.resume(); // drain response
        res.on('error', () => {}); // ignore errors
        console.log('Phone session cleared');
    }).on('error', () => {
        // Ignore errors - phone might have already closed server
    });
}

/**
 * Notify phone that transfer was CANCELLED by PC
 * Uses retries to ensure delivery - CRITICAL for sync
 */
async function notifyPhoneCancel(ip, port) {
    const url = `http://${ip}:${port}/cancel`;

    for (let attempt = 0; attempt < CONFIG.CANCEL_RETRY_COUNT; attempt++) {
        try {
            await new Promise((resolve, reject) => {
                const req = http.get(url, { timeout: CONFIG.HEARTBEAT_TIMEOUT, agent: keepAliveAgent }, (res) => {
                    res.on('data', () => {}); // Consume response
                    res.on('end', () => {
                        console.log(`Cancel notification sent successfully (attempt ${attempt + 1})`);
                        resolve();
                    });
                    res.on('error', reject);
                });
                req.on('error', reject);
                req.on('timeout', () => {
                    req.destroy();
                    reject(new Error('Timeout'));
                });
            });
            return; // Success, exit
        } catch (e) {
            console.log(`Cancel notification attempt ${attempt + 1} failed: ${e.message}`);
            if (attempt < CONFIG.CANCEL_RETRY_COUNT - 1) {
                await new Promise(r => setTimeout(r, CONFIG.CANCEL_RETRY_DELAY));
            }
        }
    }
    console.log('Failed to notify phone of cancellation after all retries');
}

/**
 * Notify phone that transfer encountered an ERROR on PC side
 */
function notifyPhoneError(ip, port, errorMessage) {
    const encodedError = encodeURIComponent(errorMessage);
    const url = `http://${ip}:${port}/error?message=${encodedError}`;

    // Send multiple times for reliability
    http.get(url, { timeout: 2000, agent: keepAliveAgent }, () => {}).on('error', () => {});
    setTimeout(() => {
        http.get(url, { timeout: 1000, agent: keepAliveAgent }, () => {}).on('error', () => {});
    }, 100);
}

/**
 * Quick health check — is the phone's HTTP server still alive?
 * Returns true if alive, false if dead. 3-second timeout.
 */
function isPhoneAlive(ip, port) {
    return new Promise((resolve) => {
        const req = http.get(`http://${ip}:${port}/info`, { timeout: 3000 }, (res) => {
            res.on('error', () => resolve(false));
            res.resume(); // drain response
            resolve(true);
        });
        req.on('error', () => resolve(false));
        req.on('timeout', () => { req.destroy(); resolve(false); });
    });
}

/**
 * Called when a download fails with a network error.
 * Checks phone health — if dead, aborts the entire transfer and resets UI.
 */
async function handleNetworkFailure(ip, port) {
    if (wifiState.cancelRequested) return; // Already aborting

    const alive = await isPhoneAlive(ip, port);
    if (!alive) {
        console.log('Phone health check failed — phone is dead, aborting transfer');
        wifiState.phoneUnreachable = true;
        wifiState.cancelRequested = true;
        wifiState.inProgress = false;
        wifiState.connection = null;
        stopWifiHeartbeat();
        destroyAllConnections(); // Kill all in-flight downloads instantly
        if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.webContents.send('transfer-error', {
                mode: 'wifi',
                message: 'Phone disconnected. Transfer aborted.'
            });
        }
    }
}

/**
 * Data-flow-based heartbeat — monitors whether bytes are actually flowing
 * instead of pinging /info (which competes with file downloads).
 *
 * How it works:
 * - Every download updates wifiState.lastActivityAt when data chunks arrive
 * - Heartbeat checks: has data flowed in the last DATA_STALL_TIMEOUT ms?
 * - If data is flowing → phone is alive, no action needed
 * - If data stalled → try one /info ping to confirm phone is actually dead
 * - Only if BOTH data stalled AND ping fails → declare dead
 *
 * This handles all cases:
 * - 10,000 small files: lastActivityAt updates constantly
 * - One 10GB file taking 10 minutes: lastActivityAt updates on every chunk (~every few ms)
 * - Phone actually disconnected: data stalls, ping fails, transfer aborted
 */
const DATA_STALL_TIMEOUT = 120000; // 2 minutes — no data at all means something is wrong

function startWifiHeartbeat(ip, port, onDead) {
    if (wifiState.heartbeatInterval) {
        clearInterval(wifiState.heartbeatInterval);
    }

    let pingFailures = 0;

    wifiState.heartbeatInterval = setInterval(async () => {
        if (!wifiState.inProgress || wifiState.cancelRequested) {
            clearInterval(wifiState.heartbeatInterval);
            wifiState.heartbeatInterval = null;
            return;
        }

        const silenceMs = Date.now() - wifiState.lastActivityAt;

        if (silenceMs < DATA_STALL_TIMEOUT) {
            // Data is flowing — phone is alive, reset any ping failure count
            pingFailures = 0;
            return;
        }

        // Data has stalled for 2+ minutes — confirm with /info ping before killing
        console.log(`WiFi data stall detected (${(silenceMs / 1000).toFixed(0)}s since last data). Pinging phone...`);

        try {
            await httpGet(`http://${ip}:${port}/info`, CONFIG.HEARTBEAT_TIMEOUT);
            // Phone responded — it's alive but slow. Reset stall timer.
            wifiState.lastActivityAt = Date.now();
            pingFailures = 0;
            console.log('Phone responded to ping — still alive, resetting stall timer');
        } catch (e) {
            pingFailures++;
            console.log(`Phone ping failed (${pingFailures}/2): ${e.message}`);

            // Require 2 consecutive ping failures after data stall to declare dead
            // (one failure could be a momentary network hiccup)
            if (pingFailures >= 2) {
                console.log('Phone confirmed unreachable — data stalled and ping failed twice');
                clearInterval(wifiState.heartbeatInterval);
                wifiState.heartbeatInterval = null;

                if (onDead) {
                    onDead();
                }
            }
        }
    }, CONFIG.HEARTBEAT_INTERVAL);
}

/**
 * Stop WiFi heartbeat monitoring
 */
function stopWifiHeartbeat() {
    if (wifiState.heartbeatInterval) {
        clearInterval(wifiState.heartbeatInterval);
        wifiState.heartbeatInterval = null;
    }
}

function httpGet(url, timeout = 30000) {
    return new Promise((resolve, reject) => {
        const urlObj = new URL(url);
        const client = urlObj.protocol === 'https:' ? https : http;

        let settled = false;
        const settle = (fn, val) => { if (!settled) { settled = true; fn(val); } };

        const req = client.get(url, { timeout, agent: keepAliveAgent }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    settle(resolve, JSON.parse(data));
                } catch (e) {
                    settle(reject, new Error('Invalid JSON response'));
                }
            });
            res.on('error', (e) => {
                settle(reject, new Error(friendlyNetworkError(e, urlObj.hostname)));
            });
        });

        req.on('error', (err) => {
            settle(reject, new Error(friendlyNetworkError(err, urlObj.hostname)));
        });
        req.on('timeout', () => {
            req.destroy();
            settle(reject, new Error(`Connection timeout (${timeout}ms) - phone may be unreachable`));
        });
    });
}

/**
 * Retry wrapper for httpGet - retries on transient network errors
 */
async function httpGetWithRetry(url, timeout = 30000, retries = CONFIG.CONNECT_RETRY_COUNT) {
    let lastError;
    for (let attempt = 1; attempt <= retries; attempt++) {
        try {
            return await httpGet(url, timeout);
        } catch (e) {
            lastError = e;
            console.log(`httpGet attempt ${attempt}/${retries} failed: ${e.message}`);
            if (attempt < retries) {
                await new Promise(r => setTimeout(r, CONFIG.CONNECT_RETRY_DELAY));
            }
        }
    }
    throw lastError;
}

/**
 * Convert raw network errors into user-friendly messages
 */
function friendlyNetworkError(err, host) {
    const code = err.code || '';
    const msg = err.message || '';

    if (code === 'EHOSTUNREACH' || msg.includes('EHOSTUNREACH')) {
        return `Host unreachable (${host}). Your PC and phone are likely on different networks. ` +
               `Try: 1) Connect PC to phone's WiFi hotspot, or 2) Ensure both are on the same WiFi network with no client isolation.`;
    }
    if (code === 'ECONNREFUSED' || msg.includes('ECONNREFUSED')) {
        return `Connection refused (${host}). The phone app may not be running or the server hasn't started yet. ` +
               `Make sure you pressed "Start Transfer" on the phone.`;
    }
    if (code === 'ETIMEDOUT' || msg.includes('ETIMEDOUT')) {
        return `Connection timed out (${host}). The phone may be unreachable or a firewall is blocking the connection.`;
    }
    if (code === 'ECONNRESET' || msg.includes('ECONNRESET')) {
        return `Connection reset (${host}). The phone may have closed the connection or restarted.`;
    }
    if (code === 'ENETUNREACH' || msg.includes('ENETUNREACH')) {
        return `Network unreachable. No route to ${host}. Check your WiFi/hotspot connection.`;
    }
    return msg;
}

// ==================== TAR BUNDLE DOWNLOAD ====================

/**
 * Parse a 512-byte tar header buffer into {name, size} or null for end-of-archive.
 */
function parseTarHeader(headerBuf) {
    // End of archive: all zeros
    if (headerBuf.every(b => b === 0)) return null;

    let name = headerBuf.slice(0, 100).toString('utf8').replace(/\0+$/, '');
    const prefix = headerBuf.slice(345, 500).toString('utf8').replace(/\0+$/, '');
    if (prefix) name = prefix + '/' + name;

    const sizeStr = headerBuf.slice(124, 136).toString('utf8').replace(/\0+$/, '').trim();
    const size = parseInt(sizeStr, 8) || 0;

    return { name, size };
}

/**
 * Download a tar bundle from the phone and extract files to disk.
 * Streaming state-machine parser — writes files directly as data arrives.
 * @param {string} ip - Phone IP
 * @param {number} port - Phone port
 * @param {number[]} indices - Manifest indices to bundle
 * @param {string} destination - Destination directory
 * @param {Function} onFileExtracted - Callback(sanitizedPath, rawPath, bytes) per file
 * @returns {Promise<{filesExtracted, bytesTotal}>}
 */
function downloadTarBundle(ip, port, indices, destination, onFileExtracted) {
    return new Promise((resolve, reject) => {
        const url = `http://${ip}:${port}/tar-bundle?indices=${indices.join(',')}`;

        let settled = false;
        const settle = (fn, val) => { if (!settled) { settled = true; fn(val); } };

        // State machine
        let state = 'header';
        let headerBuf = Buffer.alloc(0);
        let currentHeader = null;
        let currentFd = null;
        let bytesRemaining = 0;
        let paddingRemaining = 0;
        let filesExtracted = 0;
        let bytesTotal = 0;

        const cleanup = () => {
            if (currentFd !== null) { try { fs.closeSync(currentFd); } catch(_){} currentFd = null; }
        };

        const req = http.get(url, { timeout: 300000, agent: keepAliveAgent }, (res) => {
            if (res.statusCode !== 200) {
                settle(reject, new Error(`HTTP ${res.statusCode} for tar bundle`));
                return;
            }

            res.on('data', (chunk) => {
                wifiState.lastActivityAt = Date.now(); // Data flowing = phone alive
                let offset = 0;

                while (offset < chunk.length && state !== 'done') {
                    if (state === 'header') {
                        const needed = TAR_HEADER_SIZE - headerBuf.length;
                        const available = Math.min(needed, chunk.length - offset);
                        headerBuf = Buffer.concat([headerBuf, chunk.slice(offset, offset + available)]);
                        offset += available;

                        if (headerBuf.length === TAR_HEADER_SIZE) {
                            currentHeader = parseTarHeader(headerBuf);
                            headerBuf = Buffer.alloc(0);

                            if (!currentHeader) { state = 'done'; break; }

                            if (currentHeader.size === 0) {
                                // Empty file — still create it on disk and track it
                                const relPath = sanitizeRelPath(currentHeader.name);
                                const destPath = safeDestPath(path.join(destination, relPath));
                                try {
                                    fs.mkdirSync(path.dirname(destPath), { recursive: true });
                                    fs.writeFileSync(destPath, '');
                                    filesExtracted++;
                                    onFileExtracted(relPath, currentHeader.name, 0);
                                } catch (emptyErr) {
                                    console.error(`Failed to create empty file ${relPath}: ${emptyErr.message}`);
                                }
                                state = 'header';
                            } else {
                                const relPath = sanitizeRelPath(currentHeader.name);
                                const destPath = safeDestPath(path.join(destination, relPath));
                                fs.mkdirSync(path.dirname(destPath), { recursive: true });
                                currentFd = fs.openSync(destPath, 'w');
                                bytesRemaining = currentHeader.size;
                                state = 'data';
                            }
                        }
                    } else if (state === 'data') {
                        const toWrite = Math.min(bytesRemaining, chunk.length - offset);
                        fs.writeSync(currentFd, chunk, offset, toWrite);
                        offset += toWrite;
                        bytesRemaining -= toWrite;

                        if (bytesRemaining === 0) {
                            fs.closeSync(currentFd);
                            currentFd = null;
                            filesExtracted++;
                            bytesTotal += currentHeader.size;
                            onFileExtracted(
                                sanitizeRelPath(currentHeader.name),
                                currentHeader.name,
                                currentHeader.size
                            );

                            const remainder = currentHeader.size % 512;
                            if (remainder !== 0) {
                                paddingRemaining = 512 - remainder;
                                state = 'padding';
                            } else {
                                state = 'header';
                            }
                        }
                    } else if (state === 'padding') {
                        const toSkip = Math.min(paddingRemaining, chunk.length - offset);
                        offset += toSkip;
                        paddingRemaining -= toSkip;
                        if (paddingRemaining === 0) state = 'header';
                    }
                }
            });

            res.on('end', () => { cleanup(); settle(resolve, { filesExtracted, bytesTotal }); });
            res.on('error', (e) => { cleanup(); settle(reject, e); });
        });

        req.on('error', (e) => { cleanup(); settle(reject, new Error(friendlyNetworkError(e, ip))); });
        req.on('timeout', () => { req.destroy(); cleanup(); settle(reject, new Error('Tar bundle download timeout')); });
    });
}

// Download file by INDEX - simple, no encoding issues!
// Includes built-in retry for transient network errors
async function downloadFileByIndex(ip, port, fileIndex, destPath, timeout = 300000) {
    let lastError;

    for (let attempt = 1; attempt <= CONFIG.FILE_RETRY_COUNT; attempt++) {
        // Break out of retry loop if transfer was cancelled or phone is unreachable
        if (wifiState.cancelRequested) {
            throw new Error('Transfer cancelled');
        }

        try {
            return await _downloadFileOnce(ip, port, fileIndex, destPath, timeout);
        } catch (e) {
            lastError = e;

            // Only retry on network errors, not HTTP 4xx errors
            const isNetworkError = e.message.includes('EHOSTUNREACH') ||
                                   e.message.includes('ECONNRESET') ||
                                   e.message.includes('ECONNREFUSED') ||
                                   e.message.includes('ETIMEDOUT') ||
                                   e.message.includes('timeout') ||
                                   e.message.includes('socket hang up');
            if (!isNetworkError || attempt >= CONFIG.FILE_RETRY_COUNT) break;
            await new Promise(r => setTimeout(r, CONFIG.FILE_RETRY_DELAY));
        }
    }
    throw lastError;
}

function _downloadFileOnce(ip, port, fileIndex, destPath, timeout) {
    return new Promise((resolve, reject) => {
        const url = `http://${ip}:${port}/file?index=${fileIndex}`;
        let settled = false;
        const settle = (fn, val) => { if (!settled) { settled = true; fn(val); } };

        const file = fs.createWriteStream(destPath, { highWaterMark: 2 * 1024 * 1024 });
        let bytes = 0;

        // Register error handler IMMEDIATELY — before file open completes
        // (if destPath has illegal chars, open fails before HTTP callback fires)
        file.on('error', (e) => {
            fs.unlink(destPath, () => {});
            settle(reject, e);
        });

        const req = http.get(url, { timeout, agent: keepAliveAgent }, (res) => {
            // Check HTTP status
            if (res.statusCode !== 200) {
                file.close();
                fs.unlink(destPath, () => {});
                settle(reject, new Error(`HTTP ${res.statusCode} for file index ${fileIndex}`));
                return;
            }

            res.on('data', (chunk) => {
                bytes += chunk.length;
                wifiState.lastActivityAt = Date.now(); // Data flowing = phone alive
            });

            // CRITICAL: pipe does NOT propagate errors from readable to writable.
            // Without this, if socket dies mid-download, Promise hangs forever.
            res.on('error', (e) => {
                file.close();
                fs.unlink(destPath, () => {});
                settle(reject, new Error(friendlyNetworkError(e, ip)));
            });

            res.pipe(file);

            file.on('finish', () => {
                file.close();
                settle(resolve, bytes);
            });
        });

        req.on('error', (e) => {
            file.close();
            fs.unlink(destPath, () => {});
            settle(reject, new Error(friendlyNetworkError(e, ip)));
        });

        req.on('timeout', () => {
            req.destroy();
            file.close();
            fs.unlink(destPath, () => {});
            settle(reject, new Error('Download timeout'));
        });
    });
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GB';
}
