package com.radcrow.caw.sealed

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.radcrow.caw.CawApp
import com.radcrow.caw.json
import com.radcrow.caw.sealed.Crypto.b64
import com.radcrow.caw.sealed.Crypto.unb64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class EncKey(val pub: String, val priv: String, val createdAt: Long)

@Serializable
data class LocalDevice(val id: String, val signPub: String, val signPriv: String, val enc: List<EncKey>)

@Serializable
data class PublicDevice(
    val id: String,
    @SerialName("sign_pub") val signPub: String,
    @SerialName("enc_pub") val encPub: String? = null,
    @SerialName("enc_created_at") val encCreatedAt: Long? = null,
    @SerialName("enc_sig") val encSig: String? = null,
)

@Serializable
data class LinkDevices(val account: String, val devices: List<PublicDevice>)

@Serializable
data class OwnDevice(
    val id: String,
    val label: String = "",
    @SerialName("sign_pub") val signPub: String,
    @SerialName("enc_pub") val encPub: String? = null,
    @SerialName("enc_created_at") val encCreatedAt: Long? = null,
    @SerialName("enc_sig") val encSig: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen") val lastSeen: Long,
    // current and recent encryption keys
    val keys: List<String> = emptyList(),
) {
    fun public() = PublicDevice(id, signPub, encPub, encCreatedAt, encSig)
}

// o: whose device, the recipient's ("to") or the sender's own ("from")
@Serializable
data class Stanza(val d: String, val k: String, val w: String, val o: String)

@Serializable
data class Mail(
    @SerialName("link_id") val linkId: String,
    val id: String,
    @SerialName("from_device") val fromDevice: String,
    val ttl: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    val body: String,
    val keys: List<Stanza>,
    val sig: String,
)

@Serializable
data class NewMail(
    val id: String,
    @SerialName("from_device") val fromDevice: String,
    val ttl: Long,
    val body: String,
    val keys: List<Stanza>,
    val sig: String,
    val blobs: List<String> = emptyList(),
)

// sent by the scanning side over the new link: who it is and the return link
@Serializable
data class Card(
    val server: String,
    val account: String,
    val name: String,
    @SerialName("link_id") val linkId: String,
    val token: String,
)

@Serializable
data class Plain(
    val t: String,
    val text: String? = null,
    val outcome: String? = null,
    val secs: Long? = null,
    val kind: String? = null,
    val mime: String? = null,
    val blob: String? = null,
    val key: String? = null,
    val size: Long? = null,
    val w: Int? = null,
    val h: Int? = null,
    val dur: Long? = null,
    val thumb: String? = null,
    val card: Card? = null,
    val ttl: Long? = null,
) {
    // control messages; never shown as bubbles
    val control get() = t in setOf("hello", "accept", "decline", "timer", "bye")
}

class Sealed(private val app: CawApp) {
    @Volatile var device: LocalDevice? = null
        private set

    private val sp = app.getSharedPreferences("caw_device", Context.MODE_PRIVATE)

    private fun load(userId: Long): LocalDevice? = runCatching {
        val blob = sp.getString("d$userId", null) ?: return null
        json.decodeFromString<LocalDevice>(String(Vault.open(unb64(blob))))
    }.getOrNull()

    private fun save(userId: Long, d: LocalDevice) {
        sp.edit { putString("d$userId", b64(Vault.seal(json.encodeToString(LocalDevice.serializer(), d).toByteArray()))) }
    }

    fun forgetLocal(userId: Long) {
        sp.edit { remove("d$userId") }
        device = null
    }

    // loads or enrolls this install, rotates the encryption key daily and prunes keys no longer needed
    suspend fun ensure(userId: Long, ttlMax: Long): LocalDevice = withContext(Dispatchers.Default) {
        var d = load(userId)
        val server = app.api.myDevices()
        val onServer = d?.let { local -> server.find { it.id == local.id } }
        if (d != null && onServer == null) d = null

        if (d == null) {
            val (sk, pk) = Crypto.newEd25519()
            val id = app.api.addDevice("${Build.MANUFACTURER} ${Build.MODEL}".trim(), b64(pk))
            d = LocalDevice(id, b64(pk), b64(sk), emptyList())
        }

        val t = app.repo.serverNow()
        val cur = d.enc.lastOrNull()
        if (cur == null || t - cur.createdAt > DAY) {
            val (sk, pk) = Crypto.newX25519()
            val sig = Crypto.sign(unb64(d.signPriv), Crypto.encCert(d.id, pk, t))
            app.api.putDeviceKey(d.id, b64(pk), t, b64(sig))
            d = d.copy(enc = d.enc + EncKey(b64(pk), b64(sk), t))
        } else if (onServer != null) {
            // also binds this login to the device; required for a fresh sign-in on a known device
            val sig = Crypto.sign(unb64(d.signPriv), Crypto.encCert(d.id, unb64(cur.pub), cur.createdAt))
            app.api.putDeviceKey(d.id, cur.pub, cur.createdAt, b64(sig))
        }
        val last = d.enc.last()
        d = d.copy(enc = d.enc.filter { it == last || it.createdAt + DAY + ttlMax > t })
        save(userId, d)
        device = d
        d
    }

