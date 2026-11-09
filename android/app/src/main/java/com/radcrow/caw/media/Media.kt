package com.radcrow.caw.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.radcrow.caw.CawApp
import com.radcrow.caw.Remote
import com.radcrow.caw.sealed.Crypto
import com.radcrow.caw.sealed.Plain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TooLarge : IOException()

// file is plaintext in the app's private cache until sent or discarded
class Prepared(
    val kind: String,
    val mime: String,
    val file: File,
    val w: Int,
    val h: Int,
    val dur: Long?,
    val thumb: String,
) {
    val size get() = file.length()
}

private const val IMAGE_MAX = 1600
private const val THUMB_MAX = 64
private val BLOB_AAD = "caw-blob-v1".toByteArray()

fun fmtDur(s: Long) = "%d:%02d".format(s / 60, s % 60)

class Media(private val app: CawApp) {
    private val cacheDir = File(app.cacheDir, "media").apply { mkdirs() }
    private val outDir = File(app.cacheDir, "outgoing").apply { mkdirs() }
    private val locks = HashMap<String, Mutex>()

    init {
        outDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun prepare(uri: Uri, limit: Long, onProgress: (Float) -> Unit): Prepared {
        val mime = app.contentResolver.getType(uri).orEmpty()
        val p = if (mime.startsWith("video/")) prepareVideo(uri, mime, limit, onProgress) else withContext(Dispatchers.IO) { prepareImage(uri) }
        if (p.size > limit) {
            p.file.delete()
            throw TooLarge()
        }
        return p
    }

    private fun thumbOf(b: Bitmap): String {
        val s = min(1f, THUMB_MAX.toFloat() / max(b.width, b.height))
        val t = Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
        val out = ByteArrayOutputStream()
        t.compress(Bitmap.CompressFormat.JPEG, 50, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun rotation(uri: Uri): Int = runCatching {
        app.contentResolver.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    }.getOrDefault(0)

    // re-encoding strips EXIF, so location and camera metadata never leave the device
    private fun prepareImage(uri: Uri): Prepared {
        val cr = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("Could not read the image.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_MAX) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("Could not read the image.")
        val s = min(1f, IMAGE_MAX.toFloat() / max(raw.width, raw.height))
        val m = Matrix().apply {
            postScale(s, s)
            postRotate(rotation(uri).toFloat())
        }
        val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val webp = Build.VERSION.SDK_INT >= 30
        val file = File.createTempFile("img", if (webp) ".webp" else ".jpg", outDir)
        file.outputStream().use {
            if (webp) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) else bmp.compress(Bitmap.CompressFormat.JPEG, 82, it)
        }
        return Prepared("image", if (webp) "image/webp" else "image/jpeg", file, bmp.width, bmp.height, null, thumbOf(bmp))
    }

    private class Info(val w: Int, val h: Int, val dur: Long, val frame: Bitmap?)

    private fun info(src: (MediaMetadataRetriever) -> Unit): Info {
        val r = MediaMetadataRetriever()
        try {
            src(r)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val frame = runCatching { r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            val swap = rot == 90 || rot == 270
            return Info(if (swap) h else w, if (swap) w else h, (ms + 500) / 1000, frame)
        } finally {
            r.release()
        }
    }

    private suspend fun prepareVideo(uri: Uri, mime: String, limit: Long, onProgress: (Float) -> Unit): Prepared {
        val src = withContext(Dispatchers.IO) { info { it.setDataSource(app, uri) } }
        val steps = listOf(720 to 1_500_000, 480 to 700_000)
        var out: File? = null
        for ((i, step) in steps.withIndex()) {
            out?.delete()
            out = runCatching { transcode(uri, src, step.first, step.second) { onProgress((i + it) / steps.size) } }
                .onFailure { Log.w(TAG, "transcode", it) }.getOrNull()
            if (out == null || out.length() <= limit) break
        }
        // could not shrink it; the original is sent only if it fits and is mp4
        if (out == null || out.length() > limit) {
            out?.delete()
            if (mime != "video/mp4") throw TooLarge()
            out = File.createTempFile("vid", ".mp4", outDir)
            withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }
        }
        val file = out
        val i = withContext(Dispatchers.IO) { info { it.setDataSource(file.path) } }
        return Prepared("video", "video/mp4", file, i.w, i.h, i.dur, i.frame?.let(::thumbOf).orEmpty())
    }

    private suspend fun transcode(uri: Uri, src: Info, short: Int, bitrate: Int, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.Main) {
            val file = File.createTempFile("vid", ".mp4", outDir)
            // encoders reject odd dimensions
            val scaled = { d: Int -> (d * short / min(src.w, src.h)) / 2 * 2 }
            val effects = if (min(src.w, src.h) > short) {
                Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(scaled(src.w), scaled(src.h), Presentation.LAYOUT_SCALE_TO_FIT)))
            } else {
                Effects.EMPTY
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
            var t: Transformer? = null
            val poll = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (t?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    delay(250)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val tr = Transformer.Builder(app)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(app)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                                .build(),
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if (cont.isActive) cont.resume(file)
                            }

                            override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                                file.delete()
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        })
                        .build()
                    t = tr
                    tr.start(item, file.path)
                    // Transformer must be called on the thread that created it
                    cont.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { tr.cancel() }
                        file.delete()
                    }
                }
            } finally {
                poll.cancel()
            }
        }

    fun encrypt(data: ByteArray): Pair<ByteArray, String> {
        val key = Crypto.random(32)
        val iv = Crypto.random(12)
        return Crypto.concat(iv, Crypto.aesGcm(true, key, iv, BLOB_AAD, data)) to Crypto.b64(key)
    }

    private fun safe(blob: String) = blob.filter { it.isLetterOrDigit() || it == '-' }

    // keyed by where the file is and its key, so a message cannot borrow another sender's file
    private fun cached(blob: String, key: String?, from: Remote?): File {
        val tag = Crypto.sha256("${from?.server.orEmpty()}|${key.orEmpty()}".toByteArray()).copyOf(8).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "${safe(blob)}.$tag")
    }

    // sent files are kept as the local copy so a sent attachment is not downloaded again
    fun keep(blob: String, key: String, p: Prepared, from: Remote?) {
        if (!p.file.renameTo(cached(blob, key, from))) p.file.delete()
    }

    suspend fun file(p: Plain, sentTo: Remote? = null): File {
        val blob = p.blob ?: throw IOException("Attachment unavailable.")
        val f = cached(blob, p.key, sentTo)
        val lock = synchronized(locks) { locks.getOrPut(f.name) { Mutex() } }
        return lock.withLock {
            if (f.exists()) return@withLock f
            val body = if (sentTo != null) app.api.linkDownload(sentTo, blob) else app.api.downloadBlob(blob)
            val pt = withContext(Dispatchers.Default) {
                Crypto.aesGcm(false, Crypto.unb64(p.key!!), body.copyOfRange(0, 12), BLOB_AAD, body.copyOfRange(12, body.size))
            }
            withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "${f.name}.part")
                tmp.writeBytes(pt)
                tmp.renameTo(f)
            }
            f
        }
    }

    fun drop(blob: String) {
        val name = safe(blob)
        cacheDir.listFiles()?.forEach { if (it.name.substringBefore('.') == name) it.delete() }
    }

    fun keepOnly(blobs: Set<String>) {
        val names = blobs.map(::safe).toSet()
        cacheDir.listFiles()?.forEach { if (it.name.substringBefore('.') !in names) it.delete() }
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
        outDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "caw.media"
    }
}
