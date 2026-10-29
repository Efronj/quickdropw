const http = require('http');
const dgram = require('dgram');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { exec, spawn } = require('child_process');
const crypto = require('crypto');

const UDP_PORT = 52222;
const DEFAULT_HTTP_PORT = 52223;
let HTTP_PORT = DEFAULT_HTTP_PORT;

// Storage directory: ~/Downloads/QuickDrop
const DOWNLOAD_DIR = path.join(os.homedir(), 'Downloads', 'QuickDrop');
if (!fs.existsSync(DOWNLOAD_DIR)) {
  fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
}

// Config file path
const CONFIG_FILE = path.join(os.homedir(), '.quickdrop-desktop.json');

// Default config
let config = {
  id: crypto.randomUUID().slice(0, 8),
  name: `${os.hostname()} (PC)`,
  autoAccept: true,
  trusted: [],
};

// Load saved config if exists
try {
  if (fs.existsSync(CONFIG_FILE)) {
    const saved = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    config = { ...config, ...saved };
  }
} catch (e) {
  console.warn('Could not read config file, using defaults');
}

function saveConfig() {
  try {
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2), 'utf8');
  } catch (e) {
    console.error('Could not save config:', e.message);
  }
}

// History persistence
const HISTORY_FILE = path.join(os.homedir(), '.quickdrop-history.json');
let history = [];
try {
  if (fs.existsSync(HISTORY_FILE)) {
    history = JSON.parse(fs.readFileSync(HISTORY_FILE, 'utf8'));
  }
} catch (e) {
  history = [];
}

function saveHistory() {
  try {
    fs.writeFileSync(HISTORY_FILE, JSON.stringify(history.slice(0, 100), null, 2), 'utf8');
  } catch (e) {
    console.error('Could not save history:', e.message);
  }
}

function addHistory(item) {
  history.unshift({
    id: 'h_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6),
    time: Date.now(),
    ...item
  });
  saveHistory();
}

// Discovered peers: map of id -> { id, name, ip, port, deviceType, os, lastSeen }
const peers = new Map();

// Active transfers: map of id -> Transfer
const transfers = new Map();

// Pending incoming requests awaiting user confirmation: map of transferId -> { req, res, info }
const pendingRequests = new Map();

// Local IP address helper
function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        addresses.push(net.address);
      }
    }
  }
  return addresses;
}

// ─────────────────────────────────────────────────────────────
// UDP Discovery (Beacon Broadcast + Listener)
// ─────────────────────────────────────────────────────────────

const udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udpSocket.on('error', (err) => {
  console.warn(`UDP socket warning: ${err.message}`);
});

udpSocket.on('message', (msg, rinfo) => {
  try {
    const str = msg.toString('utf8');
    const data = JSON.parse(str);

    // Ignore self broadcasts
    if (data.id === config.id) return;

    if (data.type === 'quickdrop-ping' || data.type === 'quickdrop-pong') {
      const peer = {
        id: data.id,
        name: data.name || 'Nearby Device',
        deviceType: data.deviceType || 'mobile',
        os: data.os || 'Unknown',
        ip: rinfo.address,
        port: data.port || DEFAULT_HTTP_PORT,
        lastSeen: Date.now()
      };
      peers.set(peer.id, peer);

      // If it was a ping, reply with pong immediately so sender discovers us instantly
      if (data.type === 'quickdrop-ping') {
        const pong = JSON.stringify({
          type: 'quickdrop-pong',
          id: config.id,
          name: config.name,
          deviceType: 'desktop',
          os: 'Windows',
          port: HTTP_PORT,
          version: '2.0'
        });
        udpSocket.send(pong, rinfo.port, rinfo.address, () => {});
      }
    }
  } catch (e) {
    // Non-JSON or foreign packet, ignore
  }
});

udpSocket.bind(UDP_PORT, () => {
  try {
    udpSocket.setBroadcast(true);
  } catch (e) {}
  console.log(`[QuickDrop] UDP Discovery active on port ${UDP_PORT}`);
});

