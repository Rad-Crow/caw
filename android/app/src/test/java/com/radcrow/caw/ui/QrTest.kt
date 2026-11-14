package com.radcrow.caw.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.radcrow.caw.Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrTest {
    private val code = Code(2, "https://caw.example.com", "t".repeat(43), "10", "Ava Example", "k".repeat(43)).encode()

    // a grey 1280x720 luminance frame with the code drawn at (x, y), turned a quarter if asked
    private fun frame(stride: Int, x: Int, y: Int, size: Int, turned: Boolean = false): ByteArray {
        val m = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val f = ByteArray(stride * 720) { 0x60.toByte() }
        for (r in 0 until size) {
            for (c in 0 until size) {
                val dark = if (turned) m[r, size - 1 - c] else m[c, r]
                f[(y + r) * stride + x + c] = (if (dark) 0x10 else 0xF0).toByte()
            }
        }
        return f
    }

    @Test
    fun readsCode() {
        assertEquals(code, readQr(frame(1344, 340, 60, 600), 1344, 1280, 720))
    }

    @Test
    fun readsTurnedCode() {
        assertEquals(code, readQr(frame(1280, 400, 110, 500, turned = true), 1280, 1280, 720))
    }

    @Test
    fun ignoresEmptyFrame() {
        assertNull(readQr(ByteArray(1280 * 720) { 0x60.toByte() }, 1280, 1280, 720))
    }

    @Test
    fun findsCodeInMessage() {
        assertEquals(code, Code.find("Add me on Caw: scan the QR code with Caw, or paste this invite into Caw.\n\n$code\n"))
    }

    @Test
    fun findsNoCodeInOtherText() {
        assertNull(Code.find("https://caw.example.com/"))
    }
}
