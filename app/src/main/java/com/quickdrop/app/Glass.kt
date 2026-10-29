package com.quickdrop.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

object QD {
    val BgStart = Color(0xFFF8FAFC)
    val BgEnd = Color(0xFFF1F5F9)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF8FAFC)
    val Border = Color(0xFFE2E8F0)
    val BorderStrong = Color(0xFFCBD5E1)

    val Blue = Color(0xFF2563EB)
    val BlueSoft = Color(0xFFEFF6FF)
    val BlueGlow = Color(0x332563EB)

    val Violet = Color(0xFF7C3AED)
    val VioletSoft = Color(0xFFF5F3FF)

    val Mint = Color(0xFF10B981)
    val MintSoft = Color(0xFFECFDF5)

    val Pink = Color(0xFFEC4899)
    val PinkSoft = Color(0xFFFDF2F8)

    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFFFFFBEB)

    val Text1 = Color(0xFF0F172A)
    val Text2 = Color(0xFF475569)
    val Text3 = Color(0xFF94A3B8)
}

// ───────────────────────── White Premium Background ─────────────────────────

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse),
        label = "a",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(QD.BgStart, QD.BgEnd, Color(0xFFEAF0F8))))
    ) {
        // Soft airy ambient color glows
        AmbientBlob(QD.Blue.copy(alpha = 0.08f), 340.dp, (-80 + 90 * a).dp, 40.dp)
        AmbientBlob(QD.Violet.copy(alpha = 0.07f), 300.dp, (150 - 80 * a).dp, 260.dp)
        AmbientBlob(QD.Mint.copy(alpha = 0.07f), 280.dp, (-40 + 60 * a).dp, 560.dp)
        AmbientBlob(QD.Pink.copy(alpha = 0.06f), 240.dp, (180 - 70 * a).dp, 720.dp)
        content()
    }
}

@Composable
private fun AmbientBlob(c: Color, size: Dp, x: Dp, y: Dp) {
    Box(
        Modifier
            .offset(x, y)
            .size(size)
            .background(Brush.radialGradient(listOf(c, Color.Transparent)), CircleShape)
    )
}

// ───────────────────────── Glass Surfaces & Controls ─────────────────────────

fun Modifier.glass(
    radius: Dp = 24.dp,
    tint: Color = QD.Surface,
    borderColor: Color = QD.Border,
    elevation: Dp = 2.dp,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, spotColor = Color(0x1A0F172A), ambientColor = Color(0x0D0F172A)) else Modifier)
        .clip(shape)
        .background(tint, shape)
        .border(1.dp, borderColor, shape)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = QD.Surface,
    borderColor: Color = QD.Border,
    elevation: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(24.dp, tint = tint, borderColor = borderColor, elevation = elevation)
            .padding(18.dp),
        content = content,
    )
}

fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val s by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .scale(s)
        .clickable(interactionSource = src, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val isAccent = accent != null
    val base = modifier
        .height(46.dp)
        .pressable(onClick)

    val styled = if (isAccent) {
        base
            .shadow(4.dp, shape, spotColor = accent.copy(alpha = 0.35f))
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.88f))), shape)
    } else {
        base
            .glass(22.dp, tint = QD.Surface, borderColor = QD.Border, elevation = 1.dp)
    }

    Box(styled.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (isAccent) Color.White else QD.Text1,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun GlassProgress(f: Float, modifier: Modifier = Modifier) {
    val anim by animateFloatAsState(f, tween(250), label = "pg")
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(Color(0xFFE2E8F0))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(anim.coerceIn(0.04f, 1f))
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(QD.Blue, QD.Violet, QD.Mint)))
        )
    }
}

@Composable
fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .glass(18.dp, tint = QD.Surface, borderColor = QD.Border, elevation = 1.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (value.isEmpty()) {
            Text(hint, color = QD.Text3, fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = QD.Text1, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(QD.Blue),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ───────────────────────── The Send Orb (White Theme) ─────────────────────────

@Composable
fun SendOrb(onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.28f
            // Subtle blue glow ring
            drawCircle(
                Brush.radialGradient(
                    listOf(QD.Blue.copy(alpha = 0.15f), Color.Transparent),
                    center = center,
                    radius = base * 2.1f,
                ),
                radius = base * 2.1f,
                center = center,
            )
            for (i in 0..1) {
                val p = (pulse + i * 0.5f) % 1f
                drawCircle(
                    QD.Blue.copy(alpha = (1f - p) * 0.35f),
                    radius = base + p * base * 0.95f,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(136.dp)
                .pressable(onClick)
                .shadow(16.dp, CircleShape, spotColor = QD.Blue.copy(alpha = 0.4f))
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF3B82F6), QD.Blue, Color(0xFF1D4ED8))))
                .border(2.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📤", fontSize = 34.sp)
                Spacer(Modifier.height(4.dp))
                Text("Share", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ───────────────────────── Radar (Luminous White Aesthetic) ─────────────────────────

@Composable
fun Radar(peers: List<Peer>, onPick: (Peer) -> Unit) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
        label = "p",
    )
    Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2f - 12.dp.toPx()
            // Ambient concentric circles
            for (i in 0..2) {
                val q = (p + i / 3f) % 1f
                drawCircle(QD.Blue.copy(alpha = (1f - q) * 0.32f), radius = maxR * q, style = Stroke(2.dp.toPx()))
            }
            drawCircle(Color(0xFFE2E8F0), radius = maxR * 0.72f, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0xFFF1F5F9), radius = maxR * 0.4f, style = Stroke(1.dp.toPx()))
        }

        // Center "You" Indicator
        Box(
            Modifier
                .size(76.dp)
                .glass(38.dp, tint = QD.Surface, borderColor = QD.Blue.copy(alpha = 0.5f), elevation = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(QD.Mint))
                Spacer(Modifier.height(4.dp))
                Text("You", color = QD.Text1, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        val n = peers.size
        peers.forEachIndexed { i, peer ->
            val ang = 2.0 * Math.PI * i / n - Math.PI / 2
            val r = 108.0
            PeerBubble(peer, Modifier.offset(x = (r * cos(ang)).dp, y = (r * sin(ang)).dp), onPick)
        }
    }
}

@Composable
private fun PeerBubble(peer: Peer, modifier: Modifier, onPick: (Peer) -> Unit) {
    val isDesktop = peer.deviceType == "desktop"
    val icon = if (isDesktop) "💻" else "📱"

    Column(modifier.pressable { onPick(peer) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(68.dp)
                .glass(34.dp, tint = if (isDesktop) QD.BlueSoft else QD.Surface, borderColor = if (isDesktop) QD.Blue else QD.BorderStrong, elevation = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, fontSize = 24.sp)
                Text(
                    peer.name.take(1).uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = QD.Blue,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            peer.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = QD.Text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(90.dp),
        )
        Text(
            if (isDesktop) "PC · Tap to send" else "Phone · Tap to send",
            fontSize = 10.sp,
            color = QD.Blue,
            fontWeight = FontWeight.Medium,
        )
    }
}
