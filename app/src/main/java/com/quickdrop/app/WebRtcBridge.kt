package com.quickdrop.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class WebRtcBridge(
    private val app: Application,
    private val store: Store,
    private val scope: CoroutineScope,
    private val onPeerConnected: (Peer) -> Unit,
    private val onPeerDisconnected: (String) -> Unit,
    private val onTransferUpdate: (Transfer) -> Unit,
    private val onTransferFinished: (Transfer, String, HistoryItem) -> Unit,
    private val onEvent: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    val shareCode = MutableStateFlow("")
    val isReady = MutableStateFlow(false)

    // Current WebRTC connected peer (if any)
    val connectedPeer = MutableStateFlow<Peer?>(null)

    // Map of active incoming transfers: txId -> IncomingFileState
    private class IncomingFileState(
        val txId: Long,
        val name: String,
        val totalBytes: Long,
        val mime: String,
        val peerName: String,
        val uri: Uri,
        val outStream: OutputStream,
        var receivedBytes: Long = 0L,
        var lastUpdate: Long = SystemClock.elapsedRealtime(),
        var speed: Double = 0.0,
    )

    private val incomingFiles = ConcurrentHashMap<String, IncomingFileState>()

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        mainHandler.post {
            try {
                if (webView == null) {
                    webView = WebView(app).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val code = (100000..999999).random().toString()
                                val nick = store.nickname.replace("'", "\\'")
                                evaluateJavascript("window.initWebRTC('$code', '$nick');", null)
                            }
                        }

                        addJavascriptInterface(JsInterface(), "AndroidBridge")
                        loadUrl("file:///android_asset/webrtc.html")
                    }
                }
            } catch (e: Exception) {
                onEvent("WebRTC startup error: ${e.message}")
            }
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                webView?.evaluateJavascript("window.disconnectCurrent();", null)
                webView?.destroy()
                webView = null
            } catch (e: Exception) {}
        }
        shareCode.value = ""
        isReady.value = false
        connectedPeer.value = null
        incomingFiles.values.forEach { runCatching { it.outStream.close() } }
        incomingFiles.clear()
    }

    fun connectToCode(rawCode: String) {
        val clean = rawCode.filter { it.isDigit() }
        if (clean.length != 6) {
            onEvent("Please enter a valid 6-digit code")
            return
        }
        if (clean == shareCode.value) {
            onEvent("Cannot connect to your own device code!")
            return
        }

        onEvent("Connecting to ${clean.take(3)}-${clean.takeLast(3)}… 🌐")
        mainHandler.post {
            webView?.evaluateJavascript("window.connectToCode('$clean');", null)
        }
    }

    fun disconnect() {
        mainHandler.post {
            webView?.evaluateJavascript("window.disconnectCurrent();", null)
        }
        val peer = connectedPeer.value
        if (peer != null) {
            connectedPeer.value = null
            onPeerDisconnected(peer.id)
        }
    }

    fun sendText(peer: Peer, text: String) {
        val escaped = JSONObject.quote(text)
        mainHandler.post {
            webView?.evaluateJavascript("window.sendTextMsg($escaped);", null)
        }
        val h = HistoryItem(
            id = "wtx_${System.currentTimeMillis()}",
            name = "Text Note",
            size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            mime = "text/plain",
            outgoing = true,
            peer = peer.name,
            time = System.currentTimeMillis(),
            uri = null,
            text = text,
        )
        onTransferFinished(
            Transfer(System.nanoTime(), "Text Note", 0, 0, true, 0.0, 1),
            "",
            h
        )
    }

    fun sendUris(peer: Peer, uris: List<Uri>) {
        scope.launch(Dispatchers.IO) {
            val r = app.contentResolver
            for (uri in uris) {
                var name = "File_${System.currentTimeMillis()}"
                var size = 0L
                val mime = r.getType(uri) ?: "application/octet-stream"

                r.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                    }
                }

                val txId = System.nanoTime()
                val transfer = Transfer(txId, name, size, 0, true, 0.0, 0)
                onTransferUpdate(transfer)

                // Notify receiver to prepare file
                mainHandler.post {
                    val escapedName = JSONObject.quote(name)
                    webView?.evaluateJavascript("window.sendFileStart('$txId', $escapedName, $size, '$mime');", null)
                }

                delay(100)

                var sentBytes = 0L
                val startTime = SystemClock.elapsedRealtime()
                var lastUpdate = startTime
                val buffer = ByteArray(48 * 1024)

                var isSuccess = false
                try {
                    r.openInputStream(uri)?.use { stream ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break

                            val chunkBytes = if (read == buffer.size) buffer else buffer.copyOf(read)
                            val b64 = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                            mainHandler.post {
                                webView?.evaluateJavascript("window.sendFileChunk('$txId', '$b64');", null)
                            }

                            sentBytes += read

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 350) {
                                val elapsedSec = (now - startTime) / 1000.0
                                val spd = if (elapsedSec > 0.1) sentBytes / elapsedSec else 0.0
                                onTransferUpdate(Transfer(txId, name, size, sentBytes, true, spd, 0))
                                lastUpdate = now
                            }

                            // Gentle pacing for WebRTC DataChannel flow
                            delay(12)
                        }
                    }

                    mainHandler.post {
                        val escapedName = JSONObject.quote(name)
                        webView?.evaluateJavascript("window.sendFileDone('$txId', $escapedName);", null)
                    }

                    val doneTransfer = Transfer(txId, name, size, size, true, 0.0, 1)
                    onTransferUpdate(doneTransfer)

                    val h = HistoryItem(
                        id = "out_$txId",
                        name = name,
                        size = size,
                        mime = mime,
                        outgoing = true,
                        peer = peer.name,
                        time = System.currentTimeMillis(),
                        uri = uri.toString(),
                        text = null,
                    )
                    onTransferFinished(doneTransfer, uri.toString(), h)
                    onEvent("Sent $name across the internet ✓")
                    isSuccess = true
                } catch (e: Exception) {
                    onTransferUpdate(Transfer(txId, name, size, sentBytes, true, 0.0, 2))
                    onEvent("Failed to send $name: ${e.message}")
                }
            }
        }
    }

    private inner class JsInterface {

        @JavascriptInterface
        fun onCodeReady(code: String) {
            shareCode.value = code
            isReady.value = true
        }

        @JavascriptInterface
        fun onPeerConnected(peerId: String, name: String, deviceType: String) {
            val peer = Peer(
                id = peerId,
                name = name,
                deviceType = deviceType,
                isLan = false,
                isInternet = true,
            )
            connectedPeer.value = peer
            onPeerConnected(peer)
            onEvent("Connected to $name across the internet! 🌐")
        }

        @JavascriptInterface
        fun onPeerDisconnected(peerId: String) {
            val cur = connectedPeer.value
            if (cur != null && cur.id == peerId) {
                connectedPeer.value = null
                onPeerDisconnected(peerId)
                onEvent("${cur.name} disconnected")
            }
        }

        @JavascriptInterface
        fun onFileStart(txId: String, name: String, size: Long, mime: String, peerName: String) {
            scope.launch(Dispatchers.IO) {
                try {
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

                    val r = app.contentResolver
                    val uri = r.insert(collection, cv) ?: throw IllegalStateException("Cannot create file in MediaStore")
                    val outStream = r.openOutputStream(uri) ?: throw IllegalStateException("Cannot open OutputStream")

                    val numericId = txId.hashCode().toLong()
                    val state = IncomingFileState(
                        txId = numericId,
                        name = name,
                        totalBytes = size,
                        mime = mime,
                        peerName = peerName,
                        uri = uri,
                        outStream = outStream,
                    )
                    incomingFiles[txId] = state

                    onTransferUpdate(Transfer(numericId, name, size, 0, false, 0.0, 0))
                } catch (e: Exception) {
                    onEvent("Could not create incoming file: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onFileChunk(txId: String, base64: String) {
            val state = incomingFiles[txId] ?: return
            try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                state.outStream.write(bytes)
                state.receivedBytes += bytes.size

                val now = SystemClock.elapsedRealtime()
                if (now - state.lastUpdate >= 350) {
                    val inst = (bytes.size * 1000.0) / (now - state.lastUpdate)
                    state.speed = if (state.speed == 0.0) inst else state.speed * 0.6 + inst * 0.4
                    state.lastUpdate = now

                    onTransferUpdate(
                        Transfer(
                            id = state.txId,
                            name = state.name,
                            total = state.totalBytes,
                            done = state.receivedBytes,
                            outgoing = false,
                            speed = state.speed,
                            state = 0,
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore chunk error
            }
        }

        @JavascriptInterface
        fun onFileDone(txId: String) {
            val state = incomingFiles.remove(txId) ?: return
            scope.launch(Dispatchers.IO) {
                try {
                    state.outStream.flush()
                    state.outStream.close()

                    val r = app.contentResolver
                    val doneCv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    r.update(state.uri, doneCv, null, null)

                    runCatching {
                        MediaScannerConnection.scanFile(app, arrayOf(state.uri.toString()), arrayOf(state.mime), null)
                    }

                    val completed = Transfer(state.txId, state.name, state.receivedBytes, state.receivedBytes, false, 0.0, 1)
                    onTransferUpdate(completed)

                    val h = HistoryItem(
                        id = "rx_${state.txId}",
                        name = state.name,
                        size = state.receivedBytes,
                        mime = state.mime,
                        outgoing = false,
                        peer = state.peerName,
                        time = System.currentTimeMillis(),
                        uri = state.uri.toString(),
                        text = null,
                    )
                    onTransferFinished(completed, state.uri.toString(), h)
                    onEvent("Received ${state.name} from ${state.peerName} ✓ 🌐")
                } catch (e: Exception) {
                    onEvent("Error finalizing ${state.name}: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onText(text: String, peerName: String) {
            val h = HistoryItem(
                id = "rx_txt_${System.currentTimeMillis()}",
                name = "Text Note",
                size = text.toByteArray(Charsets.UTF_8).size.toLong(),
                mime = "text/plain",
                outgoing = false,
                peer = peerName,
                time = System.currentTimeMillis(),
                uri = null,
                text = text,
            )
            onTransferFinished(
                Transfer(System.nanoTime(), "Text Note", 0, 0, false, 0.0, 1),
                "",
                h
            )
            onEvent("Note from $peerName: $text")
        }

        @JavascriptInterface
        fun onError(msg: String) {
            // Internal WebRTC debug notice
        }
    }
}
