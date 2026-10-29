package com.quickdrop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun copyText(ctx: Context, s: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("QuickDrop", s))
    toast(ctx, "Copied to clipboard")
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        color = QD.Text1,
        modifier = modifier,
    )
}

// ═════════════════════════════ SHARE SCREEN ═════════════════════════════

@Composable
fun ShareScreen(vm: MainVM) {
    val e = vm.engine
    val ctx = LocalContext.current
    val peers by e.peers.collectAsStateWithLifecycle()
    val connected by e.connected.collectAsStateWithLifecycle()
    val transfers by e.transfers.collectAsStateWithLifecycle()
    val status by e.status.collectAsStateWithLifecycle()
    val pending by e.pending.collectAsStateWithLifecycle()

    var sheet by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf(false) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            e.sendUris(uris)
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            e.sendUris(uris)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 120.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Title("QuickDrop")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (connected != null) QD.Mint else QD.Blue)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(status, fontSize = 13.sp, color = QD.Text2, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Quick Share Action Dock (Photos, Videos, Files, Notes)
        QuickShareDock(
            onPickMedia = {
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onPickFiles = {
                filePicker.launch(arrayOf("*/*"))
            },
            onPasteClipboard = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (txt.isNullOrBlank()) toast(ctx, "Clipboard is empty") else e.sendText(txt)
            },
            onWriteNote = {
                textDialog = true
            },
        )

        Spacer(Modifier.height(18.dp))

        // Selected / Pending Media Tray
        if (pending.isNotEmpty()) {
            GlassCard(
                tint = QD.BlueSoft,
                borderColor = QD.Blue.copy(alpha = 0.4f),
                elevation = 4.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(QD.Blue),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📷", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${pending.size} item(s) selected",
                            color = QD.Text1,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            if (connected == null) "Tap a device on the radar below to send" else "Sending now…",
                            color = QD.Text2,
                            fontSize = 12.sp,
                        )
                    }
                    GlassButton("Clear") { e.clearPending() }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        // Radar / Connected State
        val c = connected
        if (c == null) {
            Radar(peers) { e.connect(it) }
            Text(
                if (peers.isEmpty()) "Open QuickDrop on your PC or another phone to discover it."
                else "Tap any device on the radar to transfer",
                color = QD.Text2,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GlassCard(
                tint = QD.MintSoft,
                borderColor = QD.Mint.copy(alpha = 0.5f),
                elevation = 4.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(QD.Mint)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Connected", fontSize = 11.sp, color = QD.Mint, fontWeight = FontWeight.Bold)
                        Text(
                            c.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = QD.Text1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GlassButton("Disconnect") { e.disconnect() }
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SendOrb { sheet = true }
            }

            Text(
                "Tap Share to send more photos, videos or documents",
                color = QD.Text2,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Active Transfers
        if (transfers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Transfers",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = QD.Text1,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            for (t in transfers.values) {
                TransferRow(t) { e.cancel(t.id) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (sheet) {
        SendSheet(
            onDismiss = { sheet = false },
            onMedia = {
                sheet = false
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onFiles = {
                sheet = false
                filePicker.launch(arrayOf("*/*"))
            },
            onClipboard = {
                sheet = false
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (txt.isNullOrBlank()) toast(ctx, "Clipboard is empty") else e.sendText(txt)
            },
            onText = {
                sheet = false
                textDialog = true
            },
        )
    }

    if (textDialog) {
        TextDialog(onDismiss = { textDialog = false }) { e.sendText(it) }
    }
}

@Composable
private fun QuickShareDock(
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit,
    onPasteClipboard: () -> Unit,
    onWriteNote: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickTile("🖼️", "Photos\n& Videos", QD.BlueSoft, QD.Blue, Modifier.weight(1f), onPickMedia)
        QuickTile("📁", "Files\n& Docs", QD.VioletSoft, QD.Violet, Modifier.weight(1f), onPickFiles)
        QuickTile("📋", "Paste\nClip", QD.AmberSoft, QD.Amber, Modifier.weight(1f), onPasteClipboard)
        QuickTile("✍️", "Text\nNote", QD.MintSoft, QD.Mint, Modifier.weight(1f), onWriteNote)
    }
}

@Composable
private fun QuickTile(
    icon: String,
    title: String,
    bg: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .pressable(onClick)
            .glass(20.dp, tint = QD.Surface, borderColor = QD.Border, elevation = 2.dp)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 20.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = QD.Text1,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun TransferRow(t: Transfer, onCancel: () -> Unit) {
    GlassCard(elevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (t.outgoing) QD.BlueSoft else QD.MintSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (t.outgoing) "⬆️" else "⬇️", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.name,
                    color = QD.Text1,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (t.state) {
                        1 -> "Completed · ${fmtSize(t.total)}"
                        2 -> "Failed"
                        3 -> "Canceled"
                        else -> "${fmtSize(t.done)} of ${fmtSize(t.total)} · ${fmtSpeed(t.speed)} ${fmtEta(t.total, t.done, t.speed)}"
                    },
                    fontSize = 12.sp,
                    color = QD.Text2,
                )
            }
            if (t.active) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(QD.SurfaceMuted)
                        .pressable(onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 14.sp, color = QD.Text2, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (t.active) {
            Spacer(Modifier.height(10.dp))
            GlassProgress(t.fraction)
        }
    }
}

@Composable
private fun SendSheet(
    onDismiss: () -> Unit,
    onMedia: () -> Unit,
    onFiles: () -> Unit,
    onClipboard: () -> Unit,
    onText: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(28.dp, tint = QD.Surface, borderColor = QD.Border, elevation = 16.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Share Content", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = QD.Text1)
            SheetRow("📷", "Photos & Videos", onMedia)
            SheetRow("📁", "Files & Documents", onFiles)
            SheetRow("📋", "Paste from Clipboard", onClipboard)
            SheetRow("✍️", "Send Text Note", onText)
        }
    }
}

@Composable
private fun SheetRow(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .glass(18.dp, tint = QD.SurfaceMuted, borderColor = QD.Border, elevation = 0.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Text(label, color = QD.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(28.dp, tint = QD.Surface, borderColor = QD.Border, elevation = 16.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Send Note", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = QD.Text1)
            GlassField(
                text,
                { text = it },
                "Type a note, link or message…",
                Modifier.height(130.dp),
                singleLine = false,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Cancel", Modifier.weight(1f)) { onDismiss() }
                GlassButton("Send", Modifier.weight(1f), accent = QD.Blue) {
                    onSend(text)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
fun IncomingDialog(vm: MainVM) {
    val e = vm.engine
    val inc by e.incoming.collectAsStateWithLifecycle()
    val i = inc ?: return
    Dialog(onDismissRequest = { e.declineIncoming() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(28.dp, tint = QD.Surface, borderColor = QD.Border, elevation = 16.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(QD.BlueSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text("📲", fontSize = 30.sp)
            }
            Text(
                "${i.name} wants to connect",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = QD.Text1,
                textAlign = TextAlign.Center,
            )
            Text(
                "Confirm that this code matches on both phones:",
                fontSize = 13.sp,
                color = QD.Text2,
                textAlign = TextAlign.Center,
            )
            Text(
                i.digits,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = QD.Blue,
                letterSpacing = 6.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Decline", Modifier.weight(1f)) { e.declineIncoming() }
                GlassButton("Accept", Modifier.weight(1f), accent = QD.Blue) { e.acceptIncoming(false) }
            }
            GlassButton("Accept & Always Trust", Modifier.fillMaxWidth(), accent = QD.Mint) {
                e.acceptIncoming(true)
            }
        }
    }
}

// ═════════════════════════════ HISTORY SCREEN ═════════════════════════════

private fun openItem(ctx: Context, h: HistoryItem) {
    if (h.text != null) {
        copyText(ctx, h.text)
        return
    }
    val u = h.uri ?: return
    try {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(u), h.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: Exception) {
        toast(ctx, "Can't open this file")
    }
}

@Composable
fun HistoryScreen(vm: MainVM) {
    val ctx = LocalContext.current
    val list by vm.engine.history.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("History", Modifier.weight(1f))
            if (list.isNotEmpty()) GlassButton("Clear All") { vm.engine.clearHistory() }
        }
        Spacer(Modifier.height(14.dp))

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🕒", fontSize = 48.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("No Transfers Yet", fontWeight = FontWeight.Bold, color = QD.Text1, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Photos, videos and files you share will appear here.",
                        color = QD.Text2,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list) { h -> HistoryRow(h) { openItem(ctx, h) } }
            }
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryItem, onClick: () -> Unit) {
    val isText = h.text != null
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .glass(20.dp, tint = QD.Surface, borderColor = QD.Border, elevation = 2.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (h.outgoing) QD.BlueSoft else QD.MintSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(iconFor(h.mime, isText), fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (isText) h.text.orEmpty() else h.name,
                color = QD.Text1,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = if (isText) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            val ago = DateUtils.getRelativeTimeSpanString(h.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            Text(
                "${if (h.outgoing) "Sent to" else "Received from"} ${h.peer} · ${if (isText) "tap to copy" else fmtSize(h.size)} · $ago",
                fontSize = 12.sp,
                color = QD.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(if (h.outgoing) "⬆️" else "⬇️", fontSize = 14.sp)
    }
}

// ═════════════════════════════ TOOLS SCREEN ═════════════════════════════

@Composable
fun ToolsScreen(vm: MainVM) {
    val e = vm.engine
    val connected by e.connected.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Title("Tools")
        DeviceCard(vm)
        StorageCard()
        BigFilesCard(e, connected != null)
        QrCard()
        TorchCard()
        BatteryCard()
    }
}

@Composable
private fun CardTitle(icon: String, title: String, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = QD.Text1)
            if (sub != null) Text(sub, fontSize = 12.sp, color = QD.Text2)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DeviceCard(vm: MainVM) {
    val e = vm.engine
    val trusted by e.trusted.collectAsStateWithLifecycle()
    var nick by remember { mutableStateOf(vm.nickname) }
    GlassCard {
        CardTitle("📱", "This Device", "How other phones identify you")
        GlassField(nick, { nick = it }, "Device name")
        Spacer(Modifier.height(10.dp))
        GlassButton("Save Device Name", accent = QD.Blue) { vm.saveNick(nick) }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Visible to Nearby Devices", color = QD.Text1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Turn off to stay hidden from others", fontSize = 12.sp, color = QD.Text2)
            }
            Switch(
                checked = vm.visible,
                onCheckedChange = { vm.updateVisible(it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = QD.Blue,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = QD.Border,
                    uncheckedThumbColor = Color.White,
                ),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("Trusted Devices (Auto-Accept)", color = QD.Text1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (trusted.isEmpty()) {
            Text("No trusted devices yet · Select “Accept & Always Trust” during connection.", fontSize = 12.sp, color = QD.Text2)
        } else {
            for (name in trusted) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✓ $name", color = QD.Text1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        "Remove",
                        color = QD.Pink,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.pressable { e.untrust(name) }.padding(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageCard() {
    val st = remember { StatFs(Environment.getDataDirectory().path) }
    val total = st.totalBytes
    val free = st.availableBytes
    val used = total - free
    val frac = if (total > 0) used.toFloat() / total else 0f
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(80.dp)) {
                    val sw = 8.dp.toPx()
                    val tl = Offset(sw / 2, sw / 2)
                    val sz = Size(size.width - sw, size.height - sw)
                    drawArc(Color(0xFFE2E8F0), -90f, 360f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(QD.Blue, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Text("${(frac * 100).toInt()}%", color = QD.Text1, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Device Storage", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QD.Text1)
                Text("${fmtSize(used)} used of ${fmtSize(total)}", color = QD.Text2, fontSize = 13.sp)
                Text("${fmtSize(free)} free space", color = QD.Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BigFilesCard(e: TransferEngine, canSend: Boolean) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<BigFile>?>(null) }
    val perms = remember { mediaReadPerms() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        files = scanBigFiles(ctx)
    }
    GlassCard {
        CardTitle("🔍", "Large Media Finder", "Quickly scan for large photos & videos to share or free space")
        GlassButton("Scan Photos & Videos", accent = QD.Violet) {
            if (hasAll(ctx, perms)) files = scanBigFiles(ctx) else launcher.launch(perms)
        }
        val list = files
        if (list != null) {
            Spacer(Modifier.height(12.dp))
            if (list.isEmpty()) {
                Text("No photos or videos found.", color = QD.Text2, fontSize = 13.sp)
            }
            for (f in list) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(iconFor(f.mime, false), fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier.weight(1f).pressable {
                            openItem(ctx, HistoryItem("", f.name, f.size, f.mime, true, "", 0L, f.uri.toString(), null))
                        }
                    ) {
                        Text(f.name, color = QD.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(fmtSize(f.size), color = QD.Text2, fontSize = 11.sp)
                    }
                    GlassButton("Send") {
                        if (canSend) e.sendUris(listOf(f.uri)) else toast(ctx, "Connect to a nearby device first")
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCard() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    GlassCard {
        CardTitle("🔳", "QR Code Generator", "Convert a link, Wi-Fi password or text into a QR code")
        GlassField(text, { text = it }, "Enter URL or text")
        Spacer(Modifier.height(10.dp))
        GlassButton("Create QR Code", accent = QD.Pink) {
            if (text.isBlank()) return@GlassButton
            runCatching { makeQr(text) }
                .onSuccess { bmp = it }
                .onFailure { toast(ctx, "Text is too long for QR code") }
        }
        val b = bmp
        if (b != null) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = b,
                    contentDescription = "QR Code",
                    modifier = Modifier
                        .size(200.dp)
                        .glass(18.dp, tint = Color.White, borderColor = QD.Border, elevation = 4.dp)
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun TorchCard() {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val id = remember {
        runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }
    var on by remember { mutableStateOf(false) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🔦 Flashlight", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QD.Text1)
                Text(if (id == null) "Flash unavailable" else "One-tap torch toggle", fontSize = 12.sp, color = QD.Text2)
            }
            Switch(
                checked = on,
                enabled = id != null,
                onCheckedChange = { v ->
                    if (id != null) {
                        try {
                            cm.setTorchMode(id, v)
                            on = v
                        } catch (ex: Exception) {
                            toast(ctx, "Cannot toggle flashlight")
                        }
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = QD.Blue,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = QD.Border,
                    uncheckedThumbColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun BatteryCard() {
    val ctx = LocalContext.current
    val info = remember {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        "${if (pct >= 0) "$pct%" else "?"} · ${if (charging) "Charging" else "Discharging"} · ${"%.1f".format(temp)}°C"
    }
    GlassCard {
        Text("🔋 Battery Status", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QD.Text1)
        Spacer(Modifier.height(4.dp))
        Text(info, fontSize = 13.sp, color = QD.Text2)
    }
}
