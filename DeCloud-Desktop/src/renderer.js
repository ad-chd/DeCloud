// DeCloud Desktop - Renderer Process
let currentMode = 'wifi';
let currentDirection = 'receive';  // 'receive' = Phone→PC, 'send' = PC→Phone
let isTransferring = false;
let transferGeneration = 0;  // Incremented on each new transfer, prevents stale cancel results from corrupting UI
let sendFilesList = [];  // Files selected to send to phone

const elements = {
    wifiModeBtn: document.getElementById('wifiModeBtn'),
    adbModeBtn: document.getElementById('adbModeBtn'),
    wifiPanel: document.getElementById('wifiPanel'),
    adbPanel: document.getElementById('adbPanel'),
    ipInput: document.getElementById('ipInput'),
    portInput: document.getElementById('portInput'),
    scanBtn: document.getElementById('scanBtn'),
    btScanBtn: document.getElementById('btScanBtn'),
    connectBtn: document.getElementById('connectBtn'),
    connectionStatus: document.getElementById('connectionStatus'),
    deviceSelect: document.getElementById('deviceSelect'),
    refreshBtn: document.getElementById('refreshBtn'),
    destInput: document.getElementById('destInput'),
    browseBtn: document.getElementById('browseBtn'),
    statusIcon: document.getElementById('statusIcon'),
    statusText: document.getElementById('statusText'),
    progressBar: document.getElementById('progressBar'),
    filesProgress: document.getElementById('filesProgress'),
    speedDisplay: document.getElementById('speedDisplay'),
    currentFile: document.getElementById('currentFile'),
    startBtn: document.getElementById('startBtn'),
    cancelBtn: document.getElementById('cancelBtn'),
    logContainer: document.getElementById('logContainer'),
    // ADB Settings elements
    adbStatusValue: document.getElementById('adbStatusValue'),
    adbSourceBadge: document.getElementById('adbSourceBadge'),
    adbVersionValue: document.getElementById('adbVersionValue'),
    adbPathValue: document.getElementById('adbPathValue'),
    browseAdbBtn: document.getElementById('browseAdbBtn'),
    resetAdbBtn: document.getElementById('resetAdbBtn'),
    // Direction toggle elements
    receiveBtn: document.getElementById('receiveBtn'),
    sendBtn: document.getElementById('sendBtn'),
    subtitleText: document.getElementById('subtitleText'),
    // About modal
    appNameTitle: document.getElementById('appNameTitle'),
    aboutOverlay: document.getElementById('aboutOverlay'),
    aboutEmail: document.getElementById('aboutEmail'),
    aboutHint: document.getElementById('aboutHint'),
    aboutClose: document.getElementById('aboutClose'),
    // Send mode elements
    saveLocationSection: document.getElementById('saveLocationSection'),
    sendFilesSection: document.getElementById('sendFilesSection'),
    addFilesBtn: document.getElementById('addFilesBtn'),
    addFolderBtn: document.getElementById('addFolderBtn'),
    clearFilesBtn: document.getElementById('clearFilesBtn'),
    sendFileList: document.getElementById('sendFileList'),
    sendFileCount: document.getElementById('sendFileCount'),
    sendTotalSize: document.getElementById('sendTotalSize'),
};

async function init() {
    const defaultPath = await window.api.getDefaultPath();
    elements.destInput.value = defaultPath;
    setupEventListeners();
    setupIPCListeners();

    // Hide ADB settings in default WiFi mode
    const adbSettingsPanel = document.getElementById('adbSettingsPanel');
    if (adbSettingsPanel) adbSettingsPanel.classList.add('hidden');

    // Load ADB status on startup (runs in background)
    await loadAdbStatus();

    log('DeCloud ready');
}

function setupEventListeners() {
    elements.wifiModeBtn.addEventListener('click', () => setMode('wifi'));
    elements.adbModeBtn.addEventListener('click', () => setMode('adb'));
    elements.scanBtn.addEventListener('click', scanForPhone);
    elements.btScanBtn.addEventListener('click', btScanForPhone);
    elements.connectBtn.addEventListener('click', manualConnect);
    elements.refreshBtn.addEventListener('click', refreshDevices);
    elements.browseBtn.addEventListener('click', browseFolder);
    elements.startBtn.addEventListener('click', startTransfer);
    elements.cancelBtn.addEventListener('click', cancelTransfer);

    // ADB Settings listeners
    elements.browseAdbBtn.addEventListener('click', browseAdbPath);
    elements.resetAdbBtn.addEventListener('click', resetAdbPath);

    // About modal — tap "DeCloud" to open, click outside or Close to dismiss.
    if (elements.appNameTitle) {
        elements.appNameTitle.addEventListener('click', openAboutModal);
    }
    if (elements.aboutClose) {
        elements.aboutClose.addEventListener('click', closeAboutModal);
    }
    if (elements.aboutOverlay) {
        elements.aboutOverlay.addEventListener('click', (e) => {
            // Close only when clicking the backdrop, not the card itself.
            if (e.target === elements.aboutOverlay) closeAboutModal();
        });
    }
    if (elements.aboutEmail) {
        elements.aboutEmail.addEventListener('click', copyDeveloperEmail);
    }
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && elements.aboutOverlay && !elements.aboutOverlay.hidden) {
            closeAboutModal();
        }
    });

    // ADB Settings collapse/expand toggle
    const adbToggle = document.getElementById('adbSettingsToggle');
    if (adbToggle) {
        adbToggle.addEventListener('click', () => {
            document.getElementById('adbSettingsPanel')?.classList.toggle('collapsed');
        });
    }

    // Direction toggle listeners
    elements.receiveBtn.addEventListener('click', () => setDirection('receive'));
    elements.sendBtn.addEventListener('click', () => setDirection('send'));

    // Send mode file selection listeners
    elements.addFilesBtn.addEventListener('click', addFilesToSend);
    elements.addFolderBtn.addEventListener('click', addFolderToSend);
    elements.clearFilesBtn.addEventListener('click', clearSendFiles);

    // Drag & drop on send files area
    setupDragAndDrop();

}

