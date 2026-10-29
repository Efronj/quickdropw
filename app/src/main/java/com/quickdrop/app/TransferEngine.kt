package com.quickdrop.app

import android.app.Application
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Universal Transfer Engine:
 * Supports high-speed Wi-Fi LAN transfers (with Desktop PCs & Android phones)
 * + Google Nearby Connections P2P (Wi-Fi Direct between Android devices).
 */
class TransferEngine(
    private val app: Application,
    private val store: Store,
    private val scope: CoroutineScope,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(app)
    private val service = "com.quickdrop.app.v1"
    private val strategy = Strategy.P2P_POINT_TO_POINT

    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val connected = MutableStateFlow<Peer?>(null)
    val incoming = MutableStateFlow<Incoming?>(null)
    val transfers = MutableStateFlow<Map<Long, Transfer>>(emptyMap())
    val status = MutableStateFlow("Ready to share with phones & PCs")
    val history = MutableStateFlow(store.history())
    val pending = MutableStateFlow<List<Outgoing>>(emptyList())
    val trusted = MutableStateFlow(store.trusted)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private var started = false
    private val nearbyPeers = ConcurrentHashMap<String, Peer>()
    private var lanPeers = emptyList<Peer>()
    private var pendingLanDeferred: CompletableDeferred<Boolean>? = null

    // Nearby Connections state
    private val names = HashMap<String, String>()
    private val meta = ConcurrentHashMap<Long, Meta>()
    private val fileIds = HashSet<Long>()
    private val outgoingIds = HashSet<Long>()
    private val outUris = HashMap<Long, Uri>()
    private val inPayloads = HashMap<Long, Payload>()
    private val samples = HashMap<Long, Pair<Long, Long>>()
    private val finished = HashSet<Long>()

    // Cross-platform LAN / Wi-Fi Engine
    private val lanEngine = LanEngine(
        app = app,
        store = store,
        scope = scope,
        onPeersChanged = { list ->
            lanPeers = list
            updateCombinedPeers()
        },
        onIncomingRequest = { inc, deferred ->
            pendingLanDeferred = deferred
            incoming.value = inc
        },
        onTransferUpdate = { t ->
            transfers.update { it + (t.id to t) }
        },
        onTransferFinished = { _, _, h ->
            addHistory(h)
        },
        onEvent = { msg ->
            events.tryEmit(msg)
        },
    )

    // Internet / Anywhere WebRTC Engine (5G, 4G, Cross-network Wi-Fi)
    private val webRtcBridge = WebRtcBridge(
        app = app,
        store = store,
        scope = scope,
        onPeerConnected = { peer ->
            connected.value = peer
            status.value = "Connected to " + peer.name + " across the internet 🌐"
            updateCombinedPeers()
            val queued = pending.value
            if (queued.isNotEmpty()) {
                pending.value = emptyList()
                send(queued)
            }
        },
        onPeerDisconnected = { peerId ->
            if (connected.value?.id == peerId) {
                connected.value = null
                status.value = "Ready to share with phones & PCs"
            }
            updateCombinedPeers()
        },
        onTransferUpdate = { t ->
            transfers.update { it + (t.id to t) }
        },
        onTransferFinished = { _, _, h ->
            addHistory(h)
        },
        onEvent = { msg ->
            events.tryEmit(msg)
        },
    )

    val webRtcCode get() = webRtcBridge.shareCode

    fun connectViaCode(code: String) {
        webRtcBridge.connectToCode(code)
    }

    // ───────────────────────── discovery / advertising ─────────────────────────

    fun start() {
        started = true
        lanEngine.start()
        webRtcBridge.start()

        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
        }

        nearbyPeers.clear()
        updateCombinedPeers()

        if (connected.value != null) return
        status.value = if (store.visible) "Looking for nearby phones & PCs…" else "Hidden — you can still discover others"

        if (store.visible) {
            client.startAdvertising(
                store.nickname,
                service,
                lifecycleCb,
                AdvertisingOptions.Builder().setStrategy(strategy).build(),
            ).addOnFailureListener {
                // Nearby failed, LAN will still work
            }
        }
        client.startDiscovery(
            service,
            discoveryCb,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener {
            // Nearby failed, LAN will still work
        }
    }

    fun restart() {
        if (started) start()
    }

    fun shutdown() {
        started = false
        lanEngine.stop()
        webRtcBridge.stop()
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
    }

    private fun updateCombinedPeers() {
        val list = mutableListOf<Peer>()
        val intPeer = webRtcBridge.connectedPeer.value
        if (intPeer != null) {
            list.add(intPeer)
        }
        list.addAll(lanPeers)
        val existingNames = (lanPeers.map { it.name } + (intPeer?.name ?: "")).toSet()
        for (np in nearbyPeers.values) {
            if (!existingNames.contains(np.name)) {
                list.add(np)
            }
        }
        peers.value = list
    }

    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            names[endpointId] = info.endpointName
            nearbyPeers[endpointId] = Peer(endpointId, info.endpointName, deviceType = "mobile", isLan = false)
            updateCombinedPeers()
        }

        override fun onEndpointLost(endpointId: String) {
            nearbyPeers.remove(endpointId)
            updateCombinedPeers()
        }
    }

    // ───────────────────────── connection ─────────────────────────

    fun connect(peer: Peer) {
        if (peer.isInternet) {
            connected.value = peer
            status.value = "Connected to " + peer.name + " across the internet 🌐"
            val queued = pending.value
            if (queued.isNotEmpty()) {
                pending.value = emptyList()
                send(queued)
            }
            return
        }

        if (peer.isLan) {
            // For LAN peers (Desktop PC or phone), direct HTTP connection requires no handshake!
            connected.value = peer
            status.value = "Ready to send to ${peer.name}"
            val queued = pending.value
            if (queued.isNotEmpty()) {
                pending.value = emptyList()
                send(queued)
            }
            return
        }

        // Nearby Connections P2P
        status.value = "Connecting to ${peer.name}…"
        client.requestConnection(store.nickname, peer.id, lifecycleCb)
            .addOnFailureListener { e ->
                status.value = "Couldn't reach ${peer.name}"
                events.tryEmit("Connection failed: ${e.message ?: "try again"}")
            }
    }

    fun acceptIncoming(trust: Boolean) {
        val inc = incoming.value ?: return
        if (trust) {
            store.trusted = store.trusted + inc.name
            trusted.value = store.trusted
        }
        incoming.value = null

        val deferred = pendingLanDeferred
        if (deferred != null) {
            deferred.complete(true)
            pendingLanDeferred = null
            return
        }

        client.acceptConnection(inc.id, payloadCb)
    }

    fun declineIncoming() {
        val inc = incoming.value ?: return
        incoming.value = null

        val deferred = pendingLanDeferred
        if (deferred != null) {
            deferred.complete(false)
            pendingLanDeferred = null
            return
        }

        client.rejectConnection(inc.id)
    }

    fun untrust(name: String) {
        store.trusted = store.trusted - name
        trusted.value = store.trusted
    }

    fun disconnect() {
        val cur = connected.value
        if (cur != null) {
            if (cur.isInternet) {
                webRtcBridge.disconnect()
            } else if (!cur.isLan) {
                runCatching { client.disconnectFromEndpoint(cur.id) }
            }
        }
        connected.value = null
        failActive()
        start()
    }

    private fun failActive() {
        transfers.update { m -> m.mapValues { (_, t) -> if (t.active) t.copy(state = 2) else t } }
    }

    private val lifecycleCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            names[endpointId] = info.endpointName
            val isTrusted = store.trusted.contains(info.endpointName)
            if (info.isIncomingConnection && !isTrusted) {
                incoming.value = Incoming(endpointId, info.endpointName, info.authenticationDigits, isLan = false)
            } else {
                if (!info.isIncomingConnection) {
                    status.value = "Waiting for ${info.endpointName} to accept · code ${info.authenticationDigits}"
                }
                client.acceptConnection(endpointId, payloadCb)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            incoming.value = null
            if (result.status.isSuccess) {
                val n = names[endpointId] ?: "Device"
                connected.value = Peer(endpointId, n, deviceType = "mobile", isLan = false)
                status.value = "Connected to $n"
                client.stopAdvertising()
                client.stopDiscovery()
                peers.value = emptyList()
                val queued = pending.value
                if (queued.isNotEmpty()) {
                    pending.value = emptyList()
                    send(queued)
                }
            } else {
                status.value = "Connection declined or failed"
                start()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.value = null
            failActive()
            status.value = "Disconnected"
            start()
        }
    }

    // ───────────────────────── sending ─────────────────────────

    fun queueOrSend(items: List<Outgoing>) = send(items)

    fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        send(uris.map { Outgoing.Media(it) })
    }

    fun sendText(text: String) {
        if (text.isNotBlank()) send(listOf(Outgoing.Txt(text)))
    }

    private fun send(items: List<Outgoing>) {
        if (items.isEmpty()) return
        val peer = connected.value
        if (peer == null) {
            pending.update { it + items }
            events.tryEmit("${items.size} item(s) ready! Tap a device to send.")
            return
        }

        // WebRTC Internet transfer (5G, 4G, remote Wi-Fi)
        if (peer.isInternet) {
            val uris = items.filterIsInstance<Outgoing.Media>().map { it.uri }
            if (uris.isNotEmpty()) {
                webRtcBridge.sendUris(peer, uris)
            }
            val texts = items.filterIsInstance<Outgoing.Txt>().map { it.text }
            for (t in texts) {
                webRtcBridge.sendText(peer, t)
            }
            return
        }

        // LAN Wi-Fi transfer to Desktop or phone
        if (peer.isLan) {
            val uris = items.filterIsInstance<Outgoing.Media>().map { it.uri }
            if (uris.isNotEmpty()) {
                lanEngine.sendUrisToPeer(peer, uris)
            }
            val texts = items.filterIsInstance<Outgoing.Txt>().map { it.text }
            for (t in texts) {
                lanEngine.sendTextToPeer(peer, t)
            }
            return
        }

        // Nearby Connections P2P transfer
        for (item in items) {
            when (item) {
                is Outgoing.Media -> pushMedia(peer, item.uri)
                is Outgoing.Txt -> pushText(peer, item.text)
            }
        }
    }

    fun clearPending() {
        pending.value = emptyList()
    }

    private fun pushMedia(peer: Peer, uri: Uri) {
        val r = app.contentResolver
        var name = ""
        var size = 0L

        runCatching {
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    size = c.getLong(1)
                }
            }
        }

        val mime = r.getType(uri) ?: "application/octet-stream"
        val pfd = runCatching { r.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd == null) {
            events.tryEmit("Can't read selected file")
            return
        }

        if (size <= 0L) {
            size = pfd.statSize.coerceAtLeast(0L)
        }

        if (name.isBlank() || name == "file") {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val prefix = when {
                mime.startsWith("image/") -> "Photo"
                mime.startsWith("video/") -> "Video"
                else -> "File"
            }
            name = "${prefix}_${System.currentTimeMillis() % 100000}.$ext"
        }

        val payload = Payload.fromFile(pfd)
        val pid = payload.id
        fileIds.add(pid)
        outgoingIds.add(pid)
        outUris[pid] = uri
        meta[pid] = Meta(name, mime, size)

        client.sendPayload(peer.id, Payload.fromBytes("F|$pid|$mime|$size|$name".toByteArray(Charsets.UTF_8)))
        client.sendPayload(peer.id, payload)
        putTransfer(Transfer(pid, name, size, 0, true, 0.0, 0))
    }

    private fun pushText(peer: Peer, text: String) {
        val bytes = "T|$text".toByteArray(Charsets.UTF_8)
        if (bytes.size > 32_000) {
            events.tryEmit("Text is too long (max ~30 KB)")
            return
        }
        client.sendPayload(peer.id, Payload.fromBytes(bytes))
        addHistory(
            HistoryItem(
                "t${System.nanoTime()}", "Text Note", text.length.toLong(), "text/plain",
                true, peer.name, System.currentTimeMillis(), null, text,
            )
        )
        events.tryEmit("Note sent ✓")
    }

    fun cancel(pid: Long) {
        client.cancelPayload(pid)
    }

    // ───────────────────────── receiving (Nearby Connections) ─────────────────────────

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { handleBytes(it) }
                Payload.Type.FILE -> {
                    val pid = payload.id
                    fileIds.add(pid)
                    inPayloads[pid] = payload
                    val m = meta[pid]
                    putTransfer(Transfer(pid, m?.name ?: "Incoming media…", m?.size ?: 0L, 0, false, 0.0, 0))
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            val pid = u.payloadId
            if (!fileIds.contains(pid)) return
            val out = outgoingIds.contains(pid)
            val now = SystemClock.elapsedRealtime()
            var speed = transfers.value[pid]?.speed ?: 0.0
            val prev = samples[pid]
            if (prev == null) {
                samples[pid] = now to u.bytesTransferred
            } else if (now - prev.first >= 400) {
                val inst = (u.bytesTransferred - prev.second) * 1000.0 / (now - prev.first)
                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                samples[pid] = now to u.bytesTransferred
            }
            val state = when (u.status) {
                PayloadTransferUpdate.Status.SUCCESS -> 1
                PayloadTransferUpdate.Status.FAILURE -> 2
                PayloadTransferUpdate.Status.CANCELED -> 3
                else -> 0
            }
            val name = meta[pid]?.name ?: if (out) "Sending…" else "Incoming file"
            val total = if (u.totalBytes > 0) u.totalBytes else (meta[pid]?.size ?: 0L)
            val done = if (state == 1) total else u.bytesTransferred
            putTransfer(Transfer(pid, name, total, done, out, speed, state))

            if (state == 1) finish(pid, out)
            if (state != 0) {
                scope.launch {
                    delay(3000)
                    transfers.update { it - pid }
                }
            }
            if (state == 2) events.tryEmit("Transfer failed: $name")
        }
    }

    private fun handleBytes(b: ByteArray) {
        val s = String(b, Charsets.UTF_8)
        when {
            s.startsWith("F|") -> {
                val p = s.split("|", limit = 5)
                if (p.size == 5) {
                    val id = p[1].toLongOrNull()
                    if (id != null) {
                        val m = Meta(p[4], p[2], p[3].toLongOrNull() ?: 0L)
                        meta[id] = m
                        transfers.update { map ->
                            val current = map[id]
                            if (current != null) map + (id to current.copy(name = m.name, total = m.size)) else map
                        }
                    }
                }
            }
            s.startsWith("T|") -> {
                val txt = s.substring(2)
                addHistory(
                    HistoryItem(
                        "t${System.nanoTime()}", "Text Note", txt.length.toLong(), "text/plain",
                        false, connected.value?.name ?: "Device", System.currentTimeMillis(), null, txt,
                    )
                )
                events.tryEmit("Note received — open History to copy")
            }
        }
    }

    private fun finish(pid: Long, out: Boolean) {
        if (!finished.add(pid)) return
        val peerName = connected.value?.name ?: "Device"
        if (out) {
            val m = meta[pid]
            addHistory(
                HistoryItem(
                    "o$pid", m?.name ?: "Media", m?.size ?: 0L, m?.mime ?: "",
                    true, peerName, System.currentTimeMillis(), outUris[pid]?.toString(), null,
                )
            )
            events.tryEmit("Sent ${m?.name ?: "file"} ✓")
        } else {
            val payload = inPayloads.remove(pid) ?: return
            scope.launch(Dispatchers.IO) {
                var m = meta[pid]
                var tries = 0
                while (m == null && tries < 30) {
                    delay(100)
                    m = meta[pid]
                    tries++
                }
                save(pid, payload, m, peerName)
            }
        }
    }

    private fun save(pid: Long, payload: Payload, m: Meta?, peerName: String) {
        try {
            val pFile = payload.asFile() ?: throw IllegalStateException("Payload file missing")
            val r = app.contentResolver

            val inStream: InputStream = when {
                pFile.asUri() != null -> r.openInputStream(pFile.asUri()!!)
                pFile.asJavaFile() != null -> java.io.FileInputStream(pFile.asJavaFile()!!)
                pFile.asParcelFileDescriptor() != null -> {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pFile.asParcelFileDescriptor()!!)
                }
                else -> null
            } ?: throw IllegalStateException("Cannot read received payload")

            val mime = m?.mime ?: "application/octet-stream"
            var name = m?.name ?: "QuickDrop_$pid"

            if (!name.contains(".")) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (!ext.isNullOrBlank()) {
                    name = "$name.$ext"
                }
            }

            val (collection, dir) = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }

            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/QuickDrop")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val dest = r.insert(collection, cv) ?: throw IllegalStateException("Cannot insert into MediaStore")

            inStream.use { input ->
                r.openOutputStream(dest)?.use { output ->
                    input.copyTo(output, 256 * 1024)
                }
            }

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            r.update(dest, done, null, null)

            runCatching {
                MediaScannerConnection.scanFile(app, arrayOf(dest.toString()), arrayOf(mime), null)
            }

            runCatching {
                if (pFile.asUri() != null) r.delete(pFile.asUri()!!, null, null)
                else pFile.asJavaFile()?.delete()
            }

            addHistory(
                HistoryItem(
                    "i$pid", name, m?.size ?: 0L, mime,
                    false, peerName, System.currentTimeMillis(), dest.toString(), null,
                )
            )

            val typeLabel = when {
                mime.startsWith("image/") -> "Photo"
                mime.startsWith("video/") -> "Video"
                else -> "File"
            }
            events.tryEmit("Saved $typeLabel: $name")
        } catch (e: Exception) {
            events.tryEmit("Couldn't save file: ${e.message}")
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun putTransfer(t: Transfer) {
        transfers.update { it + (t.id to t) }
    }

    private fun addHistory(h: HistoryItem) {
        history.update { listOf(h) + it }
        store.saveHistory(history.value)
    }

    fun clearHistory() {
        history.value = emptyList()
        store.saveHistory(emptyList())
    }
}