    fun keyDue(now: Long) = device?.enc?.lastOrNull()?.let { now - it.createdAt > DAY } ?: true

    // a device's encryption key only counts if its own identity key signed it
    fun certOk(d: PublicDevice): Boolean {
        val enc = d.encPub ?: return false
        val sig = d.encSig ?: return false
        val at = d.encCreatedAt ?: return false
        return Crypto.verify(unb64(d.signPub), unb64(sig), Crypto.encCert(d.id, unb64(enc), at))
    }

    private fun sigStanzas(keys: List<Stanza>) = keys.map { Crypto.Stanza(it.d, it.k, it.w, it.o) }

    // sealed for every device of the recipient and of the sender (the sender's copies)
    suspend fun sealMail(to: List<PublicDevice>, own: List<PublicDevice>, ttl: Long, plain: Plain, id: String = UUID.randomUUID().toString()): NewMail =
        withContext(Dispatchers.Default) {
            val d = device ?: error("device not set up")
            val mine = d.enc.last()
            val aad = id.toByteArray()
            val ck = Crypto.random(16)
            val nonce = Crypto.random(12)
            val pt = json.encodeToString(Plain.serializer(), plain).toByteArray()
            val body = b64(Crypto.concat(nonce, Crypto.aesGcm(true, ck, nonce, aad, pt)))
            val ownKeys = own.filter { it.id != d.id }.mapNotNull { o -> o.encPub?.let { o.id to it } } + (d.id to mine.pub)
            val keys = to.mapNotNull { r -> r.encPub?.let { Stanza(r.id, it, b64(Crypto.hpkeSeal(unb64(it), Crypto.INFO, aad, ck)), "to") } } +
                ownKeys.map { (id2, k) -> Stanza(id2, k, b64(Crypto.hpkeSeal(unb64(k), Crypto.INFO, aad, ck)), "from") }
            val sig = Crypto.sign(unb64(d.signPriv), Crypto.messageSig(id, d.id, ttl, body, sigStanzas(keys)))
            NewMail(id, d.id, ttl, body, keys, b64(sig))
        }

    private fun decrypt(m: Mail): Plain? {
        val d = device ?: return null
        val st = m.keys.find { it.d == d.id } ?: return null
        val key = d.enc.find { it.pub == st.k } ?: return null
        return runCatching {
            val aad = m.id.toByteArray()
            val ck = Crypto.hpkeOpen(unb64(key.priv), unb64(key.pub), unb64(st.w), Crypto.INFO, aad)
            val body = unb64(m.body)
            json.decodeFromString<Plain>(String(Crypto.aesGcm(false, ck, body.copyOfRange(0, 12), aad, body.copyOfRange(12, body.size))))
        }.getOrNull()
    }

    // device id and signature over a call's session description
    fun signCall(callId: String, type: String, sdp: String): Pair<String, String>? {
        val d = device ?: return null
        return d.id to b64(Crypto.sign(unb64(d.signPriv), Crypto.callSig(callId, type, sdp)))
    }

    fun signedBy(m: Mail, signers: Map<String, String>): Boolean {
        val pub = signers[m.fromDevice] ?: return false
        return runCatching { Crypto.verify(unb64(pub), unb64(m.sig), Crypto.messageSig(m.id, m.fromDevice, m.ttl, m.body, sigStanzas(m.keys))) }
            .getOrDefault(false)
    }

    // null if not sealed for this device or not signed by a device the sender's server lists
    suspend fun openMail(m: Mail, signers: Map<String, String>): Plain? = withContext(Dispatchers.Default) {
        if (!signedBy(m, signers)) null else decrypt(m)
    }

    // the caller verifies a hello against the devices behind its card
    suspend fun openHello(m: Mail): Card? = withContext(Dispatchers.Default) {
        decrypt(m)?.takeIf { it.t == "hello" }?.card
    }

    suspend fun words(linkId: String): List<String> = withContext(Dispatchers.Default) {
        val list = app.assets.open("words.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        Crypto.pairingWords(linkId, list)
    }

    companion object {
        private const val DAY = 86400L
    }
}
