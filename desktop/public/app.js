// State
let localPeers = [];
let internetPeers = [];
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

// Anywhere / Internet Share Elements
const myShareCodeEl = document.getElementById('my-share-code');
const inputPeerCode = document.getElementById('input-peer-code');
const btnConnectCode = document.getElementById('btn-connect-code');

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
// WebRTC Internet / Anywhere Sharing (PeerJS + STUN)
// ─────────────────────────────────────────────────────────────

let peerInstance = null;
const myShareCode = Math.floor(100000 + Math.random() * 900000).toString();
const myPeerId = 'quickdrop-' + myShareCode;
const incomingWebRTCFiles = new Map();

function initWebRTC() {
  if (myShareCodeEl) {
    myShareCodeEl.textContent = `Code: ${myShareCode.slice(0, 3)}-${myShareCode.slice(3)}`;
  }

  if (!window.Peer) {
    console.warn('PeerJS library not loaded');
    return;
  }

  try {
    peerInstance = new Peer(myPeerId, {
      debug: 1,
      config: {
        iceServers: [
          { urls: 'stun:stun.l.google.com:19302' },
          { urls: 'stun:stun1.l.google.com:19302' },
          { urls: 'stun:stun2.l.google.com:19302' }
        ]
      }
    });

    peerInstance.on('open', (id) => {
      console.log('[QuickDrop] Internet P2P Ready with ID:', id);
    });

    peerInstance.on('connection', (conn) => {
      setupDataConnection(conn);
    });

    peerInstance.on('error', (err) => {
      console.warn('[QuickDrop] WebRTC warning:', err.type || err);
    });
  } catch (e) {
    console.warn('[QuickDrop] WebRTC setup error:', e);
  }
}

function setupDataConnection(conn) {
  conn.on('open', () => {
    // Send hello handshake
    conn.send({
      type: 'hello',
      name: selfInfo.name || 'Windows PC',
      deviceType: 'desktop',
      os: 'Windows'
    });

    const peerObj = {
      id: conn.peer,
      name: conn.metadata?.name || 'Internet Device',
      deviceType: conn.metadata?.deviceType || 'mobile',
      os: conn.metadata?.os || 'Anywhere',
      isInternet: true,
      conn: conn
    };

    internetPeers = internetPeers.filter(p => p.id !== conn.peer);
    internetPeers.push(peerObj);
    combineAndRenderPeers();

    showToast(`Connected to ${peerObj.name} across the internet! 🌐`);
  });

  conn.on('data', (data) => {
    handleWebRTCData(conn, data);
  });

  conn.on('close', () => {
    internetPeers = internetPeers.filter(p => p.id !== conn.peer);
    if (selectedPeerId === conn.peer) deselectTarget();
    combineAndRenderPeers();
  });

  conn.on('error', () => {
    internetPeers = internetPeers.filter(p => p.id !== conn.peer);
    combineAndRenderPeers();
  });
}

