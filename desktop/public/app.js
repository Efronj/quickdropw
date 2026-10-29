// State
let peers = [];
let selectedPeerId = null;
let stagedFiles = []; // { file: File object (web upload) } or { path, name, size (native) }
let selfInfo = {};
let activeTransfers = [];
let transferHistory = [];

// DOM Elements
const peersLayer = document.getElementById('peers-layer');
const peerCount = document.getElementById('peer-count');
const radarHint = document.getElementById('radar-hint');
const selfNameEl = document.getElementById('self-name');
const selectedTargetCard = document.getElementById('selected-target-card');
const targetNameEl = document.getElementById('target-name');
const targetIconEl = document.getElementById('target-icon');
const btnClearTarget = document.getElementById('btn-clear-target');
const dropzone = document.getElementById('dropzone');
const fileInput = document.getElementById('file-input');
const stagedCard = document.getElementById('staged-card');
const stagedSummary = document.getElementById('staged-summary');
const stagedList = document.getElementById('staged-list');
const btnClearStaged = document.getElementById('btn-clear-staged');
const btnSendStaged = document.getElementById('btn-send-staged');
const transfersSection = document.getElementById('transfers-section');
const transfersList = document.getElementById('transfers-list');
const historyList = document.getElementById('history-list');
const btnClearHistory = document.getElementById('btn-clear-history');
const btnOpenFolder = document.getElementById('btn-open-folder');
const btnSettings = document.getElementById('btn-settings');
const toastContainer = document.getElementById('toast-container');

// Modals
const incomingModal = document.getElementById('incoming-modal');
const incomingTitle = document.getElementById('incoming-title');
const incomingDesc = document.getElementById('incoming-desc');
const incomingPreview = document.getElementById('incoming-files-preview');
const btnAcceptIncoming = document.getElementById('btn-accept-incoming');
const btnDeclineIncoming = document.getElementById('btn-decline-incoming');
const btnTrustIncoming = document.getElementById('btn-trust-incoming');

const noteModal = document.getElementById('note-modal');
const noteText = document.getElementById('note-text');
const btnSendNote = document.getElementById('btn-send-note');
const btnCancelNote = document.getElementById('btn-cancel-note');

const portalModal = document.getElementById('portal-modal');
const portalUrl = document.getElementById('portal-url');
const btnClosePortal = document.getElementById('btn-close-portal');
const qrCodeContainer = document.getElementById('qrcode-container');

const settingsModal = document.getElementById('settings-modal');
const settingsName = document.getElementById('settings-name');
const settingsAutoAccept = document.getElementById('settings-auto-accept');
const settingsDownloadPath = document.getElementById('settings-download-path');
const btnSaveSettings = document.getElementById('btn-save-settings');
const btnCloseSettings = document.getElementById('btn-close-settings');

let currentIncomingTxId = null;