// Manual connect with custom IP:Port
async function manualConnect() {
    const ip = elements.ipInput.value.trim();
    const port = elements.portInput.value.trim() || '64666';

    if (!ip) {
        showConnectionStatus('Please enter IP address', 'error');
        return;
    }

    elements.connectBtn.disabled = true;
    elements.connectBtn.textContent = 'Connecting...';
    showConnectionStatus('Connecting...', 'pending');
    log(`Connecting to ${ip}...`);

    try {
        const result = await window.api.checkPhoneWithPort(ip, port);
        if (result.success) {
            showConnectionStatus('Phone Connected', 'success');
            log(`Connected to phone: ${result.info.fileCount} files ready`, 'success');
            // Trigger guided steps for first-time users
            triggerGuideOnConnect('wifi');
        } else {
            showConnectionStatus(`Connection failed: ${result.error}`, 'error');
            log(`Connection failed: ${result.error}`, 'error');
        }
    } catch (e) {
        showConnectionStatus(`Error: ${e.message}`, 'error');
        log(`Error: ${e.message}`, 'error');
    }

    elements.connectBtn.disabled = false;
    elements.connectBtn.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg> Connect`;
}

function showConnectionStatus(message, type) {
    const el = elements.connectionStatus;
    el.style.display = 'block';
    el.textContent = message;
    el.style.background = type === 'success' ? 'rgba(48,209,88,0.12)' : type === 'error' ? 'rgba(255,69,58,0.12)' : 'rgba(10,132,255,0.12)';
    el.style.color = type === 'success' ? '#30d158' : type === 'error' ? '#ff453a' : '#409cff';
}

function setupIPCListeners() {
    window.api.onProgress((data) => updateProgress(data));
    window.api.onStatusUpdate((message) => { setStatus(message); log(message); });
    window.api.onTransferError((error) => {
        // Phone disconnected or fatal error — full reset like cancel
        resetProgressUI();
        setTransferState(false);
        elements.logContainer.innerHTML = '';
        log(error.message, 'error');
        setStatus('Ready');
    });
}

function setMode(mode) {
    currentMode = mode;
    elements.wifiModeBtn.classList.toggle('active', mode === 'wifi');
    elements.adbModeBtn.classList.toggle('active', mode === 'adb');
    elements.wifiPanel.classList.toggle('hidden', mode !== 'wifi');
    elements.adbPanel.classList.toggle('hidden', mode !== 'adb');

    // Hide ADB settings panel in WiFi mode — keep it simple
    const adbSettingsPanel = document.getElementById('adbSettingsPanel');
    if (adbSettingsPanel) {
        adbSettingsPanel.classList.toggle('hidden', mode === 'wifi');
    }

    const modeNames = { wifi: 'WiFi', adb: 'USB' };
    log(`Switched to ${modeNames[mode]} mode`);

    if (mode === 'adb') refreshDevices();
}

async function scanForPhone() {
    // Disable all connection buttons during scan to prevent concurrent actions
    elements.scanBtn.disabled = true;
    elements.btScanBtn.disabled = true;
    elements.connectBtn.disabled = true;
    elements.scanBtn.textContent = 'Scanning...';
    setStatus('Scanning for phone...');
    showConnectionStatus('Scanning...', 'pending');
    log('Scanning for phone on your network...');

    try {
        const result = await window.api.scanPhone();
        if (result.success) {
            elements.ipInput.value = result.ip;
            if (result.port) {
                elements.portInput.value = result.port;
            }
            setStatus('Phone found!', 'success');
            log(`Phone found${result.deviceName ? ' (' + result.deviceName + ')' : ''}`, 'success');

            // Reset scan button before connecting so UI doesn't show both active
            resetScanButtons();

            // Now auto-connect to the discovered phone
            await manualConnect();
            return;
        } else {
            const errorMsg = result.error || 'Phone not found. Try BT Scan or enter IP manually.';
            setStatus('Not found', 'error');
            log(errorMsg, 'error');
            showConnectionStatus(errorMsg, 'error');
        }
    } catch (e) {
        setStatus('Scan failed', 'error');
        log(`Error: ${e.message}`, 'error');
        showConnectionStatus('Scan failed. Try BT Scan or enter IP manually.', 'error');
    }

    resetScanButtons();
}

async function btScanForPhone() {
    // Disable all connection buttons during scan
    elements.scanBtn.disabled = true;
    elements.btScanBtn.disabled = true;
    elements.connectBtn.disabled = true;
    elements.btScanBtn.textContent = 'Scanning...';
    setStatus('Scanning Bluetooth devices...');
    showConnectionStatus('Scanning via Bluetooth...', 'pending');
    log('Scanning for phone via Bluetooth...');

    try {
        const result = await window.api.scanPhoneBluetooth();
        if (result.success) {
            elements.ipInput.value = result.ip;
            if (result.port) {
                elements.portInput.value = result.port;
            }
            setStatus('Phone found via Bluetooth!', 'success');
            log(`Found phone via BT: ${result.ip} (${result.deviceName || 'Unknown'})`, 'success');

            // Reset scan buttons before connecting
            resetScanButtons();

            // Now auto-connect to the discovered phone
            await manualConnect();
            return;
        } else {
            const errorMsg = result.error || 'Phone not found via Bluetooth.';
            setStatus('BT scan: not found', 'error');
            log(errorMsg, 'error');
            showConnectionStatus(errorMsg, 'error');
        }
    } catch (e) {
        setStatus('BT scan failed', 'error');
        log(`BT scan error: ${e.message}`, 'error');
        showConnectionStatus('Bluetooth scan failed. Make sure devices are paired.', 'error');
    }

    resetScanButtons();
}

function resetScanButtons() {
    elements.scanBtn.disabled = false;
    elements.btScanBtn.disabled = false;
    elements.connectBtn.disabled = false;
    elements.scanBtn.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg> Auto-Scan`;
    elements.btScanBtn.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6.5 6.5l11 11L12 23V1l5.5 5.5-11 11"/></svg> BT Scan`;
}