function handleWebRTCData(conn, data) {
  if (!data || !data.type) return;

  if (data.type === 'hello') {
    const peerObj = {
      id: conn.peer,
      name: data.name || 'Internet Device',
      deviceType: data.deviceType || 'mobile',
      os: data.os || 'Anywhere',
      isInternet: true,
      conn: conn
    };
    internetPeers = internetPeers.filter(p => p.id !== conn.peer);
    internetPeers.push(peerObj);
    combineAndRenderPeers();

    conn.send({
      type: 'welcome',
      name: selfInfo.name || 'Windows PC',
      deviceType: 'desktop'
    });
    showToast(`${peerObj.name} connected via Internet! 🌐`);
  }

  if (data.type === 'welcome') {
    const peer = internetPeers.find(p => p.id === conn.peer);
    if (peer && data.name) {
      peer.name = data.name;
      combineAndRenderPeers();
    }
  }

  if (data.type === 'file-start') {
    incomingWebRTCFiles.set(data.txId, {
      name: data.name,
      size: data.size,
      mime: data.mime,
      chunks: [],
      received: 0,
      startTime: Date.now()
    });

    const tObj = {
      id: data.txId,
      name: data.name,
      total: data.size,
      done: 0,
      outgoing: false,
      peer: conn.metadata?.name || 'Internet Device',
      state: 0,
      speed: 0
    };
    activeTransfers.push(tObj);
    renderTransfers();
  }

  if (data.type === 'file-chunk') {
    const f = incomingWebRTCFiles.get(data.txId);
    if (!f) return;
    f.chunks.push(data.chunk);
    f.received += (data.chunk.byteLength || data.chunk.length || 0);

    const t = activeTransfers.find(item => item.id === data.txId);
    if (t) {
      t.done = f.received;
      const elapsed = (Date.now() - f.startTime) / 1000;
      if (elapsed > 0.2) t.speed = f.received / elapsed;
      renderTransfers();
    }
  }

  if (data.type === 'file-done') {
    const f = incomingWebRTCFiles.get(data.txId);
    if (!f) return;
    incomingWebRTCFiles.delete(data.txId);

    const blob = new Blob(f.chunks, { type: f.mime || 'application/octet-stream' });

    // Save directly to Downloads\QuickDrop via local desktop API
    fetch('/api/upload-direct', {
      method: 'POST',
      headers: {
        'x-file-name': encodeURIComponent(f.name),
        'x-file-size': f.size.toString()
      },
      body: blob
    }).then(() => {
      showToast(`Saved ${f.name} to Downloads\\QuickDrop ✓`);
    }).catch(() => {
      // Browser fallback
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = f.name;
      a.click();
      showToast(`Downloaded ${f.name} ✓`);
    });

    const t = activeTransfers.find(item => item.id === data.txId);
    if (t) {
      t.done = f.size;
      t.state = 1;
      renderTransfers();
      setTimeout(() => {
        activeTransfers = activeTransfers.filter(item => item.id !== data.txId);
        renderTransfers();
      }, 4000);
    }
  }

  if (data.type === 'text') {
    showToast(`Note from ${conn.metadata?.name || 'Internet Device'}: ${data.text}`);
    transferHistory.unshift({
      name: 'Text Note',
      size: data.text.length,
      peer: conn.metadata?.name || 'Internet Device',
      outgoing: false,
      text: data.text,
      time: Date.now()
    });
    renderHistory();
  }
}

// Connect to another device's 6-digit code
function connectViaCode(code) {
  if (!peerInstance) {
    showToast('WebRTC is not ready yet');
    return;
  }
  const clean = code.replace(/[^0-9]/g, '');
  if (clean.length !== 6) {
    showToast('Please enter a 6-digit code (e.g. 123456)');
    return;
  }
  const targetId = 'quickdrop-' + clean;
  if (targetId === myPeerId) {
    showToast('Cannot connect to your own device code!');
    return;
  }

  showToast(`Connecting to ${clean.slice(0, 3)}-${clean.slice(3)}…`);
  const conn = peerInstance.connect(targetId, {
    reliable: true,
    metadata: {
      name: selfInfo.name || 'Windows PC',
      deviceType: 'desktop',
      os: 'Windows'
    }
  });

  setupDataConnection(conn);
}

if (btnConnectCode && inputPeerCode) {
  btnConnectCode.addEventListener('click', () => {
    connectViaCode(inputPeerCode.value.trim());
    inputPeerCode.value = '';
  });

  inputPeerCode.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
      connectViaCode(inputPeerCode.value.trim());
      inputPeerCode.value = '';
    }
  });
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
    localPeers = await res.json();
    combineAndRenderPeers();
  } catch (e) {
    console.error('Failed to fetch peers:', e);
  }
}

function combineAndRenderPeers() {
  // Merge local Wi-Fi peers + Internet WebRTC peers
  peers = [...localPeers, ...internetPeers];
  renderPeers();
}