// Helpers
function formatBytes(bytes) {
  if (bytes <= 0 || !bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let val = bytes;
  while (val >= 1024 && i < units.length - 1) {
    val /= 1024;
    i++;
  }
  return (i === 0 ? val : val.toFixed(1)) + ' ' + units[i];
}

function formatSpeed(bytesPerSec) {
  if (!bytesPerSec || bytesPerSec <= 0) return 'starting…';
  return formatBytes(bytesPerSec) + '/s';
}

function showToast(msg) {
  const t = document.createElement('div');
  t.className = 'toast';
  t.textContent = msg;
  toastContainer.appendChild(t);
  setTimeout(() => {
    t.style.opacity = '0';
    t.style.transform = 'translateY(10px)';
    setTimeout(() => t.remove(), 250);
  }, 3500);
}

// ─────────────────────────────────────────────────────────────
// Data Fetching & Sync
// ─────────────────────────────────────────────────────────────

async function fetchInfo() {
  try {
    const res = await fetch('/api/info');
    selfInfo = await res.json();
    selfNameEl.textContent = selfInfo.name || 'This PC';
    settingsName.value = selfInfo.name || '';
    settingsAutoAccept.checked = !!selfInfo.autoAccept;
    settingsDownloadPath.textContent = selfInfo.downloadDir || 'Downloads\\QuickDrop';
    
    // Set web portal URL (prefer first local network IPv4)
    const ip = (selfInfo.ips && selfInfo.ips[0]) || window.location.hostname;
    portalUrl.textContent = `http://${ip}:${selfInfo.port}`;
  } catch (e) {
    console.error('Failed to fetch info:', e);
  }
}

async function fetchPeers() {
  try {
    const res = await fetch('/api/peers');
    peers = await res.json();
    renderPeers();
  } catch (e) {
    console.error('Failed to fetch peers:', e);
  }
}

async function fetchTransfers() {
  try {
    const res = await fetch('/api/transfers');
    activeTransfers = await res.json();
    renderTransfers();
  } catch (e) {
    console.error('Failed to fetch transfers:', e);
  }
}

async function fetchHistory() {
  try {
    const res = await fetch('/api/history');
    transferHistory = await res.json();
    renderHistory();
  } catch (e) {
    console.error('Failed to fetch history:', e);
  }
}

async function fetchPendingRequests() {
  try {
    const res = await fetch('/api/pending-requests');
    const pending = await res.json();
    if (pending && pending.length > 0) {
      const p = pending[0];
      if (currentIncomingTxId !== p.transferId) {
        currentIncomingTxId = p.transferId;
        showIncomingDialog(p);
      }
    } else {
      if (currentIncomingTxId) {
        incomingModal.style.display = 'none';
        currentIncomingTxId = null;
      }
    }
  } catch (e) {
    console.error('Failed to fetch pending requests:', e);
  }
}

// ─────────────────────────────────────────────────────────────
// Radar & Discovered Devices Rendering
// ─────────────────────────────────────────────────────────────

function renderPeers() {
  peersLayer.innerHTML = '';
  peerCount.textContent = `${peers.length} found`;

  if (peers.length === 0) {
    radarHint.textContent = 'Looking for Android phones and PCs on the same Wi-Fi…';
    if (selectedPeerId) deselectTarget();
    return;
  }

  radarHint.textContent = 'Tap any device bubble to send files or notes';

  const n = peers.length;
  const radius = 110; // distance from radar center in px
  const centerW = 230; // container half-width approx (radar-container is ~460px / 2 = 230)
  const centerH = 170; // container half-height approx (340px / 2 = 170)

  peers.forEach((peer, i) => {
    const angle = (2 * Math.PI * i / n) - (Math.PI / 2);
    const x = centerW + radius * Math.cos(angle);
    const y = centerH + radius * Math.sin(angle);

    const bubble = document.createElement('div');
    bubble.className = `peer-bubble ${peer.id === selectedPeerId ? 'selected' : ''}`;
    bubble.style.left = `${x}px`;
    bubble.style.top = `${y}px`;

    const icon = peer.deviceType === 'desktop' ? '💻' : '📱';
    const osBadge = peer.os === 'Android' ? '🤖' : (peer.os === 'Windows' ? '🪟' : '🍏');

    bubble.innerHTML = `
      <div class="peer-avatar">
        <span>${icon}</span>
        <span class="peer-badge-os">${osBadge}</span>
      </div>
      <div class="peer-label">${peer.name}</div>
      <div class="peer-status">Ready</div>
    `;

    bubble.addEventListener('click', () => {
      selectTarget(peer);
    });

    peersLayer.appendChild(bubble);
  });
}

function selectTarget(peer) {
  selectedPeerId = peer.id;
  selectedTargetCard.style.display = 'flex';
  targetNameEl.textContent = peer.name;
  targetIconEl.textContent = peer.deviceType === 'desktop' ? '💻' : '📱';
  renderPeers();

  // If files are already staged, prompt to send immediately
  if (stagedFiles.length > 0) {
    btnSendStaged.textContent = `🚀 Send ${stagedFiles.length} File(s) to ${peer.name}`;
  }
}

function deselectTarget() {
  selectedPeerId = null;
  selectedTargetCard.style.display = 'none';
  renderPeers();
  if (stagedFiles.length > 0) {
    btnSendStaged.textContent = '🚀 Send to Selected Device';
  }
}

btnClearTarget.addEventListener('click', deselectTarget);

// ─────────────────────────────────────────────────────────────
// Staging & Sending Files
// ─────────────────────────────────────────────────────────────

function addStagedFiles(fileArray) {
  // Can be File objects (web drag-drop) or { path, name, size } objects (native pick)
  stagedFiles = stagedFiles.concat(fileArray);
  renderStaged();
}

function renderStaged() {
  if (stagedFiles.length === 0) {
    stagedCard.style.display = 'none';
    return;
  }

  stagedCard.style.display = 'block';
  const totalSize = stagedFiles.reduce((acc, f) => acc + (f.size || 0), 0);
  stagedSummary.textContent = `${stagedFiles.length} file(s) selected (${formatBytes(totalSize)})`;

  stagedList.innerHTML = '';
  stagedFiles.forEach((f, idx) => {
    const item = document.createElement('div');
    item.className = 'staged-item';
    item.innerHTML = `
      <span class="staged-item-name">${f.name}</span>
      <span class="staged-item-size">${formatBytes(f.size)}</span>
    `;
    stagedList.appendChild(item);
  });

  const target = peers.find(p => p.id === selectedPeerId);
  if (target) {
    btnSendStaged.textContent = `🚀 Send ${stagedFiles.length} File(s) to ${target.name}`;
  } else {
    btnSendStaged.textContent = '🚀 Select a Device Above to Send';
  }
}

btnClearStaged.addEventListener('click', () => {
  stagedFiles = [];
  renderStaged();
});

btnSendStaged.addEventListener('click', async () => {
  if (stagedFiles.length === 0) return;

  if (!selectedPeerId) {
    showToast('Please tap a nearby device on the radar first!');
    return;
  }

  const peer = peers.find(p => p.id === selectedPeerId);
  if (!peer) {
    showToast('Selected device is no longer reachable');
    deselectTarget();
    return;
  }

  // Check if we have native file paths or web File objects
  const hasNativePaths = stagedFiles.some(f => f.path);

  if (hasNativePaths) {
    // Send via local file paths endpoint
    const filePaths = stagedFiles.map(f => f.path).filter(Boolean);
    try {
      const res = await fetch('/api/send-paths', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ peerId: peer.id, filePaths })
      });
      const data = await res.json();
      if (data.queued) {
        showToast(`Sending ${data.queued} file(s) to ${peer.name}…`);
        stagedFiles = [];
        renderStaged();
      } else {
        showToast(`Error: ${data.error || 'Failed to send'}`);
      }
    } catch (e) {
      showToast(`Error: ${e.message}`);
    }
  } else {
    // Web File objects: upload directly to target peer
    showToast(`Uploading ${stagedFiles.length} file(s) to ${peer.name}…`);
    uploadWebFilesToPeer(peer, stagedFiles);
    stagedFiles = [];
    renderStaged();
  }
});