async function checkConnection(ip) {
    try {
        const result = await window.api.checkPhone(ip);
        if (result.success) {
            showConnectionStatus('Phone Connected', 'success');
            log(`Connected! ${result.info.fileCount} files (${result.info.totalSizeFormatted})`, 'success');
            triggerGuideOnConnect('wifi');
        }
    } catch (e) {}
}

async function refreshDevices() {
    elements.refreshBtn.disabled = true;
    elements.deviceSelect.innerHTML = '<option value="">Scanning...</option>';
    log('Scanning for USB devices...');

    try {
        const result = await window.api.getAdbDevices();
        if (result.success && result.devices.length > 0) {
            elements.deviceSelect.innerHTML = result.devices.map(d => `<option value="${d.serial}">${d.model} (${d.serial})</option>`).join('');
            log(`Found ${result.devices.length} device(s)`, 'success');
            triggerGuideOnConnect('adb');
        } else if (result.success) {
            elements.deviceSelect.innerHTML = '<option value="">No devices found</option>';
            log('No USB devices found. Check USB connection.', 'error');
        } else {
            elements.deviceSelect.innerHTML = '<option value="">Connection error</option>';
            log(`USB error: ${result.error}`, 'error');
        }
    } catch (e) {
        elements.deviceSelect.innerHTML = '<option value="">Error</option>';
        log(`Error: ${e.message}`, 'error');
    }
    elements.refreshBtn.disabled = false;
}

async function browseFolder() {
    const currentPath = elements.destInput.value.trim() || null;
    const folder = await window.api.browseFolder(currentPath);
    if (folder) { elements.destInput.value = folder; log(`Save location: ${folder}`); }
}

async function startTransfer() {
    // ==================== SEND MODE (PC → Phone) ====================
    if (currentDirection === 'send') {
        if (sendFilesList.length === 0) {
            log('Please select files or folders to send', 'error');
            return;
        }

        // Get device based on mode
        let device = null;
        if (currentMode === 'adb') {
            device = elements.deviceSelect.value;
            if (!device) {
                log('Please select a device first', 'error');
                return;
            }
        } else if (currentMode === 'wifi') {
            const ip = elements.ipInput.value.trim();
            if (!ip) {
                log('Please enter phone IP or use Auto-Scan', 'error');
                return;
            }
        }

        await startSendToPhone();
        return;
    }

    // ==================== RECEIVE MODE (Phone → PC) ====================
    const destination = elements.destInput.value;
    if (!destination) { log('Please select a destination folder', 'error'); return; }

    if (currentMode === 'wifi') {
        const ip = elements.ipInput.value.trim();
        const port = elements.portInput.value.trim() || '64666';
        if (!ip) { log('Please enter phone IP or use Auto-Scan', 'error'); return; }
        await startWifiTransfer(ip, destination, port);
    } else if (currentMode === 'adb') {
        const device = elements.deviceSelect.value;
        if (!device) { log('Please select a USB device', 'error'); return; }
        await startAdbTransfer(device, destination);
    }
}