function broadcastPresence() {
  const message = Buffer.from(JSON.stringify({
    type: 'quickdrop-ping',
    id: config.id,
    name: config.name,
    deviceType: 'desktop',
    os: 'Windows',
    port: HTTP_PORT,
    version: '2.0'
  }));

  // Broadcast to global subnet and local network interfaces
  udpSocket.send(message, 0, message.length, UDP_PORT, '255.255.255.255', () => {});

  const ips = getLocalIPs();
  for (const ip of ips) {
    const parts = ip.split('.');
    parts[3] = '255';
    const bcast = parts.join('.');
    udpSocket.send(message, 0, message.length, UDP_PORT, bcast, () => {});
  }

  // Purge peers not seen for 8 seconds
  const now = Date.now();
  for (const [id, peer] of peers.entries()) {
    if (now - peer.lastSeen > 8000) {
      peers.delete(id);
    }
  }
}

setInterval(broadcastPresence, 2000);

// ─────────────────────────────────────────────────────────────
// Transfer Management & Speed Calculation
// ─────────────────────────────────────────────────────────────

function updateTransfer(id, delta) {
  const curr = transfers.get(id) || {};
  const updated = { ...curr, ...delta };
  const now = Date.now();

  if (!updated.startTime) updated.startTime = now;

  if (delta.done !== undefined && curr.done !== undefined) {
    const elapsed = (now - (curr.lastUpdate || updated.startTime)) / 1000;
    if (elapsed >= 0.3) {
      const bytesDiff = delta.done - curr.done;
      const instSpeed = bytesDiff / elapsed;
      updated.speed = curr.speed ? curr.speed * 0.6 + instSpeed * 0.4 : instSpeed;
      updated.lastUpdate = now;
    }
  }

  transfers.set(id, updated);
  return updated;
}