async function uploadWebFilesToPeer(peer, files) {
  for (const file of files) {
    try {
      await fetch(`http://${peer.ip}:${peer.port}/api/transfer/upload`, {
        method: 'POST',
        headers: {
          'X-Transfer-Id': 'web_tx_' + Date.now(),
          'X-File-Name': encodeURIComponent(file.name),
          'X-File-Size': file.size.toString(),
          'X-Sender-Name': encodeURIComponent(selfInfo.name || 'QuickDrop Desktop'),
          'Content-Type': file.type || 'application/octet-stream'
        },
        body: file
      });
      showToast(`Sent ${file.name} ✓`);
    } catch (e) {
      console.error('Error uploading file:', e);
      showToast(`Failed to send ${file.name}`);
    }
  }
}

// ─────────────────────────────────────────────────────────────
// Drag & Drop & Browse File Handlers
// ─────────────────────────────────────────────────────────────

dropzone.addEventListener('click', () => fileInput.click());

fileInput.addEventListener('change', (e) => {
  if (e.target.files && e.target.files.length > 0) {
    addStagedFiles(Array.from(e.target.files));
    fileInput.value = '';
  }
});

['dragenter', 'dragover'].forEach(eventName => {
  window.addEventListener(eventName, (e) => {
    e.preventDefault();
    dropzone.classList.add('dragover');
  });
});

['dragleave', 'drop'].forEach(eventName => {
  window.addEventListener(eventName, (e) => {
    e.preventDefault();
    dropzone.classList.remove('dragover');
  });
});

window.addEventListener('drop', (e) => {
  e.preventDefault();
  if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
    addStagedFiles(Array.from(e.dataTransfer.files));
  }
});

// Tile: Native browse files (PowerShell OpenFileDialog)
document.getElementById('tile-pick-files').addEventListener('click', async () => {
  try {
    const res = await fetch('/api/pick-files', { method: 'POST' });
    const data = await res.json();
    if (data.files && data.files.length > 0) {
      addStagedFiles(data.files);
    }
  } catch (e) {
    // Fallback: trigger standard browser file input
    fileInput.click();
  }
});