// ==================== Send to Phone Transfer ====================

async function startSendToPhone() {
    setTransferState(true);
    resetProgressUI();

    // Determine which method to use based on mode
    if (currentMode === 'wifi') {
        const ip = elements.ipInput.value.trim();
        const port = elements.portInput.value.trim() || '64666';
        setStatus('Connecting to phone...', 'active');
        log(`Sending ${sendFilesList.length} item(s) via WiFi to ${ip}...`);

        try {
            const result = await window.api.sendToPhoneWifi({
                ip,
                port,
                files: sendFilesList
            });

            if (result.success) {
                setStatus('Send Complete!', 'success');
                log(`Sent ${result.completed} files to phone in ${result.elapsed}s`, 'success');
                elements.progressBar.style.width = '100%';

                if (confirm(`Send complete!\n\n${result.completed} files sent to phone.\nDestination: Internal Storage/DeCloud/Received/\n\nClear selection?`)) {
                    sendFilesList = [];
                    updateSendFileListUI();
                }
            } else {
                setStatus('Send Failed', 'error');
                log(`Send failed: ${result.error}`, 'error');
            }
        } catch (e) {
            setStatus('Error', 'error');
            log(`Error: ${e.message}`, 'error');
        }
    } else {
        // ADB Push mode
        const device = elements.deviceSelect.value;
        setStatus('Sending to phone via USB...', 'active');
        log(`Sending ${sendFilesList.length} item(s) to phone via USB...`);

        try {
            const result = await window.api.sendToPhoneAdb({
                device,
                files: sendFilesList
            });

            if (result.success) {
                setStatus('Send Complete!', 'success');
                log(`Sent ${result.completed} files to phone in ${result.elapsed}s`, 'success');
                elements.progressBar.style.width = '100%';

                if (confirm(`Send complete!\n\n${result.completed} files sent to phone.\nDestination: Internal Storage/DeCloud/\n\nClear selection?`)) {
                    sendFilesList = [];
                    updateSendFileListUI();
                }
            } else {
                setStatus('Send Failed', 'error');
                log(`Send failed: ${result.error}`, 'error');
            }
        } catch (e) {
            setStatus('Error', 'error');
            log(`Error: ${e.message}`, 'error');
        }
    }

    setTransferState(false);
}

async function startWifiTransfer(ip, destination, port) {
    setTransferState(true);
    const myGeneration = ++transferGeneration;
    setStatus('Connecting...', 'active');
    resetProgressUI();
    log(`Connecting to ${ip}...`);

    try {
        const options = { ip, destination, port };
        const result = await window.api.startWifiTransfer(options);

        // If a newer transfer was started (after cancel), ignore this stale result
        if (myGeneration !== transferGeneration) return;

        if (result.success) {
            setStatus('Transfer Complete!', 'success');

            log(`Transfer complete! ${result.completed} files in ${result.elapsed}s`, 'success');

            // Show warning if any files were skipped on phone
            if (result.warning) {
                log(`Warning: ${result.warning}`, 'error');
            }

            elements.progressBar.style.width = '100%';

            // Format dialog message
            let dialogMsg = `Transfer complete!\n\n${result.completed} files transferred in ${result.elapsed}s.`;
            if (result.warning) {
                dialogMsg += `\n\nWarning: ${result.warning}`;
            }
            dialogMsg += '\n\nOpen destination folder?';

            if (confirm(dialogMsg)) {
                window.api.openFolder(destination);
            }

            // Reset UI for next transfer (keep mode & device selected)
            resetProgressUI();
            setStatus('Ready for next transfer', 'success');
        } else if (result.error === 'Cancelled') {
            // User cancelled — cancelTransfer() already did a full reset
            resetProgressUI();
            setTransferState(false);
            setStatus('Ready');
            return;
        } else {
            setStatus('Transfer Failed', 'error');
            log(`Transfer failed: ${result.error}`, 'error');

            // Show partial success info if available
            if (result.completed > 0) {
                log(`Partial: ${result.completed} files succeeded, ${result.failed} failed`, 'error');
            }

        }
    } catch (e) {
        setStatus('Error', 'error');
        log(`Error: ${e.message}`, 'error');
    }
    setTransferState(false);
}

async function startAdbTransfer(device, destination) {
    setTransferState(true);
    const myGeneration = ++transferGeneration;
    setStatus('Setting up USB connection...', 'active');
    resetProgressUI();
    log(`Using device: ${device}`);

    try {
        const result = await window.api.startAdbTransfer(device, destination);

        // If a newer transfer was started (after cancel), ignore this stale result
        if (myGeneration !== transferGeneration) return;

        if (result.cancelled) {
            // User cancelled — cancelTransfer() already did a full reset
            resetProgressUI();
            setTransferState(false);
            setStatus('Ready');
            return;
        } else if (result.success) {
            setStatus('Transfer Complete!', 'success');
            log(`Transfer complete! ${result.message || result.completed + ' files in ' + result.elapsed + 's'}`, 'success');
            elements.progressBar.style.width = '100%';
            if (confirm(`Transfer complete!\n\n${result.completed} files transferred.\n\nOpen destination folder?`)) {
                window.api.openFolder(destination);
            }

            // Reset UI for next transfer (keep mode & device selected)
            resetProgressUI();
            setStatus('Ready for next transfer', 'success');
        } else {
            setStatus('Transfer Failed', 'error');
            log(`Transfer failed: ${result.error || result.message}`, 'error');
            if (result.completed > 0) {
                log(`Partial: ${result.completed} files succeeded, ${result.failed} failed`, 'error');
            }
        }
    } catch (e) {
        setStatus('Error', 'error');
        log(`Error: ${e.message}`, 'error');
    }
    setTransferState(false);
}

