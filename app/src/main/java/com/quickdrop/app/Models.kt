package com.quickdrop.app

import android.net.Uri

data class Peer(
    val id: String,
    val name: String,
    val deviceType: String = "mobile", // "mobile" or "desktop"
    val ip: String = "",
    val port: Int = 52223,
    val isLan: Boolean = true,
    val isInternet: Boolean = false,
)

data class Incoming(
    val id: String,
    val name: String,
    val digits: String,
    val isLan: Boolean = false,
)

/** state: 0 = active, 1 = done, 2 = failed, 3 = canceled */
data class Transfer(
    val id: Long,
    val name: String,
    val total: Long,
    val done: Long,
    val outgoing: Boolean,
    val speed: Double,
    val state: Int,
) {
    val active: Boolean get() = state == 0
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class HistoryItem(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val outgoing: Boolean,
    val peer: String,
    val time: Long,
    val uri: String?,
    val text: String?,
)

data class BigFile(val uri: Uri, val name: String, val size: Long, val mime: String)

data class Meta(val name: String, val mime: String, val size: Long)

sealed interface Outgoing {
    data class Media(val uri: Uri) : Outgoing
    data class Txt(val text: String) : Outgoing
}
