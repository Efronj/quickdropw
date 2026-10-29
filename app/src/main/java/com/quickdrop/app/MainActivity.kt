package com.quickdrop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (savedInstanceState == null) handleShare(intent)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Receives files / photos / videos / text shared from external apps. */
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val items = ArrayList<Outgoing>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) items.add(Outgoing.Media(uri))
                else if (!text.isNullOrBlank()) items.add(Outgoing.Txt(text))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { items.add(Outgoing.Media(it)) }
            }
        }
        if (items.isNotEmpty()) {
            vm.engine.queueOrSend(items)
            Toast.makeText(this, "${items.size} item(s) queued · Tap a device to send", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun App(vm: MainVM) {
    val ctx = LocalContext.current
    val perms = remember { nearbyPerms() }
    var granted by remember { mutableStateOf(hasAll(ctx, perms)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAll(ctx, perms)
    }
    LaunchedEffect(granted) { if (granted) vm.engine.start() }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = QD.Blue,
            background = QD.BgStart,
            surface = QD.Surface,
            onPrimary = Color.White,
            onBackground = QD.Text1,
            onSurface = QD.Text1,
        )
    ) {
        Surface(color = Color.Transparent, contentColor = QD.Text1) {
            GlassBackground {
                if (granted) MainShell(vm) else PermissionScreen { launcher.launch(perms) }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        GlassCard(elevation = 8.dp) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(QD.BlueSoft)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) {
                Text("📡", fontSize = 36.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Nearby Transfer",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = QD.Text1,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "QuickDrop connects directly to nearby phones using Wi-Fi Direct and Bluetooth. Transfer photos, videos, and large files at maximum speed with zero internet data.",
                color = QD.Text2,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            GlassButton("Enable Nearby Sharing", Modifier.fillMaxWidth(), accent = QD.Blue) { onAllow() }
        }
    }
}

@Composable
private fun MainShell(vm: MainVM) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        vm.engine.events.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (tab) {
                0 -> ShareScreen(vm)
                1 -> HistoryScreen(vm)
                else -> ToolsScreen(vm)
            }
        }
        GlassNav(
            tab,
            { tab = it },
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
        IncomingDialog(vm)
    }
}

@Composable
private fun GlassNav(tab: Int, onTab: (Int) -> Unit, modifier: Modifier) {
    val items = listOf("⚡" to "Share", "🕒" to "History", "🛠️" to "Tools")
    Row(
        modifier
            .fillMaxWidth()
            .glass(32.dp, tint = QD.Surface.copy(alpha = 0.95f), borderColor = QD.Border, elevation = 10.dp)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEachIndexed { i, pair ->
            val sel = i == tab
            Column(
                Modifier
                    .weight(1f)
                    .pressable { onTab(i) }
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (sel) QD.BlueSoft else Color.Transparent)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(pair.first, fontSize = 20.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    pair.second,
                    fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (sel) QD.Blue else QD.Text2,
                )
            }
        }
    }
}
