package com.radcrow.caw.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.radcrow.caw.Attachment
import com.radcrow.caw.Remote
import com.radcrow.caw.app
import com.radcrow.caw.media.fmtDur
import com.radcrow.caw.sealed.Plain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private fun decodeThumb(b64: String?): Bitmap? = runCatching {
    val b = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(b, 0, b.size)
}.getOrNull()

private fun decodeFile(f: File, maxPx: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var sample = 1
    while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

fun fmtSize(n: Long) = if (n < 1024 * 1024) "${max(1, n / 1024)} KB" else "%.1f MB".format(n / 1024f / 1024f)

@Composable
fun MediaBubble(p: Plain, sentTo: Remote? = null) {
    val app = LocalContext.current.app
    val w = p.w ?: 1
    val h = p.h ?: 1
    val thumb = remember(p.blob) { decodeThumb(p.thumb) }
    var image by remember(p.blob) { mutableStateOf<Bitmap?>(null) }
    var video by remember(p.blob) { mutableStateOf<File?>(null) }
    var loading by remember(p.blob) { mutableStateOf(false) }
    var failed by remember(p.blob) { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }

    suspend fun fetch(): File? = runCatching { app.media.file(p, sentTo) }.onFailure { failed = true }.getOrNull()

    LaunchedEffect(p.blob) {
        if (p.kind == "image") image = fetch()?.let { withContext(Dispatchers.IO) { decodeFile(it, 800) } }
    }
    LaunchedEffect(loading) {
        if (loading) {
            video = fetch()
            loading = false
            full = video != null
        }
    }

    val s = min(1f, min(260f / w, 320f / h))
    Box(
        Modifier
            .width((w * s).dp)
            .aspectRatio(w.toFloat() / h)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !failed) {
                when {
                    p.kind == "image" && image != null -> full = true
                    p.kind == "video" && video != null -> full = true
                    p.kind == "video" -> loading = true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shown = image ?: thumb
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Attachment unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (shown != null) {
            Image(
                shown.asImageBitmap(),
                null,
                Modifier.fillMaxSize().then(if (image == null) Modifier.blur(8.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }
        if (p.kind == "video" && !failed) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(40.dp), color = Color.White)
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    "Play video",
                    Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(8.dp),
                    tint = Color.White,
                )
            }
            p.dur?.let {
                Text(
                    fmtDur(it),
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    if (full) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                val v = video
                if (p.kind == "video" && v != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setVideoPath(v.path)
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onRelease = { it.stopPlayback() },
                    )
                } else {
                    var big by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(Unit) { big = fetch()?.let { withContext(Dispatchers.IO) { decodeFile(it, 2048) } } }
                    (big ?: image)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                IconButton(onClick = { full = false }, Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun AttachmentBar(a: Attachment, onRemove: () -> Unit) {
    val p = a.prepared
    val thumb = remember(p) { decodeThumb(p?.thumb) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else CircularProgressIndicator(Modifier.size(24.dp))
        }
        Text(
            when {
                a.status == "preparing" -> "Compressing" + if (a.progress > 0) " ${(a.progress * 100).toInt()}%" else "..."
                a.status == "sending" -> "Sending ${(a.progress * 100).toInt()}%"
                p != null -> "${if (p.kind == "video") "Video" else "Photo"} \u00b7 ${fmtSize(p.size)}"
                else -> ""
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (a.status != "sending") IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove attachment") }
    }
}