async function cancelTransfer() {
    // First, check if confirmation is needed
    const checkResult = await window.api.cancelTransfer({ mode: currentMode, confirmed: false });

    if (checkResult.needsConfirmation) {
        // Show confirmation dialog
        const modeNames = { wifi: 'WiFi', adb: 'USB' };
        const modeName = modeNames[checkResult.mode] || checkResult.mode;

        if (!confirm(`Are you sure you want to cancel the ${modeName} transfer?\n\nThis will stop all progress and reset.`)) {
            log('Cancel aborted by user');
            return;
        }
    }

    // User confirmed - proceed with cancellation
    log(`Cancelling ${currentMode} transfer...`);
    const result = await window.api.cancelTransfer({ mode: currentMode, confirmed: true });

    if (result.success) {
        // Full clean reset — as if the app just launched
        resetProgressUI();
        setTransferState(false);
        elements.logContainer.innerHTML = '';
        log('Transfer cancelled. Ready for next transfer.', 'error');
        setStatus('Ready');
    } else {
        log(`Cancel failed: ${result.error}`, 'error');
    }
}

function setTransferState(transferring) {
    isTransferring = transferring;
    elements.startBtn.classList.toggle('hidden', transferring);
    elements.cancelBtn.classList.toggle('hidden', !transferring);
    elements.scanBtn.disabled = transferring;
    elements.btScanBtn.disabled = transferring;
    elements.refreshBtn.disabled = transferring;
    elements.browseBtn.disabled = transferring;
    if (!transferring) elements.currentFile.textContent = '';
    // Clear guide hints when transfer starts
    if (transferring) clearGuideHint();

    // Hide file list / save location during transfer to focus on progress
    if (transferring) {
        elements.saveLocationSection.classList.add('hidden');
        elements.sendFilesSection.classList.add('hidden');
        // Hide mode section and connection panels during transfer
        document.querySelector('.mode-section')?.classList.add('hidden');
        elements.wifiPanel.classList.add('hidden');
        elements.adbPanel.classList.add('hidden');
        document.getElementById('adbSettingsPanel')?.classList.add('hidden');
    } else {
        // Restore visibility based on current direction/mode
        elements.saveLocationSection.classList.toggle('hidden', currentDirection === 'send');
        elements.sendFilesSection.classList.toggle('hidden', currentDirection === 'receive');
        document.querySelector('.mode-section')?.classList.remove('hidden');
        elements.wifiPanel.classList.toggle('hidden', currentMode !== 'wifi');
        elements.adbPanel.classList.toggle('hidden', currentMode !== 'adb');
        if (currentMode === 'adb') {
            document.getElementById('adbSettingsPanel')?.classList.remove('hidden');
        }
    }
}

/**
 * Fully reset the progress UI back to initial state
 * Called after cancel or before starting a fresh transfer
 */
function resetProgressUI() {
    elements.progressBar.style.width = '0%';
    elements.filesProgress.textContent = 'Files: 0/0';
    elements.speedDisplay.textContent = '';
    elements.currentFile.textContent = '';
}

function setStatus(text, type = '') {
    // Prepend mode badge when transferring
    const modeLabel = currentMode === 'adb' ? 'USB' : 'WiFi';
    const prefix = isTransferring ? `[${modeLabel}] ` : '';
    elements.statusText.textContent = prefix + text;
    elements.statusIcon.className = 'status-icon';
    if (type === 'success') { elements.statusIcon.style.color = '#30d158'; }
    else if (type === 'error') { elements.statusIcon.style.color = '#ff453a'; }
    else if (type === 'active') { elements.statusIcon.classList.add('active'); elements.statusIcon.style.color = '#30d158'; }
    else { elements.statusIcon.style.color = 'rgba(235,235,245,0.3)'; }
}

