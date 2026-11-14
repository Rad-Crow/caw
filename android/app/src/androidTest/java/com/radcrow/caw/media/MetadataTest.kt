package com.radcrow.caw.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radcrow.caw.CawApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// fixtures from testdata/media; the web remux test uses the same video
@RunWith(AndroidJUnit4::class)
class MetadataTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext.applicationContext as CawApp
    private val made = mutableListOf<File>()

    @After
    fun cleanup() = made.forEach { it.delete() }

    // a content uri gives prepare() the mime type, as a picked file does
    private fun fixture(name: String): File =
        File(app.cacheDir, "share/$name").also { f ->
            f.parentFile!!.mkdirs()
            inst.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
            made += f
        }

    private fun uri(f: File) = FileProvider.getUriForFile(app, "${app.packageName}.share", f)

    private fun text(f: File) = String(f.readBytes(), Charsets.ISO_8859_1)

    private fun meta(f: File, key: Int): String? = MediaMetadataRetriever().run {
        try {
            setDataSource(f.path)
            extractMetadata(key)
        } finally {
            release()
        }
    }

    private fun assertNoVideoMetadata(src: File, out: File) {
        made += out
        assertNull(meta(out, MediaMetadataRetriever.METADATA_KEY_LOCATION))
        val t = text(out)
        assertFalse("location", t.contains(LOCATION) || t.contains("©xyz"))
        assertFalse("source tags", t.contains("Lavf"))
        assertFalse("device details", t.contains("com.android"))
        assertNotEquals(meta(src, MediaMetadataRetriever.METADATA_KEY_DATE), meta(out, MediaMetadataRetriever.METADATA_KEY_DATE))
    }

    @Test
    fun photo(): Unit = runBlocking {
        val src = fixture("location.jpg")
        assertTrue(ExifInterface(src.path).getLatLong(FloatArray(2)))
        val p = app.media.prepare(uri(src), Long.MAX_VALUE) {}
        made += p.file
        val t = text(p.file)
        assertFalse(ExifInterface(p.file.path).getLatLong(FloatArray(2)))
        assertFalse("exif", t.contains("Exif") || t.contains("EXIF"))
        assertFalse("make", t.contains("CawTest"))
    }

    // rotated.jpg is stored 96x48 with a red block top left; its orientation tag is rewritten to each value
    @Test
    fun photoOrientation(): Unit = runBlocking {
        val src = fixture("rotated.jpg")
        val bytes = src.readBytes()
        val at = text(src).indexOf("\u0012\u0001\u0003\u0000") + 8
        assertEquals(6, bytes[at].toInt())
        val wrong = (1..8).mapNotNull { o ->
            bytes[at] = o.toByte()
            src.writeBytes(bytes)
            val p = app.media.prepare(uri(src), Long.MAX_VALUE) {}
            made += p.file
            val b = BitmapFactory.decodeFile(p.file.path)
            val want = (if (o < 5) "96x48 " else "48x96 ") + CORNERS[(o - 1) % 4]
            val got = "${b.width}x${b.height} ${redCorners(b).joinToString("+")}"
            if (got == want && p.w == b.width && p.h == b.height) null else "$o: $got, want $want"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    private fun redCorners(b: Bitmap): List<String> {
        val m = 6
        val at = listOf(m to m, b.width - 1 - m to m, b.width - 1 - m to b.height - 1 - m, m to b.height - 1 - m)
        return CORNERS.filterIndexed { i, _ ->
            val c = b.getPixel(at[i].first, at[i].second)
            Color.red(c) > 200 && Color.green(c) < 90 && Color.blue(c) < 90
        }
    }

    @Test
    fun transcodedVideo(): Unit = runBlocking {
        val src = fixture("location.mp4")
        assertNotNull(meta(src, MediaMetadataRetriever.METADATA_KEY_LOCATION))
        val p = app.media.prepare(uri(src), Long.MAX_VALUE) {}
        assertNoVideoMetadata(src, p.file)
        assertTrue("portrait", p.h > p.w)
    }

    @Test
    fun remuxedVideo(): Unit = runBlocking {
        val src = fixture("location.mp4")
        val out = app.media.remux(uri(src))
        assertNoVideoMetadata(src, out)
        val rotation = MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        assertEquals(meta(src, rotation), meta(out, rotation))
    }

    private companion object {
        const val LOCATION = "+40.6892-074.0445/"
        val CORNERS = listOf("top left", "top right", "bottom right", "bottom left")
    }
}