// Tile: Send Clipboard
document.getElementById('tile-clipboard').addEventListener('click', async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (!text || !text.trim()) {
      showToast('Clipboard is empty!');
      return;
    }
    if (!selectedPeerId) {
      showToast('Tap a device on the radar first to send clipboard!');
      return;
    }
    const peer = peers.find(p => p.id === selectedPeerId);
    if (!peer) return;

    await sendTextToPeer(peer, text);
  } catch (e) {
    showToast('Could not read clipboard. Try Write Note instead.');
  }
});

// Tile: Write Note
document.getElementById('tile-note').addEventListener('click', () => {
  if (!selectedPeerId) {
    showToast('Tap a device on the radar first!');
    return;
  }
  noteModal.style.display = 'flex';
  noteText.focus();
});

btnCancelNote.addEventListener('click', () => {
  noteModal.style.display = 'none';
  noteText.value = '';
});

btnSendNote.addEventListener('click', async () => {
  const text = noteText.value.trim();
  if (!text) return;
  const peer = peers.find(p => p.id === selectedPeerId);
  if (!peer) return;

  await sendTextToPeer(peer, text);
  noteModal.style.display = 'none';
  noteText.value = '';
});

async function sendTextToPeer(peer, text) {
  try {
    const res = await fetch('/api/send-text', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ peerId: peer.id, text })
    });
    const data = await res.json();
    if (data.success) {
      showToast(`Sent note to ${peer.name} ✓`);
    } else {
      showToast(`Failed to send note: ${data.error}`);
    }
  } catch (e) {
    showToast(`Error: ${e.message}`);
  }
}

// Tile: Web Portal / QR Code
document.getElementById('tile-browser-portal').addEventListener('click', () => {
  portalModal.style.display = 'flex';
  qrCodeContainer.innerHTML = '';
  const url = portalUrl.textContent.trim();
  if (window.QRCode) {
    new QRCode(qrCodeContainer, {
      text: url,
      width: 180,
      height: 180,
      colorDark: '#0F172A',
      colorLight: '#FFFFFF',
      correctLevel: QRCode.CorrectLevel.M
    });
  }
});

btnClosePortal.addEventListener('click', () => {
  portalModal.style.display = 'none';
});

// ─────────────────────────────────────────────────────────────
// Transfers & History UI
// ─────────────────────────────────────────────────────────────

function renderTransfers() {
  if (activeTransfers.length === 0) {
    transfersSection.style.display = 'none';
    return;
  }

  transfersSection.style.display = 'block';
  transfersList.innerHTML = '';

  activeTransfers.forEach(t => {
    const card = document.createElement('div');
    card.className = 'transfer-card';

    const pct = t.total > 0 ? Math.min(100, Math.round((t.done / t.total) * 100)) : 0;
    const directionIcon = t.outgoing ? '⬆️' : '⬇️';
    const statusText = t.state === 1 ? 'Completed ✓' : (t.state === 2 ? 'Failed ✕' : `${formatBytes(t.done)} of ${formatBytes(t.total)} · ${formatSpeed(t.speed)}`);

    card.innerHTML = `
      <div class="transfer-row">
        <div class="transfer-icon ${t.outgoing ? 'bg-blue' : 'bg-mint'}">${directionIcon}</div>
        <div class="transfer-info">
          <div class="transfer-filename">${t.name}</div>
          <div class="transfer-meta">${statusText}</div>
        </div>
        <div style="font-weight: 700; font-size: 13px; color: var(--blue);">${pct}%</div>
      </div>
      <div class="progress-bar-bg">
        <div class="progress-bar-fill" style="width: ${pct}%;"></div>
      </div>
    `;
    transfersList.appendChild(card);
  });
}