function cleanFilename(originalName) {
  let safe = path.basename(originalName).replace(/[<>:"/\\|?*]/g, '_');
  if (!safe) safe = `QuickDrop_${Date.now()}`;
  let dest = path.join(DOWNLOAD_DIR, safe);
  let ext = path.extname(safe);
  let base = path.basename(safe, ext);
  let counter = 1;

  while (fs.existsSync(dest)) {
    dest = path.join(DOWNLOAD_DIR, `${base} (${counter})${ext}`);
    counter++;
  }
  return dest;
}

// ─────────────────────────────────────────────────────────────
// HTTP Server & API Endpoints
// ─────────────────────────────────────────────────────────────

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = parsedUrl.pathname;

  // CORS headers for local network web UI
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Transfer-Id, X-File-Index, X-File-Name, X-File-Size, X-Sender-Name');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // API Endpoints
  if (pathname === '/api/info') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: config.id,
      name: config.name,
      deviceType: 'desktop',
      os: 'Windows',
      port: HTTP_PORT,
      ips: getLocalIPs(),
      downloadDir: DOWNLOAD_DIR,
      autoAccept: config.autoAccept,
      version: '2.0'
    }));
    return;
  }

  if (pathname === '/api/peers') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(Array.from(peers.values())));
    return;
  }

  if (pathname === '/api/transfers') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(Array.from(transfers.values())));
    return;
  }

  if (pathname === '/api/history') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(history));
    return;
  }

  if (pathname === '/api/history/clear' && req.method === 'POST') {
    history = [];
    saveHistory();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true }));
    return;
  }

  if (pathname === '/api/settings') {
    if (req.method === 'POST') {
      let body = '';
      req.on('data', chunk => body += chunk);
      req.on('end', () => {
        try {
          const updates = JSON.parse(body);
          if (updates.name) config.name = updates.name.trim();
          if (typeof updates.autoAccept === 'boolean') config.autoAccept = updates.autoAccept;
          saveConfig();
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(config));
        } catch (e) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Invalid settings' }));
        }
      });
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(config));
    }
    return;
  }

  if (pathname === '/api/open-downloads' && req.method === 'POST') {
    if (process.platform === 'win32') {
      spawn('explorer.exe', [DOWNLOAD_DIR], { detached: true });
    } else if (process.platform === 'darwin') {
      spawn('open', [DOWNLOAD_DIR], { detached: true });
    } else {
      spawn('xdg-open', [DOWNLOAD_DIR], { detached: true });
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true }));
    return;
  }

  if (pathname === '/api/open-file' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { filePath } = JSON.parse(body);
        if (filePath && fs.existsSync(filePath)) {
          if (process.platform === 'win32') {
            spawn('cmd.exe', ['/c', 'start', '""', filePath], { detached: true });
          } else if (process.platform === 'darwin') {
            spawn('open', [filePath], { detached: true });
          } else {
            spawn('xdg-open', [filePath], { detached: true });
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ success: true }));
        } else {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'File not found' }));
        }
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // Windows native file dialog invocation
  if (pathname === '/api/pick-files' && req.method === 'POST') {
    const psScript = `
Add-Type -AssemblyName System.Windows.Forms
$dialog = New-Object System.Windows.Forms.OpenFileDialog
$dialog.Multiselect = $true
$dialog.Title = 'Select images, videos or files to send with QuickDrop'
$dialog.Filter = 'All Files (*.*)|*.*|Images & Videos (*.jpg;*.jpeg;*.png;*.mp4;*.mkv;*.mov)|*.jpg;*.jpeg;*.png;*.mp4;*.mkv;*.mov'
if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
  $dialog.FileNames | ConvertTo-Json
} else {
  "[]"
}
`;
    exec(`powershell -NoProfile -Command "${psScript.replace(/"/g, '\\"').replace(/\n/g, '; ')}"`, (err, stdout) => {
      if (err) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
        return;
      }
      try {
        const raw = stdout.trim();
        let files = [];
        if (raw.startsWith('[')) files = JSON.parse(raw);
        else if (raw.startsWith('"')) files = [JSON.parse(raw)];
        
        // Enrich files with size and name
        const detailed = files.map(f => {
          try {
            const st = fs.statSync(f);
            return { path: f, name: path.basename(f), size: st.size };
          } catch (e) {
            return null;
          }
        }).filter(Boolean);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ files: detailed }));
      } catch (e) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ files: [] }));
      }
    });
    return;
  }

  // ─────────────────────────────────────────────────────────────
  // Incoming Transfer Handling (From Android or other PC)
  // ─────────────────────────────────────────────────────────────

  // Android requests permission to send files
  if (pathname === '/api/transfer/request' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const reqData = JSON.parse(body);
        const { transferId, senderName, files, pin } = reqData;

        // Auto-accept if configured or sender is trusted
        const shouldAutoAccept = config.autoAccept || (senderName && config.trusted.includes(senderName));

        if (shouldAutoAccept) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ accepted: true }));
          return;
        }

        // Otherwise hold request and notify UI
        const timeout = setTimeout(() => {
          if (pendingRequests.has(transferId)) {
            pendingRequests.delete(transferId);
            try {
              res.writeHead(200, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ accepted: false, reason: 'Timed out' }));
            } catch (e) {}
          }
        }, 45000);

        pendingRequests.set(transferId, {
          res,
          timeout,
          info: { transferId, senderName, files, pin, time: Date.now() }
        });

      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Malformed request' }));
      }
    });
    return;
  }

  // UI responds to pending request
  if (pathname === '/api/pending-requests') {
    if (req.method === 'GET') {
      const list = Array.from(pendingRequests.values()).map(p => p.info);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(list));
      return;
    }

    if (req.method === 'POST') {
      let body = '';
      req.on('data', chunk => body += chunk);
      req.on('end', () => {
        try {
          const { transferId, accepted, trust } = JSON.parse(body);
          const pending = pendingRequests.get(transferId);
          if (!pending) {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Request expired or not found' }));
            return;
          }

          clearTimeout(pending.timeout);
          pendingRequests.delete(transferId);

          if (trust && pending.info.senderName) {
            if (!config.trusted.includes(pending.info.senderName)) {
              config.trusted.push(pending.info.senderName);
              saveConfig();
            }
          }

          pending.res.writeHead(200, { 'Content-Type': 'application/json' });
          pending.res.end(JSON.stringify({ accepted: !!accepted }));

          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ success: true }));
        } catch (e) {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: e.message }));
        }
      });
      return;
    }
  }

  // Android uploads a file directly
  if (pathname === '/api/transfer/upload' && req.method === 'POST') {
    const transferId = req.headers['x-transfer-id'] || 'tx_' + Date.now();
    const rawFileName = decodeURIComponent(req.headers['x-file-name'] || 'QuickDrop_file.bin');
    const totalBytes = parseInt(req.headers['x-file-size'] || req.headers['content-length'] || '0', 10);
    const senderName = decodeURIComponent(req.headers['x-sender-name'] || 'Android Phone');
    const mime = req.headers['content-type'] || 'application/octet-stream';

    const savePath = cleanFilename(rawFileName);
    const fileName = path.basename(savePath);
    const writeStream = fs.createWriteStream(savePath);

    let receivedBytes = 0;

    const tObj = updateTransfer(transferId, {
      id: transferId,
      name: fileName,
      total: totalBytes,
      done: 0,
      outgoing: false,
      peer: senderName,
      state: 0, // active
      speed: 0,
      path: savePath,
      mime
    });

    req.on('data', chunk => {
      receivedBytes += chunk.length;
      updateTransfer(transferId, { done: receivedBytes });
    });

    req.pipe(writeStream);

    writeStream.on('finish', () => {
      updateTransfer(transferId, {
        done: receivedBytes,
        total: receivedBytes,
        state: 1 // completed
      });

      addHistory({
        name: fileName,
        size: receivedBytes,
        mime,
        outgoing: false,
        peer: senderName,
        path: savePath
      });

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true, savedAs: fileName, path: savePath }));

      // Clean up active transfers after 4s
      setTimeout(() => transfers.delete(transferId), 4000);
    });

    writeStream.on('error', (err) => {
      console.error('File write error:', err);
      updateTransfer(transferId, { state: 2 }); // failed
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    });

    req.on('error', (err) => {
      console.error('Upload stream error:', err);
      updateTransfer(transferId, { state: 2 }); // failed
      writeStream.destroy();
    });

    return;
  }

  // Incoming text / note
  if (pathname === '/api/transfer/text' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { text, senderName } = JSON.parse(body);
        if (text) {
          addHistory({
            name: 'Text Note',
            size: Buffer.byteLength(text, 'utf8'),
            mime: 'text/plain',
            outgoing: false,
            peer: senderName || 'Nearby Device',
            text
          });
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // ─────────────────────────────────────────────────────────────
  // Outgoing Transfer (Send from Desktop to Android / PC)
  // ─────────────────────────────────────────────────────────────

  // Send local file paths to a peer
  if (pathname === '/api/send-paths' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', async () => {
      try {
        const { peerId, filePaths } = JSON.parse(body);
        const peer = peers.get(peerId);
        if (!peer) {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Device no longer reachable' }));
          return;
        }

        // Validate files
        const validFiles = [];
        for (const fp of filePaths) {
          if (fs.existsSync(fp)) {
            const st = fs.statSync(fp);
            validFiles.push({
              path: fp,
              name: path.basename(fp),
              size: st.size
            });
          }
        }

        if (validFiles.length === 0) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'No valid files selected' }));
          return;
        }

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ queued: validFiles.length }));

        // Execute transfer in background
        sendFilesToPeer(peer, validFiles);
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // Send text note to peer
  if (pathname === '/api/send-text' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { peerId, text } = JSON.parse(body);
        const peer = peers.get(peerId);
        if (!peer) {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Device not reachable' }));
          return;
        }

        const payload = JSON.stringify({ text, senderName: config.name });
        const postReq = http.request({
          hostname: peer.ip,
          port: peer.port,
          path: '/api/transfer/text',
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(payload)
          },
          timeout: 8000
        }, (postRes) => {
          addHistory({
            name: 'Text Note',
            size: Buffer.byteLength(text),
            mime: 'text/plain',
            outgoing: true,
            peer: peer.name,
            text
          });
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ success: true }));
        });

        postReq.on('error', (err) => {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Failed to send: ' + err.message }));
        });

        postReq.write(payload);
        postReq.end();
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // Web Browser Direct Upload to PC (Drag & drop from web UI on another phone or computer)
  if (pathname === '/api/upload-direct' && req.method === 'POST') {
    const rawFileName = decodeURIComponent(req.headers['x-file-name'] || 'UploadedFile.bin');
    const totalBytes = parseInt(req.headers['x-file-size'] || req.headers['content-length'] || '0', 10);
    const savePath = cleanFilename(rawFileName);
    const fileName = path.basename(savePath);
    const writeStream = fs.createWriteStream(savePath);
    const transferId = 'direct_' + Date.now();

    updateTransfer(transferId, {
      id: transferId,
      name: fileName,
      total: totalBytes,
      done: 0,
      outgoing: false,
      peer: 'Web Browser',
      state: 0,
      speed: 0,
      path: savePath
    });

    let done = 0;
    req.on('data', chunk => {
      done += chunk.length;
      updateTransfer(transferId, { done });
    });

    req.pipe(writeStream);

    writeStream.on('finish', () => {
      updateTransfer(transferId, { done, total: done, state: 1 });
      addHistory({
        name: fileName,
        size: done,
        mime: 'application/octet-stream',
        outgoing: false,
        peer: 'Web Browser',
        path: savePath
      });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true, fileName }));
      setTimeout(() => transfers.delete(transferId), 4000);
    });

    writeStream.on('error', err => {
      updateTransfer(transferId, { state: 2 });
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    });

    return;
  }

  // ─────────────────────────────────────────────────────────────
  // Static File Serving (Web UI)
  // ─────────────────────────────────────────────────────────────

  let filePath = path.join(__dirname, 'public', pathname === '/' ? 'index.html' : pathname);

  // Security: prevent directory traversal
  if (!filePath.startsWith(path.join(__dirname, 'public'))) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const mimeTypes = {
      '.html': 'text/html; charset=utf-8',
      '.css': 'text/css; charset=utf-8',
      '.js': 'application/javascript; charset=utf-8',
      '.json': 'application/json',
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.svg': 'image/svg+xml',
      '.ico': 'image/x-icon'
    };

    const contentType = mimeTypes[ext] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': contentType });
    fs.createReadStream(filePath).pipe(res);
  });
});

