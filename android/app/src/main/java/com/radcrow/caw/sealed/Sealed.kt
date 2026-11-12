package com.radcrow.caw.sealed

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.radcrow.caw.CawApp
import com.radcrow.caw.json
import com.radcrow.caw.strictJson
import com.radcrow.caw.sealed.Crypto.b64
import com.radcrow.caw.sealed.Crypto.unb64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    // group messages: the group, the sending member and a digest of its member list
    val g: String? = null,
    val m: String? = null,
    val sv: String? = null,
) {
    fun toJson(): JsonObject = json.encodeToJsonElement(serializer(), this).jsonObject
}

// control messages; never shown as bubbles. group controls do not fit Plain and are read from the json
val CONTROLS = setOf("hello", "timer", "group_invite", "group_join", "group_decline")

fun plainOf(o: JsonObject): Plain? = runCatching { json.decodeFromJsonElement(Plain.serializer(), o) }.getOrNull()

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

    private suspend fun seal(recipients: List<Triple<String, String, String>>, ttl: Long, plain: JsonObject, id: String): NewMail =
        withContext(Dispatchers.Default) {
            val d = device ?: error("device not set up")
            val aad = id.toByteArray()
            val ck = Crypto.random(16)
            val nonce = Crypto.random(12)
            val body = b64(Crypto.concat(nonce, Crypto.aesGcm(true, ck, nonce, aad, plain.toString().toByteArray())))
            val keys = recipients.map { (rid, k, o) -> Stanza(rid, k, b64(Crypto.hpkeSeal(unb64(k), Crypto.INFO, aad, ck)), o) }
            val sig = Crypto.sign(unb64(d.signPriv), Crypto.messageSig(id, d.id, ttl, body, sigStanzas(keys)))
            NewMail(id, d.id, ttl, body, keys, b64(sig))
        }

    private fun withSelf(own: List<PublicDevice>): List<Pair<String, String>> {
        val d = device ?: error("device not set up")
        return own.filter { it.id != d.id }.mapNotNull { o -> o.encPub?.let { o.id to it } } + (d.id to d.enc.last().pub)
    }

    suspend fun sealMail(to: List<PublicDevice>, own: List<PublicDevice>, ttl: Long, plain: JsonObject, id: String = UUID.randomUUID().toString()): NewMail =
        seal(to.mapNotNull { r -> r.encPub?.let { Triple(r.id, it, "to") } } + withSelf(own).map { (d, k) -> Triple(d, k, "from") }, ttl, plain, id)

    suspend fun sealCopy(devices: List<PublicDevice>, ttl: Long, plain: JsonObject, id: String, self: Boolean): NewMail {
        val list = if (self) withSelf(devices) else devices.mapNotNull { r -> r.encPub?.let { r.id to it } }
        return seal(list.map { (d, k) -> Triple(d, k, "to") }, ttl, plain, id)
    }

    private fun decrypt(m: Mail): JsonObject? {
        val d = device ?: return null
        val st = m.keys.find { it.d == d.id } ?: return null
        val key = d.enc.find { it.pub == st.k } ?: return null
        return runCatching {
            val aad = m.id.toByteArray()
            val ck = Crypto.hpkeOpen(unb64(key.priv), unb64(key.pub), unb64(st.w), Crypto.INFO, aad)
            val body = unb64(m.body)
            strictJson(String(Crypto.aesGcm(false, ck, body.copyOfRange(0, 12), aad, body.copyOfRange(12, body.size)))) as? JsonObject
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
    suspend fun openMail(m: Mail, signers: Map<String, String>): JsonObject? = withContext(Dispatchers.Default) {
        if (!signedBy(m, signers)) null else decrypt(m)
    }

    // group mail names its sender inside the plaintext; the caller verifies the signature before using anything else
    suspend fun openSealed(m: Mail): JsonObject? = withContext(Dispatchers.Default) { decrypt(m) }

    // the caller verifies a hello against the devices behind its card
    suspend fun openHello(m: Mail): Card? = withContext(Dispatchers.Default) {
        decrypt(m)?.let(::plainOf)?.takeIf { it.t == "hello" }?.card
    }

    suspend fun words(linkId: String): List<String> = withContext(Dispatchers.Default) {
        val list = app.assets.open("words.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        Crypto.pairingWords(linkId, list)
    }

    companion object {
        private const val DAY = 86400L
    }
}