function renderHistory() {
  if (transferHistory.length === 0) {
    historyList.innerHTML = '<div class="empty-hint">No transfers yet. Shared files will appear here!</div>';
    return;
  }

  historyList.innerHTML = '';
  transferHistory.slice(0, 15).forEach(item => {
    const row = document.createElement('div');
    row.className = 'history-item';

    const isOutgoing = item.outgoing;
    const icon = item.text ? '💬' : (item.name.match(/\.(jpg|jpeg|png|gif|webp)$/i) ? '🖼️' : (item.name.match(/\.(mp4|mkv|mov|avi)$/i) ? '🎬' : '📄'));

    row.innerHTML = `
      <div class="history-left">
        <span style="font-size: 18px;">${icon}</span>
        <div>
          <div class="history-name">${item.name}</div>
          <div class="history-sub">
            ${isOutgoing ? 'To ' + item.peer : 'From ' + item.peer} · ${formatBytes(item.size)} · ${new Date(item.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
          </div>
        </div>
      </div>
      <div class="history-actions">
        ${item.path ? `<button class="btn btn-small btn-secondary btn-open-item">Open</button>` : ''}
        ${item.text ? `<button class="btn btn-small btn-secondary btn-copy-text">Copy</button>` : ''}
      </div>
    `;

    if (item.path) {
      row.querySelector('.btn-open-item').addEventListener('click', () => {
        fetch('/api/open-file', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ filePath: item.path })
        });
      });
    }

    if (item.text) {
      row.querySelector('.btn-copy-text').addEventListener('click', () => {
        navigator.clipboard.writeText(item.text);
        showToast('Copied note to clipboard');
      });
    }

    historyList.appendChild(row);
  });
}

btnClearHistory.addEventListener('click', async () => {
  await fetch('/api/history/clear', { method: 'POST' });
  transferHistory = [];
  renderHistory();
});

// Open Downloads Folder
btnOpenFolder.addEventListener('click', () => {
  fetch('/api/open-downloads', { method: 'POST' });
});

// ─────────────────────────────────────────────────────────────
// Incoming Transfer Dialog
// ─────────────────────────────────────────────────────────────

function showIncomingDialog(reqInfo) {
  incomingTitle.textContent = `Incoming from ${reqInfo.senderName}`;
  const totalSize = (reqInfo.files || []).reduce((acc, f) => acc + (f.size || 0), 0);
  incomingDesc.textContent = `${reqInfo.senderName} wants to send ${reqInfo.files?.length || 1} file(s) (${formatBytes(totalSize)})`;

  incomingPreview.innerHTML = (reqInfo.files || []).map(f => `
    <div style="font-size: 12px; color: var(--text-2); margin-top: 4px;">
      📄 ${f.name} (${formatBytes(f.size)})
    </div>
  `).join('');

  incomingModal.style.display = 'flex';
}

btnAcceptIncoming.addEventListener('click', () => respondIncoming(true, false));
btnDeclineIncoming.addEventListener('click', () => respondIncoming(false, false));
btnTrustIncoming.addEventListener('click', () => respondIncoming(true, true));

async function respondIncoming(accepted, trust) {
  if (!currentIncomingTxId) return;
  try {
    await fetch('/api/pending-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ transferId: currentIncomingTxId, accepted, trust })
    });
    incomingModal.style.display = 'none';
    currentIncomingTxId = null;
    if (accepted) showToast('Receiving transfer…');
  } catch (e) {
    console.error('Error responding to incoming transfer:', e);
  }
}

// ─────────────────────────────────────────────────────────────
// Settings
// ─────────────────────────────────────────────────────────────

btnSettings.addEventListener('click', () => settingsModal.style.display = 'flex');
document.getElementById('self-device-pill').addEventListener('click', () => settingsModal.style.display = 'flex');
btnCloseSettings.addEventListener('click', () => settingsModal.style.display = 'none');

btnSaveSettings.addEventListener('click', async () => {
  const name = settingsName.value.trim();
  const autoAccept = settingsAutoAccept.checked;

  try {
    const res = await fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, autoAccept })
    });
    const updated = await res.json();
    selfInfo.name = updated.name;
    selfInfo.autoAccept = updated.autoAccept;
    selfNameEl.textContent = updated.name;
    settingsModal.style.display = 'none';
    showToast('Settings saved ✓');
  } catch (e) {
    showToast('Could not save settings');
  }
});

// ─────────────────────────────────────────────────────────────
// Init & Loops
// ─────────────────────────────────────────────────────────────

fetchInfo();
fetchPeers();
fetchTransfers();
fetchHistory();

setInterval(fetchPeers, 1500);
setInterval(fetchTransfers, 600);
setInterval(fetchPendingRequests, 1000);
setInterval(fetchHistory, 3500);