function updateProgress(data) {
    if (!isTransferring) return; // Ignore stale events after cancel
    // Use whichever percent is further along — byte-based is more accurate, file-count is
    // more responsive early on. Max-of-both keeps the bar always moving forward.
    const hasFiles = data.completed !== undefined && data.total !== undefined && data.total > 0;
    const hasBytes = data.bytesTransferred !== undefined && data.totalBytes !== undefined && data.totalBytes > 0;
    let percent = null;
    if (hasFiles || hasBytes) {
        const filePercent = hasFiles ? Math.round((data.completed / data.total) * 100) : 0;
        const bytePercent = hasBytes ? Math.round((data.bytesTransferred / data.totalBytes) * 100) : 0;
        const rawPercent = Math.max(filePercent, bytePercent);
        // Cap at 99 until either metric definitively hits its end, then snap to 100.
        const reachedEnd = (hasFiles && data.completed >= data.total) ||
                          (hasBytes && data.bytesTransferred >= data.totalBytes);
        percent = reachedEnd ? 100 : Math.min(rawPercent, 99);
    }
    if (percent !== null) {
        elements.progressBar.style.width = `${percent}%`;
        const filesLabel = hasFiles ? `${data.completed} / ${data.total} files ` : '';
        elements.filesProgress.textContent = `${filesLabel}(${percent}%)`;
    }

    // Hide speed - just show progress
    elements.speedDisplay.textContent = '';

    if (data.currentFile) {
        let f = data.currentFile;
        if (f.length > 40) f = '...' + f.slice(-37);
        elements.currentFile.textContent = f;
    }
}

function log(message, type = '') {
    const timestamp = new Date().toLocaleTimeString('en-US', { hour12: false });
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.innerHTML = `<span class="timestamp">[${timestamp}]</span> ${message}`;
    elements.logContainer.appendChild(entry);
    elements.logContainer.scrollTop = elements.logContainer.scrollHeight;
    while (elements.logContainer.children.length > 100) elements.logContainer.removeChild(elements.logContainer.firstChild);
}

// ==================== ADB Settings Functions ====================

async function loadAdbStatus() {
    try {
        const status = await window.api.getAdbStatus();
        updateAdbStatusUI(status);
    } catch (e) {
        console.error('Failed to load ADB status:', e);
        updateAdbStatusUI({ available: false, error: e.message });
    }
}

function updateAdbStatusUI(status) {
    // Update inline status in collapsed header
    const inlineEl = document.getElementById('adbStatusInline');
    if (inlineEl) {
        inlineEl.textContent = status.available ? 'Available' : 'Not Found';
        inlineEl.className = 'adb-status-inline' + (status.available ? '' : ' error');
    }

    // Update status text
    if (status.available) {
        elements.adbStatusValue.textContent = 'Available';
        elements.adbStatusValue.className = 'adb-status-value success';
    } else {
        elements.adbStatusValue.textContent = status.error || 'Not Found';
        elements.adbStatusValue.className = 'adb-status-value error';
    }

    // Update source badge
    const sourceLabels = {
        'user-configured': 'User Set',
        'android-home': 'ANDROID_HOME',
        'system-path': 'System PATH',
        'running-process': 'Running',
        'bundled': 'Bundled',
        'none': 'Not Found'
    };
    const sourceClasses = {
        'user-configured': 'user',
        'android-home': 'system',
        'system-path': 'system',
        'running-process': 'system',
        'bundled': 'bundled',
        'none': 'none'
    };

    const source = status.source || 'none';
    elements.adbSourceBadge.textContent = sourceLabels[source] || source;
    elements.adbSourceBadge.className = `adb-source-badge ${sourceClasses[source] || ''}`;

    // Update version
    elements.adbVersionValue.textContent = status.version || '--';

    // Update path
    if (status.path) {
        // Show shortened path
        const shortPath = status.path.length > 40
            ? '...' + status.path.slice(-37)
            : status.path;
        elements.adbPathValue.textContent = shortPath;
        elements.adbPathValue.title = status.path; // Full path on hover
    } else {
        elements.adbPathValue.textContent = '--';
        elements.adbPathValue.title = '';
    }

    // Log status
    if (status.available) {
        log(`USB tool ready (v${status.version})`, 'success');
    } else {
        log(`USB tool: ${status.error || 'Not found'}`, 'error');
    }
}

async function browseAdbPath() {
    elements.browseAdbBtn.disabled = true;
    elements.browseAdbBtn.textContent = 'Selecting...';

    try {
        const result = await window.api.browseAdb();
        if (result.success) {
            log(`USB tool path set: ${result.path}`, 'success');
            updateAdbStatusUI({
                available: true,
                path: result.path,
                source: result.source,
                version: result.version
            });
        } else {
            if (result.error !== 'No file selected') {
                log(`Failed to set USB tool path: ${result.error}`, 'error');
            }
        }
    } catch (e) {
        log(`Error: ${e.message}`, 'error');
    }

    elements.browseAdbBtn.disabled = false;
    elements.browseAdbBtn.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
        <polyline points="17 8 12 3 7 8"/>
        <line x1="12" y1="3" x2="12" y2="15"/>
    </svg> Set USB Tool Path`;
}

async function resetAdbPath() {
    elements.resetAdbBtn.disabled = true;
    elements.resetAdbBtn.textContent = 'Detecting...';

    try {
        const status = await window.api.resetAdbPath();
        updateAdbStatusUI(status);
        log('USB tool path reset to auto-detect', 'success');
    } catch (e) {
        log(`Error: ${e.message}`, 'error');
    }

    elements.resetAdbBtn.disabled = false;
    elements.resetAdbBtn.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21.5 2v6h-6M2.5 22v-6h6M2 11.5a10 10 0 0 1 18.8-4.3M22 12.5a10 10 0 0 1-18.8 4.3"/>
    </svg> Auto-detect`;
}