async function fetchTransfers() {
  try {
    const res = await fetch('/api/transfers');
    const serverTransfers = await res.json();
    
    // Merge server transfers with WebRTC transfers
    const serverIds = new Set(serverTransfers.map(t => t.id));
    const rtcOnly = activeTransfers.filter(t => !serverIds.has(t.id));
    activeTransfers = [...serverTransfers, ...rtcOnly];
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
    radarHint.textContent = 'Looking for Android phones and PCs on local Wi-Fi or Internet…';
    if (selectedPeerId) deselectTarget();
    return;
  }

  radarHint.textContent = 'Tap any device bubble to send files or notes';

  const n = peers.length;
  const radius = 110;
  const centerW = 230;
  const centerH = 170;

  peers.forEach((peer, i) => {
    const angle = (2 * Math.PI * i / n) - (Math.PI / 2);
    const x = centerW + radius * Math.cos(angle);
    const y = centerH + radius * Math.sin(angle);

    const bubble = document.createElement('div');
    bubble.className = `peer-bubble ${peer.id === selectedPeerId ? 'selected' : ''}`;
    bubble.style.left = `${x}px`;
    bubble.style.top = `${y}px`;

    const icon = peer.deviceType === 'desktop' ? '💻' : '📱';
    const osBadge = peer.isInternet ? '🌐' : (peer.os === 'Android' ? '🤖' : (peer.os === 'Windows' ? '🪟' : '🍏'));

    bubble.innerHTML = `
      <div class="peer-avatar">
        <span>${icon}</span>
        <span class="peer-badge-os">${osBadge}</span>
      </div>
      <div class="peer-label">${peer.name}</div>
      <div class="peer-status">${peer.isInternet ? 'Internet' : 'Local Wi-Fi'}</div>
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
  stagedFiles.forEach((f) => {
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

  // 1. If connected via Internet WebRTC:
  if (peer.isInternet && peer.conn) {
    showToast(`Streaming ${stagedFiles.length} file(s) to ${peer.name} across the internet…`);
    sendFilesOverWebRTC(peer, stagedFiles);
    stagedFiles = [];
    renderStaged();
    return;
  }

  // 2. If connected via Local Wi-Fi:
  const hasNativePaths = stagedFiles.some(f => f.path);
  if (hasNativePaths) {
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
    showToast(`Uploading ${stagedFiles.length} file(s) to ${peer.name}…`);
    uploadWebFilesToPeer(peer, stagedFiles);
    stagedFiles = [];
    renderStaged();
  }
});

// Stream files over WebRTC DataChannel across the Internet
async function sendFilesOverWebRTC(peer, files) {
  const conn = peer.conn;
  if (!conn || !conn.open) {
    showToast('Internet connection to peer lost');
    return;
  }

  for (const f of files) {
    const txId = 'rtc_' + Date.now();
    const totalBytes = f.size || 0;
    let sentBytes = 0;

    conn.send({
      type: 'file-start',
      txId,
      name: f.name,
      size: totalBytes,
      mime: f.type || 'application/octet-stream'
    });

    const tObj = {
      id: txId,
      name: f.name,
      total: totalBytes,
      done: 0,
      outgoing: true,
      peer: peer.name,
      state: 0,
      speed: 0
    };
    activeTransfers.push(tObj);
    renderTransfers();

    // Stream chunks
    const chunkSize = 64 * 1024;
    let offset = 0;
    const startTime = Date.now();
    let lastTime = startTime;

    // Handle File object (web) vs local file path (native)
    let blob = f;
    if (f.path && !f.slice) {
      // In native app mode, we can fetch the local file slice
      try {
        const fileBlob = await fetch('/api/file-blob?path=' + encodeURIComponent(f.path)).then(r => r.blob());
        blob = fileBlob;
      } catch (e) {
        blob = new Blob([f.name]);
      }
    }

    while (offset < totalBytes) {
      const slice = blob.slice(offset, offset + chunkSize);
      const buffer = await slice.arrayBuffer();
      conn.send({
        type: 'file-chunk',
        txId,
        chunk: buffer
      });

      offset += buffer.byteLength;
      sentBytes = offset;

      const now = Date.now();
      if (now - lastTime >= 300) {
        const instSpeed = (sentBytes / ((now - startTime) / 1000));
        tObj.done = sentBytes;
        tObj.speed = instSpeed;
        renderTransfers();
        lastTime = now;
      }

      if (conn.dataChannel && conn.dataChannel.bufferedAmount > 2 * 1024 * 1024) {
        await new Promise(r => setTimeout(r, 40));
      }
    }

    conn.send({
      type: 'file-done',
      txId,
      name: f.name
    });

    tObj.done = totalBytes;
    tObj.state = 1;
    renderTransfers();
    showToast(`Sent ${f.name} across the internet ✓`);

    setTimeout(() => {
      activeTransfers = activeTransfers.filter(t => t.id !== txId);
      renderTransfers();
    }, 4000);
  }
}

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

    if (peer.isInternet && peer.conn) {
      peer.conn.send({ type: 'text', text });
      showToast(`Sent clipboard to ${peer.name} ✓`);
    } else {
      await sendTextToPeer(peer, text);
    }
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

  if (peer.isInternet && peer.conn) {
    peer.conn.send({ type: 'text', text });
    showToast(`Sent note to ${peer.name} across the internet ✓`);
  } else {
    await sendTextToPeer(peer, text);
  }

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
initWebRTC();

setInterval(fetchPeers, 1500);
setInterval(fetchTransfers, 600);
setInterval(fetchPendingRequests, 1000);
setInterval(fetchHistory, 3500);