// Helper: Stream files to peer
async function sendFilesToPeer(peer, files) {
  const transferId = 'tx_' + Date.now();

  // 1. Send request
  const requestPayload = JSON.stringify({
    transferId,
    senderName: config.name,
    files: files.map(f => ({ name: f.name, size: f.size })),
    pin: Math.floor(1000 + Math.random() * 9000).toString()
  });

  const accepted = await new Promise((resolve) => {
    const postReq = http.request({
      hostname: peer.ip,
      port: peer.port,
      path: '/api/transfer/request',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(requestPayload)
      },
      timeout: 20000
    }, (res) => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        try {
          const parsed = JSON.parse(data);
          resolve(parsed.accepted === true);
        } catch (e) {
          resolve(false);
        }
      });
    });

    postReq.on('error', () => resolve(false));
    postReq.on('timeout', () => {
      postReq.destroy();
      resolve(false);
    });

    postReq.write(requestPayload);
    postReq.end();
  });

  if (!accepted) {
    updateTransfer(transferId, {
      id: transferId,
      name: `${files.length} file(s)`,
      total: files.reduce((acc, f) => acc + f.size, 0),
      done: 0,
      outgoing: true,
      peer: peer.name,
      state: 2, // declined / failed
      speed: 0
    });
    setTimeout(() => transfers.delete(transferId), 4000);
    return;
  }

  // 2. Stream files sequentially
  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    const fileTxId = `${transferId}_${i}`;

    updateTransfer(fileTxId, {
      id: fileTxId,
      name: file.name,
      total: file.size,
      done: 0,
      outgoing: true,
      peer: peer.name,
      state: 0,
      speed: 0,
      path: file.path
    });

    await new Promise((resolve) => {
      const uploadReq = http.request({
        hostname: peer.ip,
        port: peer.port,
        path: '/api/transfer/upload',
        method: 'POST',
        headers: {
          'X-Transfer-Id': fileTxId,
          'X-File-Index': i.toString(),
          'X-File-Name': encodeURIComponent(file.name),
          'X-File-Size': file.size.toString(),
          'X-Sender-Name': encodeURIComponent(config.name),
          'Content-Type': 'application/octet-stream',
          'Content-Length': file.size
        }
      }, (uploadRes) => {
        uploadRes.on('data', () => {});
        uploadRes.on('end', () => {
          updateTransfer(fileTxId, {
            done: file.size,
            total: file.size,
            state: 1 // finished
          });

          addHistory({
            name: file.name,
            size: file.size,
            mime: 'application/octet-stream',
            outgoing: true,
            peer: peer.name,
            path: file.path
          });

          setTimeout(() => transfers.delete(fileTxId), 4000);
          resolve(true);
        });
      });

      uploadReq.on('error', (err) => {
        console.error('Upload stream error:', err);
        updateTransfer(fileTxId, { state: 2 });
        setTimeout(() => transfers.delete(fileTxId), 4000);
        resolve(false);
      });

      const readStream = fs.createReadStream(file.path, { highWaterMark: 128 * 1024 });
      let sentBytes = 0;

      readStream.on('data', chunk => {
        sentBytes += chunk.length;
        updateTransfer(fileTxId, { done: sentBytes });
      });

      readStream.pipe(uploadReq);
    });
  }
}