// ==================== Direction Toggle ====================

function setDirection(direction) {
    currentDirection = direction;

    // Update toggle buttons
    elements.receiveBtn.classList.toggle('active', direction === 'receive');
    elements.sendBtn.classList.toggle('active', direction === 'send');

    // Subtitle is the brand tagline — shown in both Receive and Send modes.
    // Using innerHTML so the bold/accent spans survive the toggle.
    elements.subtitleText.innerHTML =
        '<span class="tag-bold">Transfer.</span> ' +
        '<span class="tag-accent">Protect.</span> ' +
        '<span class="tag-bold">Repeat.</span>';

    // Show/hide relevant sections
    elements.saveLocationSection.classList.toggle('hidden', direction === 'send');
    elements.sendFilesSection.classList.toggle('hidden', direction === 'receive');

    // Update start button text
    elements.startBtn.innerHTML = direction === 'receive'
        ? `<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg> Start Transfer`
        : `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg> Send to Phone`;

    log(`Switched to ${direction === 'receive' ? 'Receive from Phone' : 'Send to Phone'} mode`);
}

// ==================== Send Mode File Selection ====================

async function addFilesToSend() {
    const result = await window.api.selectFilesToSend();
    if (!result || !result.success || !result.files || result.files.length === 0) {
        // User cancelled or no files selected - don't add anything
        return;
    }
    // Add files, avoiding duplicates
    let added = 0;
    for (const file of result.files) {
        if (file.path && !sendFilesList.some(f => f.path === file.path)) {
            sendFilesList.push(file);
            added++;
        }
    }
    if (added > 0) {
        updateSendFileListUI();
        log(`Added ${added} file(s) to send list`, 'success');
    }
}

async function addFolderToSend() {
    const result = await window.api.selectFolderToSend();
    if (!result || !result.success || !result.folder || !result.folder.path) {
        // User cancelled or invalid result - don't add anything
        return;
    }
    // Check if folder already added
    if (!sendFilesList.some(f => f.path === result.folder.path)) {
        sendFilesList.push(result.folder);
        updateSendFileListUI();
        log(`Added folder: ${result.folder.name} (${formatFileSize(result.folder.size)}, ${result.folder.fileCount} files)`, 'success');
    } else {
        log('Folder already in list', 'error');
    }
}

function clearSendFiles() {
    if (sendFilesList.length === 0) return;
    if (confirm('Clear all selected files?')) {
        sendFilesList = [];
        updateSendFileListUI();
        log('Cleared send list');
    }
}

function removeSendFile(index) {
    const removed = sendFilesList.splice(index, 1)[0];
    updateSendFileListUI();
    log(`Removed: ${removed.name}`);
}

function updateSendFileListUI() {
    const list = elements.sendFileList;

    if (sendFilesList.length === 0) {
        list.innerHTML = '<div class="empty-list">Drag & drop files here, or use the buttons above to add files.</div>';
        elements.sendFileCount.textContent = '0 files';
        elements.sendTotalSize.textContent = '0 B';
        return;
    }

    let totalSize = 0;
    let totalFiles = 0;

    list.innerHTML = sendFilesList.map((item, index) => {
        totalSize += item.size;
        totalFiles += item.isFolder ? (item.fileCount || 1) : 1;

        const iconSvg = item.isFolder
            ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>'
            : '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';

        const sizeText = item.isFolder
            ? `${formatFileSize(item.size)} (${item.fileCount} files)`
            : formatFileSize(item.size);

        return `
            <div class="send-file-item">
                <div class="file-icon ${item.isFolder ? 'folder' : ''}">${iconSvg}</div>
                <div class="file-info">
                    <div class="file-name">${item.name}</div>
                    <div class="file-path">${item.path}</div>
                </div>
                <div class="file-size">${sizeText}</div>
                <button class="remove-btn" data-remove-index="${index}" title="Remove">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                    </svg>
                </button>
            </div>
        `;
    }).join('');

    // Bind remove buttons via event listeners (inline onclick blocked by CSP)
    list.querySelectorAll('.remove-btn[data-remove-index]').forEach(btn => {
        btn.addEventListener('click', () => {
            const idx = parseInt(btn.getAttribute('data-remove-index'), 10);
            removeSendFile(idx);
        });
    });

    elements.sendFileCount.textContent = `${totalFiles} file${totalFiles !== 1 ? 's' : ''}`;
    elements.sendTotalSize.textContent = formatFileSize(totalSize);
}

// ==================== DRAG & DROP ====================

