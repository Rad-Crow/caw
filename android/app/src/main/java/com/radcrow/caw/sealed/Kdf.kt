package com.radcrow.caw.sealed

import com.radcrow.caw.sealed.Crypto.b64
import com.radcrow.caw.sealed.Crypto.unb64
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.text.Normalizer

// password -> login key (sent to the server) + password key (kept local); see docs/protocol.md, sections 3.1 and 4.1
@Serializable
data class Kdf(val salt: String, val m: Int, val t: Int, val p: Int)

class Derived(val loginKey: String, val pwKey: ByteArray)

object Keys {
    private val KEY_AAD = "caw-key-v1".toByteArray()

    fun fresh() = Kdf(b64(Crypto.random(16)), 65536, 3, 1)

    fun derive(password: String, k: Kdf): Derived {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(unb64(k.salt))
            .withMemoryAsKB(k.m)
            .withIterations(k.t)
            .withParallelism(k.p)
            .build()
        val out = ByteArray(64)
        Argon2BytesGenerator().apply { init(params) }
            .generateBytes(Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), out)
        return Derived(b64(out.copyOfRange(0, 32)), out.copyOfRange(32, 64))
    }

    // "salt.m.t.p.nonce+ct": embeds its wrap parameters so it still opens after the login salt changes
    fun wrap(dataKey: ByteArray, pwKey: ByteArray, k: Kdf): String {
        val iv = Crypto.random(12)
        return "${k.salt}.${k.m}.${k.t}.${k.p}.${b64(Crypto.concat(iv, Crypto.aesGcm(true, pwKey, iv, KEY_AAD, dataKey)))}"
    }

    fun kdfOf(w: String): Kdf? {
        val parts = w.split('.')
        if (parts.size != 5) return null
        return runCatching { Kdf(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt()) }.getOrNull()
    }

    fun unwrap(w: String, pwKey: ByteArray): ByteArray? = runCatching {
        val b = unb64(w.split('.')[4])
        Crypto.aesGcm(false, pwKey, b.copyOfRange(0, 12), KEY_AAD, b.copyOfRange(12, b.size))
    }.getOrNull()

    // reuses an existing derivation when the wrap used the same parameters
    fun unwrapWith(w: String, password: String, have: Secret? = null): ByteArray? {
        val k = kdfOf(w) ?: return null
        val pwKey = if (have != null && have.kdf == k) have.pwKey else derive(password, k).pwKey
        return unwrap(w, pwKey)
    }
}

// derived from a typed password; held only until the vault is unlocked
class Secret(val password: String, val kdf: Kdf, val pwKey: ByteArray)

object Password {
    const val MIN = 12
    const val HINT = "At least 12 characters. A few random words work well."

    private val COMMON = listOf(
        "password", "passw0rd", "qwerty", "qwertyuiop", "asdfgh", "asdfghjkl", "zxcvbnm", "letmein", "welcome", "iloveyou",
        "admin", "monkey", "dragon", "football", "baseball", "soccer", "hockey", "sunshine", "princess", "trustno",
        "master", "shadow", "superman", "batman", "starwars", "whatever", "freedom", "secret", "changeme", "login",
        "abc", "abcdef", "abcdefgh", "caw", "hello", "charlie", "michael", "jordan", "jesus", "god", "love", "pokemon",
    )
    private val SEQS = listOf("0123456789", "abcdefghijklmnopqrstuvwxyz", "qwertyuiopasdfghjklzxcvbnm")

    // same rules as the web client
    fun problem(pw: String, username: String = ""): String? {
        if (pw.codePointCount(0, pw.length) < MIN) return "Use at least $MIN characters."
        val low = pw.lowercase()
        if (low.toSet().size < 5) return "Too repetitive. A few random words work well."
        val u = username.trim().lowercase()
        if (u.length >= 3 && low.contains(u)) return "Don't include your username."
        val core = low.filter { it in 'a'..'z' }
        if (core.isNotEmpty() && (core in COMMON || COMMON.any { w -> core == w.repeat(maxOf(1, Math.round(core.length.toFloat() / w.length))) }))
            return "Too common. A few random words work well."
        val alnum = low.filter { it in 'a'..'z' || it in '0'..'9' }
        if (alnum.length >= 6 && SEQS.any { s -> (s + s).contains(alnum) || s.reversed().contains(alnum) }) return "Too easy to guess."
        return null
    }
}
