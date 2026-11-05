package com.radcrow.caw.sealed

import com.radcrow.caw.sealed.Crypto.b64
import com.radcrow.caw.sealed.Crypto.unb64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CryptoTest {
    private val aad = "message-id".toByteArray()

    // vector from server/src/webpush.rs hpke_vector (rust hpke crate)
    @Test
    fun opensRustVector() {
        val pt = Crypto.hpkeOpen(
            unb64("t95UAESODeP3F2-yxoye38Lon_Ijq82us7_Zd28Bbs0"),
            unb64("tarVPutDGeHZEOwEQPhJ0Z4KOqn-O8uRNCvYDkiDV1U"),
            unb64("yh9lYMmDB0ihEzkBytN2OU7SEf1Nl9yyccDaIig3iRXLixXnFr8dsoDan4bHYixUDCg8j9SxBYKu-LMcLN_J2Q"),
            Crypto.INFO,
            aad,
        )
        assertEquals("BwcHBwcHBwcHBwcHBwcHBw", b64(pt))
    }

    @Test
    fun sealRoundTrip() {
        val (sk, pk) = Crypto.newX25519()
        val msg = "hello".toByteArray()
        assertArrayEquals(msg, Crypto.hpkeOpen(sk, pk, Crypto.hpkeSeal(pk, Crypto.INFO, aad, msg), Crypto.INFO, aad))
    }

    @Test
    fun signVerify() {
        val (sk, pk) = Crypto.newEd25519()
        val sig = Crypto.sign(sk, "hello".toByteArray())
        assertTrue(Crypto.verify(pk, sig, "hello".toByteArray()))
        assertFalse(Crypto.verify(pk, sig, "hellO".toByteArray()))
    }

    // same link id as the web test
    @Test
    fun pairingWords() {
        val list = File("../../protocol/words.txt").readLines().filter { it.isNotBlank() }
        val w = Crypto.pairingWords("6f1c2a4e-0b7d-4e8a-9c3f-2d5e8a1b7c90", list)
        assertEquals("koala blanket plum ankle ribbon bunny", w.joinToString(" "))
    }

    @Test
    fun signatureInput() {
        val keys = listOf(Crypto.Stanza("dev-b", "kb", "wb", "to"), Crypto.Stanza("dev-a", "ka", "wa", "from"))
        assertEquals("caw-message-v1\nid\ndev-a\n86400\nbody\ndev-a:ka:wa:from,dev-b:kb:wb:to", String(Crypto.messageSig("id", "dev-a", 86400, "body", keys)))
        assertEquals("caw-call-v1\ncall-id\noffer\nv=0", String(Crypto.callSig("call-id", "offer", "v=0")))
    }

    // vector from hash-wasm, as used by the web client
    @Test
    fun argon2idVector() {
        val d = Keys.derive("correct horse battery staple", Kdf("AAECAwQFBgcICQoLDA0ODw", 65536, 3, 1))
        assertEquals("9YPGbrDJg_a-DxgB00WA26bOORo13vYf7QQ8Nl3DfFI", d.loginKey)
        assertEquals("8cKBUJdhXyGHl-1XTHCE7SH9Jgm40LNwa__U3sfmrrg", b64(d.pwKey))
    }
}