function setupDragAndDrop() {
    const dropZone = elements.sendFileList;

    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (currentDirection === 'send') dropZone.classList.add('drag-over');
    });

    dropZone.addEventListener('dragenter', (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (currentDirection === 'send') dropZone.classList.add('drag-over');
    });

    dropZone.addEventListener('dragleave', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.remove('drag-over');
    });

    dropZone.addEventListener('drop', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.remove('drag-over');

        if (currentDirection !== 'send') return;

        const paths = [];
        for (const file of e.dataTransfer.files) {
            paths.push(file.path);
        }

        if (paths.length === 0) return;

        log(`Processing ${paths.length} dropped item(s)...`);

        try {
            const result = await window.api.resolveDroppedPaths(paths);
            if (result.success && result.items.length > 0) {
                let added = 0;
                for (const item of result.items) {
                    if (item.path && !sendFilesList.some(f => f.path === item.path)) {
                        sendFilesList.push(item);
                        added++;
                    }
                }
                if (added > 0) {
                    updateSendFileListUI();
                    log(`Added ${added} item(s) via drag & drop`, 'success');
                } else {
                    log('Items already in list');
                }
            }
        } catch (err) {
            log(`Drop error: ${err.message}`, 'error');
        }
    });
}

// ==================== INLINE GUIDE HINTS ====================

let guideHintTimer = null;

/**
 * Show an inline hint banner in the guide slot.
 * Always shows (every time device connects), not just first time.
 * Auto-advances: connected → set destination → start transfer (with glow).
 */
function triggerGuideOnConnect(mode) {
    if (isTransferring) return;

    // Clear any previous hint
    clearGuideHint();
    elements.startBtn.classList.remove('guide-highlight-btn');

    const modeName = mode === 'adb' ? 'USB' : 'WiFi';

    if (currentDirection === 'receive') {
        // Step 1: Phone connected → nudge to check save location
        showGuideHint(
            'success',
            `<strong>Phone Connected</strong> via ${modeName} &mdash; check your <strong>Save Location</strong> below, then hit Start Transfer.`
        );
        // Glow the save location section
        document.getElementById('saveLocationSection').classList.add('guide-glow');
        // After 4s, advance to highlight start button
        guideHintTimer = setTimeout(() => {
            document.getElementById('saveLocationSection').classList.remove('guide-glow');
            showGuideHint(
                '',
                `Ready! Hit <strong>Start Transfer</strong> to begin receiving files.`
            );
            elements.startBtn.classList.add('guide-highlight-btn');
            // Auto-clear after 6s
            guideHintTimer = setTimeout(() => {
                clearGuideHint();
                elements.startBtn.classList.remove('guide-highlight-btn');
            }, 6000);
        }, 4000);
    } else {
        // Send mode: just confirm connected, highlight start
        showGuideHint(
            'success',
            `<strong>Phone Connected</strong> via ${modeName} &mdash; select files above, then hit <strong>Send to Phone</strong>.`
        );
        elements.startBtn.classList.add('guide-highlight-btn');
        guideHintTimer = setTimeout(() => {
            clearGuideHint();
            elements.startBtn.classList.remove('guide-highlight-btn');
        }, 6000);
    }
}

function showGuideHint(type, html) {
    const slot = document.getElementById('guideHintSlot');
    const cls = type ? `guide-hint ${type}` : 'guide-hint';
    const iconSvg = type === 'success'
        ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>'
        : '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>';

    slot.innerHTML = `
        <div class="${cls}">
            <div class="guide-hint-icon">${iconSvg}</div>
            <div class="guide-hint-text">${html}</div>
            <button class="guide-hint-dismiss" id="guideHintDismissBtn">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
            </button>
        </div>
    `;
    document.getElementById('guideHintDismissBtn')?.addEventListener('click', clearGuideHint);
}

function clearGuideHint() {
    const slot = document.getElementById('guideHintSlot');
    if (slot) slot.innerHTML = '';
    if (guideHintTimer) { clearTimeout(guideHintTimer); guideHintTimer = null; }
    // Clean up any leftover glows
    document.getElementById('saveLocationSection')?.classList.remove('guide-glow');
    elements.startBtn.classList.remove('guide-highlight-btn');
}

function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// ==================== About Modal ====================
const DEVELOPER_EMAIL = 'adityachaudhary703@gmail.com';

function openAboutModal() {
    if (!elements.aboutOverlay) return;
    // Reset hint text on each open
    if (elements.aboutHint) {
        elements.aboutHint.textContent = 'Click to copy';
        elements.aboutHint.classList.remove('copied');
    }
    elements.aboutOverlay.hidden = false;
}

function closeAboutModal() {
    if (!elements.aboutOverlay) return;
    elements.aboutOverlay.hidden = true;
}

async function copyDeveloperEmail() {
    try {
        await navigator.clipboard.writeText(DEVELOPER_EMAIL);
        if (elements.aboutHint) {
            elements.aboutHint.textContent = 'Copied to clipboard ✓';
            elements.aboutHint.classList.add('copied');
        }
    } catch (e) {
        if (elements.aboutHint) {
            elements.aboutHint.textContent = 'Copy failed — select & copy manually';
        }
    }
}

document.addEventListener('DOMContentLoaded', init);
