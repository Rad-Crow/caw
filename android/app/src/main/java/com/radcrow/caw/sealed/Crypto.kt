package com.radcrow.caw.sealed

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// message crypto; see docs/protocol.md, section 7
object Crypto {
    val INFO = "caw-message-key-v1".toByteArray()
    private val rng = SecureRandom()
    private val EMPTY = ByteArray(0)
    private const val HEX = "0123456789abcdef"

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))
    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun concat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun i2osp(n: Long, len: Int) = ByteArray(len) { i -> (n ushr (8 * (len - 1 - i))).toByte() }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = HMac(SHA256Digest())
        m.init(KeyParameter(key))
        m.update(data, 0, data.size)
        return ByteArray(32).also { m.doFinal(it, 0) }
    }

    private fun expand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
        var t = EMPTY
        var out = EMPTY
        var i = 1
        while (out.size < len) {
            t = hmac(prk, concat(t, info, byteArrayOf(i++.toByte())))
            out = concat(out, t)
        }
        return out.copyOf(len)
    }

    // rfc 9180: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, base mode
    private val V1 = "HPKE-v1".toByteArray()
    private val KEM = concat("KEM".toByteArray(), i2osp(0x20, 2))
    private val SUITE = concat("HPKE".toByteArray(), i2osp(0x20, 2), i2osp(1, 2), i2osp(1, 2))

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray) =
        hmac(salt, concat(V1, suite, label.toByteArray(), ikm))

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, len: Int) =
        expand(prk, concat(i2osp(len.toLong(), 2), V1, suite, label.toByteArray(), info), len)

    private fun x25519(priv: ByteArray, pub: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(X25519PrivateKeyParameters(priv, 0))
        return ByteArray(32).also { a.calculateAgreement(X25519PublicKeyParameters(pub, 0), it, 0) }
    }

    private fun context(dh: ByteArray, enc: ByteArray, pkR: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val eae = labeledExtract(KEM, EMPTY, "eae_prk", dh)
        val shared = labeledExpand(KEM, eae, "shared_secret", concat(enc, pkR), 32)
        val ksc = concat(
            byteArrayOf(0),
            labeledExtract(SUITE, EMPTY, "psk_id_hash", EMPTY),
            labeledExtract(SUITE, EMPTY, "info_hash", info),
        )
        val secret = labeledExtract(SUITE, shared, "secret", EMPTY)
        return labeledExpand(SUITE, secret, "key", ksc, 16) to labeledExpand(SUITE, secret, "base_nonce", ksc, 12)
    }

    fun aesGcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    fun hpkeSeal(pkR: ByteArray, info: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        val (skE, enc) = newX25519()
        val (key, nonce) = context(x25519(skE, pkR), enc, pkR, info)
        return concat(enc, aesGcm(true, key, nonce, aad, pt))
    }

    fun hpkeOpen(skR: ByteArray, pkR: ByteArray, w: ByteArray, info: ByteArray, aad: ByteArray): ByteArray {
        val enc = w.copyOfRange(0, 32)
        val (key, nonce) = context(x25519(skR, enc), enc, pkR, info)
        return aesGcm(false, key, nonce, aad, w.copyOfRange(32, w.size))
    }

    // (private, public), both raw 32 bytes
    fun newX25519(): Pair<ByteArray, ByteArray> {
        val k = X25519PrivateKeyParameters(rng)
        return k.encoded to k.generatePublicKey().encoded
    }

    fun newEd25519(): Pair<ByteArray, ByteArray> {
        val k = Ed25519PrivateKeyParameters(rng)
        return k.encoded to k.generatePublicKey().encoded
    }

    fun sign(priv: ByteArray, data: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(priv, 0))
        s.update(data, 0, data.size)
        return s.generateSignature()
    }

    fun verify(pub: ByteArray, sig: ByteArray, data: ByteArray): Boolean = runCatching {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(pub, 0))
        s.update(data, 0, data.size)
        s.verifySignature(sig)
    }.getOrDefault(false)

    fun encCert(deviceId: String, encPub: ByteArray, createdAt: Long) =
        concat("caw-enc-v1".toByteArray(), deviceId.toByteArray(), byteArrayOf(0), encPub, i2osp(createdAt, 8))

    data class Stanza(val d: String, val k: String, val w: String, val o: String)

    // sealed for every device of both accounts; o says whose device each stanza is for
    fun messageSig(id: String, fromDevice: String, ttl: Long, body: String, keys: List<Stanza>): ByteArray {
        val stanzas = keys.sortedBy { it.d }.joinToString(",") { "${it.d}:${it.k}:${it.w}:${it.o}" }
        return "caw-message-v1\n$id\n$fromDevice\n$ttl\n$body\n$stanzas".toByteArray()
    }

    // a call's session description, bound to the call
    fun callSig(callId: String, type: String, sdp: String) = "caw-call-v1\n$callId\n$type\n$sdp".toByteArray()

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // see docs/protocol.md, section 9
    fun groupToken(secret: ByteArray, gen: Long): String = b64(hmac(secret, concat("caw-group-token-v1".toByteArray(), i2osp(gen, 8))))

    fun groupMessageId(secret: ByteArray, trigger: String, member: String): String {
        val h = hmac(secret, concat("caw-group-id-v1".toByteArray(), trigger.toByteArray(), byteArrayOf(0), member.toByteArray())).copyOf(16)
        h[6] = ((h[6].toInt() and 0x0f) or 0x80).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        val x = buildString { for (b in h) append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15]) }
        return "${x.substring(0, 8)}-${x.substring(8, 12)}-${x.substring(12, 16)}-${x.substring(16, 20)}-${x.substring(20)}"
    }

    fun membershipDigest(list: String): String = b64(sha256(list.toByteArray()).copyOf(16))

    // derived from the link id, which both sides of a pairing know
    fun pairingWords(linkId: String, wordlist: List<String>): List<String> {
        val tag = "caw-words-v1".toByteArray()
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(concat(tag, linkId.toByteArray()), tag, 65536)
        val out = (gen.generateDerivedParameters(48) as KeyParameter).key
        return out.map { wordlist[it.toInt() and 0xff] }
    }
}