// ─────────────────────────────────────────────────────────────
// Start Server & Auto Launch Native App Window
// ─────────────────────────────────────────────────────────────

function startServer(port) {
  server.listen(port, () => {
    HTTP_PORT = port;
    console.log(`=======================================================`);
    console.log(`  🚀 QuickDrop Desktop Server running!`);
    console.log(`  Local URL:   http://localhost:${HTTP_PORT}`);
    const ips = getLocalIPs();
    for (const ip of ips) {
      console.log(`  Network URL: http://${ip}:${HTTP_PORT}`);
    }
    console.log(`  Downloads:   ${DOWNLOAD_DIR}`);
    console.log(`=======================================================`);

    // Auto-launch native window in standalone App mode
    launchAppWindow(`http://localhost:${HTTP_PORT}`);
  });

  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.log(`Port ${port} in use, trying ${port + 1}...`);
      startServer(port + 1);
    } else {
      console.error('Server error:', err);
    }
  });
}

function launchAppWindow(url) {
  if (process.argv.includes('--no-window')) return;

  const edgePaths = [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    process.env.LOCALAPPDATA + '\\Microsoft\\Edge\\Application\\msedge.exe'
  ];

  const chromePaths = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    process.env.LOCALAPPDATA + '\\Google\\Chrome\\Application\\chrome.exe'
  ];

  let browserPath = edgePaths.find(p => fs.existsSync(p)) || chromePaths.find(p => fs.existsSync(p));

  if (browserPath) {
    const userDir = path.join(os.tmpdir(), 'quickdrop-browser-profile');
    spawn(browserPath, [
      `--app=${url}`,
      `--user-data-dir=${userDir}`,
      '--window-size=1020,720',
      '--window-position=120,80',
      '--disable-extensions'
    ], { detached: true, stdio: 'ignore' }).unref();
  } else {
    // Fallback: default browser
    if (process.platform === 'win32') {
      exec(`start ${url}`);
    } else if (process.platform === 'darwin') {
      exec(`open ${url}`);
    } else {
      exec(`xdg-open ${url}`);
    }
  }
}

startServer(DEFAULT_HTTP_PORT);
