package com.quickdrop.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cross-Platform Wi-Fi / Local Network Sharing Engine.
 * Discovers Desktop PCs and Android phones over UDP (port 52222)
 * and transfers files / media / notes at high-speed over HTTP (port 52223).
 */
class LanEngine(
    private val app: Application,
    private val store: Store,
    private val scope: CoroutineScope,
    private val onPeersChanged: (List<Peer>) -> Unit,
    private val onIncomingRequest: (Incoming, CompletableDeferred<Boolean>) -> Unit,
    private val onTransferUpdate: (Transfer) -> Unit,
    private val onTransferFinished: (Transfer, String?, HistoryItem) -> Unit,
    private val onEvent: (String) -> Unit,
) {
    companion object {
        const val UDP_PORT = 52222
        const val HTTP_PORT = 52223
    }

    val myId: String = UUID.randomUUID().toString().substring(0, 8)

    private val peerMap = ConcurrentHashMap<String, PeerRecord>()
    private var udpSocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var discoveryJob: Job? = null
    private var serverJob: Job? = null
    private var cleanupJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val activeTransfers = ConcurrentHashMap<Long, Transfer>()
    private val transferSamples = ConcurrentHashMap<Long, Pair<Long, Long>>()

    data class PeerRecord(
        val peer: Peer,
        var lastSeen: Long,
    )

    fun start() {
        stop()

        // Acquire Wi-Fi Multicast Lock so Android receives UDP broadcasts
        runCatching {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("quickdrop_lan_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        startUdpDiscovery()
        startHttpServer()
        startCleanupLoop()
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null

        serverJob?.cancel()
        serverJob = null

        cleanupJob?.cancel()
        cleanupJob = null

        runCatching { udpSocket?.close() }
        udpSocket = null

        runCatching { serverSocket?.close() }
        serverSocket = null

        runCatching { multicastLock?.release() }
        multicastLock = null

        peerMap.clear()
        onPeersChanged(emptyList())
    }

    // ───────────────────────── UDP Discovery ─────────────────────────

    private fun startUdpDiscovery() {
        discoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_PORT))
                }
                udpSocket = sock

                // Receiver coroutine
                launch {
                    val buf = ByteArray(2048)
                    while (isActive && !sock.isClosed) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            sock.receive(packet)
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            handleUdpPacket(text, packet.address)
                        } catch (e: Exception) {
                            if (!isActive || sock.isClosed) break
                        }
                    }
                }

                // Beacon sender coroutine
                launch {
                    while (isActive && !sock.isClosed) {
                        if (store.visible) {
                            sendUdpBeacon(sock)
                        }
                        delay(2500)
                    }
                }
            } catch (e: Exception) {
                onEvent("LAN Discovery notice: ${e.message}")
            }
        }
    }

    private fun sendUdpBeacon(sock: DatagramSocket) {
        val payload = JSONObject().apply {
            put("type", "quickdrop-ping")
            put("id", myId)
            put("name", store.nickname)
            put("deviceType", "mobile")
            put("os", "Android")
            put("port", HTTP_PORT)
            put("version", "2.0")
        }.toString().toByteArray(Charsets.UTF_8)

        // 1. Send to 255.255.255.255
        runCatching {
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), UDP_PORT))
        }

        // 2. Send to interface broadcast addresses
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.interfaceAddresses) {
                    val bcast = addr.broadcast ?: continue
                    sock.send(DatagramPacket(payload, payload.size, bcast, UDP_PORT))
                }
            }
        }
    }

    private fun handleUdpPacket(jsonStr: String, senderAddr: InetAddress) {
        runCatching {
            val json = JSONObject(jsonStr)
            val id = json.optString("id")
            if (id.isEmpty() || id == myId) return

            val type = json.optString("type")
            val name = json.optString("name", "Nearby Device")
            val devType = json.optString("deviceType", "mobile")
            val port = json.optInt("port", HTTP_PORT)
            val ip = senderAddr.hostAddress ?: return

            val peer = Peer(
                id = id,
                name = name,
                deviceType = devType,
                ip = ip,
                port = port,
                isLan = true,
            )

            peerMap[id] = PeerRecord(peer, System.currentTimeMillis())
            notifyPeers()

            // If it's a ping, respond with pong so they discover us immediately
            if (type == "quickdrop-ping") {
                val pong = JSONObject().apply {
                    put("type", "quickdrop-pong")
                    put("id", myId)
                    put("name", store.nickname)
                    put("deviceType", "mobile")
                    put("os", "Android")
                    put("port", HTTP_PORT)
                    put("version", "2.0")
                }.toString().toByteArray(Charsets.UTF_8)

                runCatching {
                    udpSocket?.send(DatagramPacket(pong, pong.size, senderAddr, UDP_PORT))
                }
            }
        }
    }

    private fun startCleanupLoop() {
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000)
                val now = System.currentTimeMillis()
                var changed = false
                val it = peerMap.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (now - entry.value.lastSeen > 8000) {
                        it.remove()
                        changed = true
                    }
                }
                if (changed) notifyPeers()
            }
        }
    }

    private fun notifyPeers() {
        val list = peerMap.values.map { it.peer }
        onPeersChanged(list)
    }

    // ───────────────────────── Embedded HTTP Server ─────────────────────────

    private fun startHttpServer() {
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val srv = ServerSocket(HTTP_PORT)
                serverSocket = srv

                while (isActive && !srv.isClosed) {
                    try {
                        val client = srv.accept()
                        launch(Dispatchers.IO) {
                            handleHttpClient(client)
                        }
                    } catch (e: Exception) {
                        if (!isActive || srv.isClosed) break
                    }
                }
            } catch (e: Exception) {
                onEvent("LAN Server note: ${e.message}")
            }
        }
    }

    private suspend fun handleHttpClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 30000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Parse request line and headers
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val reqLine = reader.readLine() ?: return@withContext
            val parts = reqLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            val headers = HashMap<String, String>()
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line.isNullOrEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            when {
                path == "/api/info" && method == "GET" -> {
                    val resp = JSONObject().apply {
                        put("id", myId)
                        put("name", store.nickname)
                        put("deviceType", "mobile")
                        put("os", "Android")
                        put("port", HTTP_PORT)
                        put("version", "2.0")
                    }.toString()
                    writeHttpResponse(output, 200, "application/json", resp.toByteArray(Charsets.UTF_8))
                }

                path == "/api/transfer/request" && method == "POST" -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyChars = CharArray(length)
                    var readChars = 0
                    while (readChars < length) {
                        val r = reader.read(bodyChars, readChars, length - readChars)
                        if (r <= 0) break
                        readChars += r
                    }
                    val body = String(bodyChars, 0, readChars)
                    handleIncomingTransferRequest(body, output)
                }

                path == "/api/transfer/upload" && method == "POST" -> {
                    // For file uploads, we read remaining raw bytes from the underlying socket InputStream!
                    handleIncomingFileUpload(headers, input, output)
                }

                path == "/api/transfer/text" && method == "POST" -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyChars = CharArray(length)
                    var readChars = 0
                    while (readChars < length) {
                        val r = reader.read(bodyChars, readChars, length - readChars)
                        if (r <= 0) break
                        readChars += r
                    }
                    val body = String(bodyChars, 0, readChars)
                    handleIncomingText(body, output)
                }

                else -> {
                    writeHttpResponse(output, 404, "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (e: Exception) {
            // Socket or client closed
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleIncomingTransferRequest(body: String, output: OutputStream) {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json == null) {
            writeHttpResponse(output, 400, "application/json", """{"error":"Invalid JSON"}""".toByteArray())
            return
        }

        val txId = json.optString("transferId", "tx_${System.currentTimeMillis()}")
        val senderName = json.optString("senderName", "Nearby Device")
        val pin = json.optString("pin", "")

        val isTrusted = store.trusted.contains(senderName)
        if (isTrusted) {
            writeHttpResponse(output, 200, "application/json", """{"accepted":true}""".toByteArray())
            return
        }

        // Ask user on UI
        val deferred = CompletableDeferred<Boolean>()
        val incoming = Incoming(txId, senderName, pin, isLan = true)
        onIncomingRequest(incoming, deferred)

        val accepted = withTimeoutOrNull(40000) { deferred.await() } ?: false
        val resp = JSONObject().apply { put("accepted", accepted) }.toString()
        writeHttpResponse(output, 200, "application/json", resp.toByteArray(Charsets.UTF_8))
    }

    private suspend fun handleIncomingFileUpload(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val rawName = headers["x-file-name"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: "ReceivedFile"
        val totalBytes = headers["x-file-size"]?.toLongOrNull() ?: headers["content-length"]?.toLongOrNull() ?: 0L
        val senderName = headers["x-sender-name"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: "Nearby Device"
        val mime = headers["content-type"] ?: "application/octet-stream"

        val txId = System.nanoTime()
        val transfer = Transfer(txId, rawName, totalBytes, 0, false, 0.0, 0)
        activeTransfers[txId] = transfer
        onTransferUpdate(transfer)

        try {
            // Save directly into MediaStore (Pictures / Movies / Downloads)
            val (collection, dir) = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }

            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, rawName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/QuickDrop")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val r = app.contentResolver
            val uri = r.insert(collection, cv) ?: throw IllegalStateException("Cannot insert into MediaStore")

            var doneBytes = 0L
            val buffer = ByteArray(64 * 1024)
            var lastUpdate = SystemClock.elapsedRealtime()

            r.openOutputStream(uri)?.use { outStream ->
                while (doneBytes < totalBytes || totalBytes <= 0) {
                    val toRead = if (totalBytes > 0) Math.min(buffer.size.toLong(), totalBytes - doneBytes).toInt() else buffer.size
                    if (toRead <= 0) break
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break

                    outStream.write(buffer, 0, read)
                    doneBytes += read

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 350) {
                        val prev = transferSamples[txId]
                        var spd = activeTransfers[txId]?.speed ?: 0.0
                        if (prev != null && now > prev.first) {
                            val inst = (doneBytes - prev.second) * 1000.0 / (now - prev.first)
                            spd = if (spd == 0.0) inst else spd * 0.6 + inst * 0.4
                        }
                        transferSamples[txId] = now to doneBytes
                        lastUpdate = now

                        val cur = Transfer(txId, rawName, totalBytes, doneBytes, false, spd, 0)
                        activeTransfers[txId] = cur
                        onTransferUpdate(cur)
                    }
                }
            }

            // Mark completed in MediaStore
            val doneCv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            r.update(uri, doneCv, null, null)

            // Trigger MediaScanner so Gallery / Photos indexes it immediately
            runCatching {
                MediaScannerConnection.scanFile(app, arrayOf(uri.toString()), arrayOf(mime), null)
            }

            val completed = Transfer(txId, rawName, doneBytes, doneBytes, false, 0.0, 1)
            activeTransfers[txId] = completed
            onTransferUpdate(completed)

            val historyItem = HistoryItem(
                id = "i$txId",
                name = rawName,
                size = doneBytes,
                mime = mime,
                outgoing = false,
                peer = senderName,
                time = System.currentTimeMillis(),
                uri = uri.toString(),
                text = null,
            )
            onTransferFinished(completed, uri.toString(), historyItem)

            val typeLabel = when {
                mime.startsWith("image/") -> "Photo"
                mime.startsWith("video/") -> "Video"
                else -> "File"
            }
            onEvent("Saved $typeLabel: $rawName ✓")

            writeHttpResponse(output, 200, "application/json", """{"success":true}""".toByteArray())
        } catch (e: Exception) {
            val failed = Transfer(txId, rawName, totalBytes, 0, false, 0.0, 2)
            activeTransfers[txId] = failed
            onTransferUpdate(failed)
            onEvent("Failed to save $rawName: ${e.message}")
            writeHttpResponse(output, 500, "application/json", """{"error":"${e.message}"}""".toByteArray())
        } finally {
            scope.launch {
                delay(4000)
                activeTransfers.remove(txId)
                transferSamples.remove(txId)
            }
        }
    }

    private fun handleIncomingText(body: String, output: OutputStream) {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val text = json?.optString("text") ?: ""
        val sender = json?.optString("senderName", "Nearby Device") ?: "Nearby Device"

        if (text.isNotEmpty()) {
            val h = HistoryItem(
                id = "t${System.nanoTime()}",
                name = "Text Note",
                size = text.length.toLong(),
                mime = "text/plain",
                outgoing = false,
                peer = sender,
                time = System.currentTimeMillis(),
                uri = null,
                text = text,
            )
            onTransferFinished(Transfer(0, "Text Note", text.length.toLong(), text.length.toLong(), false, 0.0, 1), null, h)
            onEvent("Note received from $sender ✓")
        }

        writeHttpResponse(output, 200, "application/json", """{"success":true}""".toByteArray())
    }

    private fun writeHttpResponse(out: OutputStream, code: Int, contentType: String, body: ByteArray) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    // ───────────────────────── Sending (Phone to Desktop / Phone) ─────────────────────────

    fun sendUrisToPeer(peer: Peer, uris: List<Uri>) {
        scope.launch(Dispatchers.IO) {
            val r = app.contentResolver
            val fileList = mutableListOf<Triple<Uri, String, Long>>()

            for (uri in uris) {
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
                if (size <= 0L) {
                    runCatching {
                        r.openFileDescriptor(uri, "r")?.use { size = it.statSize }
                    }
                }
                val mime = r.getType(uri) ?: "application/octet-stream"
                if (name.isBlank() || name == "file") {
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                    val prefix = when {
                        mime.startsWith("image/") -> "Photo"
                        mime.startsWith("video/") -> "Video"
                        else -> "File"
                    }
                    name = "${prefix}_${System.currentTimeMillis() % 100000}.$ext"
                }
                fileList.add(Triple(uri, name, size))
            }

            if (fileList.isEmpty()) return@launch

            // 1. Request transfer from target peer
            val txId = "tx_${System.currentTimeMillis()}"
            val reqJson = JSONObject().apply {
                put("transferId", txId)
                put("senderName", store.nickname)
                put("pin", (1000..9999).random().toString())
                val filesArr = JSONArray()
                for ((_, name, size) in fileList) {
                    filesArr.put(JSONObject().apply {
                        put("name", name)
                        put("size", size)
                    })
                }
                put("files", filesArr)
            }.toString()

            val requestHttp = Request.Builder()
                .url("http://${peer.ip}:${peer.port}/api/transfer/request")
                .post(RequestBody.create("application/json".toMediaTypeOrNull(), reqJson))
                .build()

            val accepted = runCatching {
                okHttpClient.newCall(requestHttp).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    JSONObject(respBody).optBoolean("accepted", false)
                }
            }.getOrDefault(false)

            if (!accepted) {
                onEvent("${peer.name} declined the transfer")
                return@launch
            }

            // 2. Stream each file
            for ((uri, name, size) in fileList) {
                streamFileToPeer(peer, txId, uri, name, size)
            }
        }
    }

    private suspend fun streamFileToPeer(peer: Peer, baseTxId: String, uri: Uri, name: String, size: Long) = withContext(Dispatchers.IO) {
        val txId = System.nanoTime()
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        val transfer = Transfer(txId, name, size, 0, true, 0.0, 0)
        activeTransfers[txId] = transfer
        onTransferUpdate(transfer)

        try {
            var sentBytes = 0L
            var lastUpdate = SystemClock.elapsedRealtime()

            val customBody = object : RequestBody() {
                override fun contentType() = mime.toMediaTypeOrNull()
                override fun contentLength() = size

                override fun writeTo(sink: BufferedSink) {
                    val inStream = app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read file")
                    inStream.source().use { source ->
                        val buffer = okio.Buffer()
                        while (true) {
                            val read = source.read(buffer, 64 * 1024)
                            if (read == -1L) break
                            sink.write(buffer, read)
                            sentBytes += read

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 350) {
                                val prev = transferSamples[txId]
                                var spd = activeTransfers[txId]?.speed ?: 0.0
                                if (prev != null && now > prev.first) {
                                    val inst = (sentBytes - prev.second) * 1000.0 / (now - prev.first)
                                    spd = if (spd == 0.0) inst else spd * 0.6 + inst * 0.4
                                }
                                transferSamples[txId] = now to sentBytes
                                lastUpdate = now

                                val cur = Transfer(txId, name, size, sentBytes, true, spd, 0)
                                activeTransfers[txId] = cur
                                onTransferUpdate(cur)
                            }
                        }
                    }
                }
            }

            val encodedName = URLEncoder.encode(name, "UTF-8")
            val encodedSender = URLEncoder.encode(store.nickname, "UTF-8")

            val uploadReq = Request.Builder()
                .url("http://${peer.ip}:${peer.port}/api/transfer/upload")
                .header("X-Transfer-Id", "${baseTxId}_${txId}")
                .header("X-File-Name", encodedName)
                .header("X-File-Size", size.toString())
                .header("X-Sender-Name", encodedSender)
                .post(customBody)
                .build()

            okHttpClient.newCall(uploadReq).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
            }

            val completed = Transfer(txId, name, size, size, true, 0.0, 1)
            activeTransfers[txId] = completed
            onTransferUpdate(completed)

            val h = HistoryItem(
                id = "o$txId",
                name = name,
                size = size,
                mime = mime,
                outgoing = true,
                peer = peer.name,
                time = System.currentTimeMillis(),
                uri = uri.toString(),
                text = null,
            )
            onTransferFinished(completed, uri.toString(), h)
            onEvent("Sent $name ✓")
        } catch (e: Exception) {
            val failed = Transfer(txId, name, size, 0, true, 0.0, 2)
            activeTransfers[txId] = failed
            onTransferUpdate(failed)
            onEvent("Failed sending $name: ${e.message}")
        } finally {
            scope.launch {
                delay(4000)
                activeTransfers.remove(txId)
                transferSamples.remove(txId)
            }
        }
    }

    fun sendTextToPeer(peer: Peer, text: String) {
        scope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("text", text)
                put("senderName", store.nickname)
            }.toString()

            val req = Request.Builder()
                .url("http://${peer.ip}:${peer.port}/api/transfer/text")
                .post(RequestBody.create("application/json".toMediaTypeOrNull(), payload))
                .build()

            val success = runCatching {
                okHttpClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)

            if (success) {
                val h = HistoryItem(
                    id = "t${System.nanoTime()}",
                    name = "Text Note",
                    size = text.length.toLong(),
                    mime = "text/plain",
                    outgoing = true,
                    peer = peer.name,
                    time = System.currentTimeMillis(),
                    uri = null,
                    text = text,
                )
                onTransferFinished(Transfer(0, "Text Note", text.length.toLong(), text.length.toLong(), true, 0.0, 1), null, h)
                onEvent("Note sent ✓")
            } else {
                onEvent("Failed to send note to ${peer.name}")
            }
        }
    }
}
