const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
    // Folder operations
    browseFolder: (currentPath) => ipcRenderer.invoke('browse-folder', currentPath),
    getDefaultPath: () => ipcRenderer.invoke('get-default-path'),
    openFolder: (path) => ipcRenderer.invoke('open-folder', path),

    // WiFi operations
    scanPhone: () => ipcRenderer.invoke('scan-phone'),
    scanPhoneBluetooth: () => ipcRenderer.invoke('scan-phone-bluetooth'),
    checkPhone: (ip) => ipcRenderer.invoke('check-phone', ip),
    checkPhoneWithPort: (ip, port) => ipcRenderer.invoke('check-phone-with-port', { ip, port }),

    // ADB Settings
    getAdbStatus: () => ipcRenderer.invoke('get-adb-status'),
    browseAdb: () => ipcRenderer.invoke('browse-adb'),
    resetAdbPath: () => ipcRenderer.invoke('reset-adb-path'),
    refreshAdbStatus: () => ipcRenderer.invoke('refresh-adb-status'),

    // ADB operations
    getAdbDevices: () => ipcRenderer.invoke('get-adb-devices'),

    // Transfer operations
    startWifiTransfer: (options) => ipcRenderer.invoke('start-wifi-transfer', options),
    startAdbTransfer: (device, destination) => ipcRenderer.invoke('start-adb-transfer', { device, destination }),
    cancelTransfer: (options) => ipcRenderer.invoke('cancel-transfer', options || {}),
    getTransferStatus: () => ipcRenderer.invoke('get-transfer-status'),

    // Send to Phone operations (PC → Phone)
    selectFilesToSend: () => ipcRenderer.invoke('select-files-to-send'),
    selectFolderToSend: () => ipcRenderer.invoke('select-folder-to-send'),
    resolveDroppedPaths: (paths) => ipcRenderer.invoke('resolve-dropped-paths', paths),
    sendToPhoneAdb: (options) => ipcRenderer.invoke('send-to-phone-adb', options),
    sendToPhoneWifi: (options) => ipcRenderer.invoke('send-to-phone-wifi', options),

    // Event listeners
    onProgress: (callback) => ipcRenderer.on('transfer-progress', (event, data) => callback(data)),
    onStatusUpdate: (callback) => ipcRenderer.on('status-update', (event, message) => callback(message)),
    onTransferError: (callback) => ipcRenderer.on('transfer-error', (event, error) => callback(error)),
    removeAllListeners: () => {
        ipcRenderer.removeAllListeners('transfer-progress');
        ipcRenderer.removeAllListeners('status-update');
        ipcRenderer.removeAllListeners('transfer-error');
    }
});
