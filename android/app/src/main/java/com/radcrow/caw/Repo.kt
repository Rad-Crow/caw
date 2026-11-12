package com.radcrow.caw

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import com.radcrow.caw.call.Notifications
import com.radcrow.caw.media.Prepared
import com.radcrow.caw.media.TooLarge
import com.radcrow.caw.sealed.CONTROLS
import com.radcrow.caw.sealed.Card
import com.radcrow.caw.sealed.Crypto
import com.radcrow.caw.sealed.Keys
import com.radcrow.caw.sealed.Mail
import com.radcrow.caw.sealed.NewMail
import com.radcrow.caw.sealed.OwnDevice
import com.radcrow.caw.sealed.Password
import com.radcrow.caw.sealed.Plain
import com.radcrow.caw.sealed.PublicDevice
import com.radcrow.caw.sealed.Secret
import com.radcrow.caw.sealed.Vault
import com.radcrow.caw.sealed.plainOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.text.Collator
import java.util.UUID

data class RawMessage(val id: String, val fromUser: Long, val toUser: Long, val createdAt: Long, val expiresAt: Long, val sender: String? = null)

// pending: in the outbox, not yet on the server
data class Message(val raw: RawMessage, val plain: Plain?, val pending: Boolean = false)

// group entries: one copy per member, each with its own id; held copies wait for the member's card to change
@Serializable
data class Outgoing(
    val id: String,
    val to: Long,
    val plain: Plain,
    val blobs: List<String>,
    val at: Long,
    val ttl: Long,
    val group: String? = null,
    val copies: Map<String, String>? = null,
    val cards: Map<String, GroupCard>? = null,
    val held: Map<String, String>? = null,
    val body: JsonObject? = null,
)

// at: seconds with a fraction, so an event sorts after messages that arrived earlier in the same second
data class CawEvent(val at: Double, val text: String)

data class Attachment(val status: String, val progress: Float, val prepared: Prepared? = null)

// pairing request from someone who redeemed this account's code
data class Prompt(val linkId: String, val card: Card, val words: List<String>)

data class GroupPrompt(val contact: String, val from: String, val g: String, val name: String, val members: List<String>, val inviter: String, val member: String)

// only g syncs through the vault
@Serializable
data class LocalGroup(
    val g: Group,
    val num: Long,
    val u: Long,
    val names: Map<String, String> = emptyMap(),
    val signers: Map<String, Map<String, String>> = emptyMap(),
)

data class GroupMember(val id: String, val name: String, val host: String, val contact: Boolean, val me: Boolean)

@Serializable
data class NewDevice(val id: String, val label: String)

// own devices over time; lets the device check tolerate recent removals and key rotations
@Serializable
data class KnownDevice(val keys: List<String>, val sign: String, val label: String, val seen: Long)

@Serializable
private data class Stored(
    val contacts: List<Contact>,
    val gone: Map<String, Long> = emptyMap(),
    val groups: List<LocalGroup> = emptyList(),
    val goneGroups: Map<String, Long> = emptyMap(),
)

private data class MemberLink(override val server: String, override val out: LinkCred) : Remote

private fun remoteOf(c: GroupCard): Remote = MemberLink(c.server, LinkCred(c.linkId, c.token))

// never accepted through a contact link
private fun isGroupContent(p: JsonObject): Boolean {
    val t = p.string("t")
    return t == "group" || ((t == "text" || t == "media") && p.containsKey("g"))
}

// javascript's `sv && sv !== digest`
private fun differs(sv: JsonElement?, digest: String): Boolean = when (sv) {
    null, is JsonNull -> false
    is JsonPrimitive -> if (sv.isString) sv.content.isNotEmpty() && sv.content != digest else sv.content == "true" || sv.content.toDoubleOrNull()?.let { it != 0.0 } == true
    else -> true
}

private class Opened(val raw: JsonObject, val sender: String) {
    val plain by lazy { plainOf(raw) }
}

private fun clip(s: String, n: Int): String = if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

fun plainText(p: Plain): String {
    if (p.t != "media") return p.text.orEmpty()
    val kind = if (p.kind == "video") "Video" else "Photo"
    return if (p.text.isNullOrBlank()) kind else "$kind: ${p.text}"
}

class Repo(private val app: CawApp) {
    val me = MutableStateFlow(app.prefs.me)
    val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val groups = MutableStateFlow<List<LocalGroup>>(emptyList())
    val messages = MutableStateFlow<Map<Long, List<Message>>>(emptyMap())
    val events = MutableStateFlow<Map<Long, List<CawEvent>>>(emptyMap())
    val seen = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val opened = MutableStateFlow<Set<Long>>(emptySet())
    // stored in the contacts (and so the vault); exposed as separate flows for the views
    val favorites = MutableStateFlow<Set<Long>>(emptySet())
    val aliases = MutableStateFlow<Map<Long, String>>(emptyMap())
    val composeFor = MutableStateFlow<Long?>(null)
    val prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val groupPrompts = MutableStateFlow<List<GroupPrompt>>(emptyList())
    // contact whose server did not respond; the user chooses retry or remove
    val unreachable = MutableStateFlow<Long?>(null)
    val config = MutableStateFlow(ServerConfig())
    val deviceError = MutableStateFlow<String?>(null)
    val clock = MutableStateFlow(System.currentTimeMillis() / 1000)
    val drafts = MutableStateFlow<Map<Long, String>>(emptyMap())
    val attachments = MutableStateFlow<Map<Long, Attachment>>(emptyMap())
    // persisted across restarts, sealed with the keystore vault key
    val outbox = MutableStateFlow<List<Outgoing>>(emptyList())
    // vault is locked under a password that predates an admin reset
    val vaultLocked = MutableStateFlow(false)
    val ownDevices = MutableStateFlow<List<OwnDevice>>(emptyList())
    val newDevices = MutableStateFlow<List<NewDevice>>(emptyList())
    // contacts whose messages were also sealed for a device the server does not list
    val deviceAlerts = MutableStateFlow<Set<Long>>(emptySet())
    // an incoming call names its contact by link, so call handling waits for this
    val contactsLoaded = MutableStateFlow(false)
    // after "that wasn't me": change the password and replace the vault key
    val secureNow = MutableStateFlow(false)

    @Volatile var openChat: Long? = null
    @Volatile private var skew = 0L
    // a full reload and live mail never overlap
    private val loadLock = Mutex()
    private val flushLock = Mutex()
    private val setupLock = Mutex()
    private val vaultLock = Mutex()
    private val devicesLock = Mutex()
    private val sp = app.getSharedPreferences("caw_seen", Context.MODE_PRIVATE)
    private val outSp = app.getSharedPreferences("caw_outbox", Context.MODE_PRIVATE)
    private val contactSp = app.getSharedPreferences("caw_contacts", Context.MODE_PRIVATE)
    private val keySp = app.getSharedPreferences("caw_datakey", Context.MODE_PRIVATE)
    private val knownSp = app.getSharedPreferences("caw_known", Context.MODE_PRIVATE)
    private val outSerializer = ListSerializer(Outgoing.serializer())
    private val knownSerializer = MapSerializer(String.serializer(), KnownDevice.serializer())
    private val newSerializer = ListSerializer(NewDevice.serializer())
    private val peers = HashMap<String, Pair<Long, List<PublicDevice>>>()
    private val memberLists = HashMap<String, Pair<Long, List<PublicDevice>>>()
    private val synced = HashMap<String, Long>()
    // entries a send is still posting; flush skips them
    private val posting = HashSet<String>()
    // per server: next retry time and backoff step
    private val retry = HashMap<String, Pair<Long, Int>>()
    private val main = Handler(Looper.getMainLooper())
    private var gone: Map<String, Long> = emptyMap()
    private var goneGroups: Map<String, Long> = emptyMap()
    // the account's group links, known before the vault names their groups
    @Volatile private var groupLinks: Set<String> = emptySet()
    @Volatile private var vaultExtra: Map<String, JsonElement> = emptyMap()
    private var known: Map<String, KnownDevice> = emptyMap()
    @Volatile private var dataKey: ByteArray? = null
    // key before rotation, for merging a copy written concurrently by another device
    @Volatile private var prevKey: ByteArray? = null
    @Volatile private var pending: Secret? = null
    // set by sign-in, consumed once by setup
    @Volatile private var signInSecret: Secret? = null
    @Volatile private var vaultVersion = 0L
    @Volatile private var pushQueued = false
    @Volatile private var devicesAgain = false
    @Volatile private var enrolledAt = 0L
    @Volatile private var ownAt = 0L

    init {
        app.scope.launch {
            var tick = 0
            while (true) {
                delay(5000)
                expire()
                // hourly, so a long-running process keeps up with daily key rotation
                if (++tick % 720 == 0 && me.value != null) runCatching { refreshKeys() }
                if (tick % 3 == 0 && outbox.value.isNotEmpty()) runCatching { flush() }
            }
        }
    }

    fun serverNow() = System.currentTimeMillis() / 1000 + skew

    private fun stamp() = System.currentTimeMillis() + skew * 1000

    fun contact(num: Long) = contacts.value.find { it.num == num }

    fun name(id: Long) = group(id)?.let { it.g.name.v.ifEmpty { "Group" } } ?: contact(id)?.let { it.s.alias ?: it.name } ?: "Someone"

    fun timerFor(id: Long) = group(id)?.g?.ttl?.v ?: contact(id)?.ttl ?: config.value.messageTtlDefault

    private fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private fun publish() {
        val list = contacts.value
        favorites.value = list.filter { it.s.favorite == true }.map { it.num }.toSet()
        aliases.value = list.mapNotNull { c -> c.s.alias?.let { c.num to it } }.toMap()
    }

    private fun saveContacts() {
        val uid = me.value?.id ?: return
        val v = Stored(contacts.value, gone, groups.value, goneGroups)
        contactSp.edit { putString("c.$uid", Crypto.b64(Vault.seal(json.encodeToString(Stored.serializer(), v).toByteArray()))) }
    }

    private fun setContacts(list: List<Contact>) {
        contacts.value = list
        publish()
        saveContacts()
    }

    private fun nextNum(list: List<Contact> = contacts.value) =
        maxOf(1_000_000L, list.maxOfOrNull { it.num } ?: 0L, groups.value.maxOfOrNull { it.num } ?: 0L) + 1

    // change synced to every device of this account
    private fun touch(num: Long, at: Long = stamp(), f: (Synced) -> Synced) {
        setContacts(contacts.value.map { c -> if (c.num == num) c.copy(s = f(c.s).copy(updatedAt = maxOf(at, c.s.updatedAt + 1))) else c })
        schedulePush()
    }

    private fun local(num: Long, f: (Contact) -> Contact) {
        setContacts(contacts.value.map { if (it.num == num) f(it) else it })
    }

    // open caws first by recency, then the rest by name
    fun contactIds(query: String = ""): List<Long> {
        val q = query.trim().lowercase()
        val n = messages.value
        val rank = { id: Long -> n[id]?.lastOrNull()?.raw?.createdAt ?: if (id in opened.value || id == openChat) Long.MAX_VALUE else -1L }
        val people = contacts.value
            .filter { q.isEmpty() || name(it.num).lowercase().contains(q) || it.name.lowercase().contains(q) || hostOf(it.server).contains(q) }
            .map { it.num }
        val teams = groups.value.filter { q.isEmpty() || it.g.name.v.lowercase().contains(q) }.map { it.num }
        return (people + teams).sortedWith(compareByDescending<Long> { rank(it) }.thenBy { name(it).lowercase() })
    }

    fun unread(id: Long): Int {
        val since = seen.value[id] ?: 0
        return messages.value[id].orEmpty().count { it.raw.fromUser == id && it.raw.createdAt > since }
    }

    fun preview(id: Long): Pair<String, Boolean> {
        val g = group(id)
        if (g != null) {
            if (g.g.inviter != null) return "Waiting to be added" to false
            val n = messages.value[id]?.lastOrNull() ?: return memberCount(id) to false
            val p = n.plain ?: return "Encrypted message" to false
            return "${if (n.raw.fromUser == me.value?.id) "You" else memberName(id, n.raw.sender)}: ${plainText(p)}" to false
        }
        val c = contact(id)
        if (c?.status == "pending") return "Waiting to accept" to false
        val n = messages.value[id]?.lastOrNull() ?: return hostOf(c?.server.orEmpty()) to false
        val p = n.plain ?: return "Encrypted message" to false
        if (p.t == "call") return callText(n) to (p.outcome == "missed" && n.raw.fromUser == id)
        return (if (n.raw.fromUser == me.value?.id) "You: " else "") + plainText(p) to false
    }

    fun callText(n: Message): String {
        val p = n.plain ?: return ""
        val mine = n.raw.fromUser == me.value?.id
        val s = p.secs ?: 0
        val dur = "%d:%02d".format(s / 60, s % 60)
        return when (p.outcome) {
            "answered" -> "${if (mine) "Outgoing" else "Incoming"} call \u00b7 $dur"
            "declined" -> if (mine) "Outgoing call, declined" else "Incoming call, declined"
            else -> if (mine) "Outgoing call, no answer" else "Missed call"
        }
    }

    fun nameFor(p: Peer) = contact(p.id)?.let { it.s.alias ?: it.name } ?: p.name

    fun accountName(id: Long) = contact(id)?.name.orEmpty()

    fun peer(c: Contact) = Peer(c.num, hostOf(c.server), name(c.num))

    fun peer(num: Long) = contact(num)?.let(::peer)

    fun setAlias(id: Long, name: String) {
        val c = contact(id) ?: return
        val n = name.trim()
        touch(id) { it.copy(alias = if (n.isNotEmpty() && n != c.name) n else null) }
    }

    fun toggleFavorite(id: Long) {
        val c = contact(id) ?: return
        touch(id) { it.copy(favorite = if (c.s.favorite == true) null else true) }
    }

    // --- sign-in; only a key derived from the password is sent to the server

    suspend fun signIn(address: String, username: String, password: String) {
        val (server, cfg) = app.api.reach(normServer(address) ?: throw IOException("That isn't a server address."))
        val s = app.api.salt(server, username)
        val kdf = com.radcrow.caw.sealed.Kdf(s.salt, s.m, s.t, s.p)
        val d = withContext(Dispatchers.Default) { Keys.derive(password, kdf) }
        val res = app.api.login(server, username, d.loginKey)
        signInSecret = Secret(password, kdf, d.pwKey)
        app.onLoggedIn(server, res, cfg)
    }

    private fun setMe(user: Me) {
        app.prefs.me = user
        me.value = user
    }

    // first sign-in with a password an admin set
    suspend fun choosePassword(password: String) {
        val kdf = Keys.fresh()
        val d = withContext(Dispatchers.Default) { Keys.derive(password, kdf) }
        setMe(app.api.setLogin(d.loginKey, kdf.salt))
        signInSecret = Secret(password, kdf, d.pwKey)
        setup()
    }

    // re-wraps only the data key; with rotate, a new data key invalidates copies held by removed devices
    suspend fun changePassword(current: String, next: String, rotate: Boolean = false) {
        val user = me.value ?: return
        val s = app.api.salt(app.prefs.server!!, user.username)
        val cur = withContext(Dispatchers.Default) { Keys.derive(current, com.radcrow.caw.sealed.Kdf(s.salt, s.m, s.t, s.p)) }
        val kdf = Keys.fresh()
        val d = withContext(Dispatchers.Default) { Keys.derive(next, kdf) }
        var key = dataKey
        if (rotate && key != null) {
            pullVault()
            prevKey = key
            key = Crypto.random(32)
        }
        setMe(app.api.setLogin(d.loginKey, kdf.salt, cur.loginKey, key?.let { Keys.wrap(it, d.pwKey, kdf) }))
        if (rotate && key != null) {
            setDataKey(key)
            pushVault(true)
        }
    }

    // enrolls or loads this device, then loads contacts and all live messages
    suspend fun setup() {
        val user = me.value ?: return
        if (user.mustChange) return
        val uid = user.id
        val secret = signInSecret.also { signInSecret = null }
        app.prefs.server?.let { s -> runCatching { app.api.config(s) }.onSuccess { config.value = it; app.applyConfig(it) } }
        seen.value = sp.all.mapNotNull { (k, v) -> k.removePrefix("seen.$uid.").toLongOrNull()?.let { it to (v as Long) } }.toMap()
        known = knownSp.getString("k.$uid", null)?.let { runCatching { json.decodeFromString(knownSerializer, it) }.getOrNull() }.orEmpty()
        newDevices.value = knownSp.getString("n.$uid", null)?.let { runCatching { json.decodeFromString(newSerializer, it) }.getOrNull() }.orEmpty()
        // the vault key requires the password; a session without one returns to sign-in
        val kept = if (secret == null) loadDataKey(uid) else null
        if (secret == null && kept == null) return signedOut("Sign in again to sync your contacts.")
        loadContacts(uid)
        contactsLoaded.value = true
        try {
            app.sealed.ensure(uid, config.value.messageTtlMax)
            deviceError.value = null
            refreshDevices()
            if (secret != null) establishKey(secret) else setDataKey(kept)
            pullVault()
            reconcile()
            loadMessages()
            loadOutbox(uid)
            app.scope.launch { runCatching { flush() } }
        } catch (e: Exception) {
            deviceError.value = "Device setup failed: ${e.message}"
            Log.w(TAG, "device setup", e)
        }
    }

    private fun loadContacts(uid: Long) {
        val stored = contactSp.getString("c.$uid", null)
        val v = stored?.let { runCatching { json.decodeFromString(Stored.serializer(), String(Vault.open(Crypto.unb64(it)))) }.getOrNull() }
        contacts.value = v?.contacts.orEmpty()
        gone = v?.gone.orEmpty()
        groups.value = v?.groups.orEmpty()
        goneGroups = v?.goneGroups.orEmpty()
        publish()
    }

    // --- the vault: contacts and groups shared by all of this account's devices

    private fun loadDataKey(uid: Long): ByteArray? =
        keySp.getString("k.$uid", null)?.let { runCatching { Vault.open(Crypto.unb64(it)) }.getOrNull() }

    // survives a server-ended session; cleared on sign-out
    private fun setDataKey(key: ByteArray?) {
        dataKey = key
        vaultLocked.value = key == null && pending != null
        if (key != null) pending = null
        val uid = me.value?.id ?: return
        keySp.edit { if (key != null) putString("k.$uid", Crypto.b64(Vault.seal(key))) else remove("k.$uid") }
    }

    private fun openVault(key: ByteArray, blob: String): VaultDoc? = runCatching {
        val b = Crypto.unb64(blob)
        val pt = Crypto.aesGcm(false, key, b.copyOfRange(0, 12), VAULT_AAD, b.copyOfRange(12, b.size))
        VaultDoc.decode(String(pt))
    }.getOrNull()

    private fun sealVault(key: ByteArray, doc: VaultDoc): String {
        val iv = Crypto.random(12)
        return Crypto.b64(Crypto.concat(iv, Crypto.aesGcm(true, key, iv, VAULT_AAD, doc.encode().toByteArray())))
    }

    private fun openAny(blob: String): VaultDoc? = listOfNotNull(dataKey, prevKey).firstNotNullOfOrNull { openVault(it, blob) }

    // data key from the password; otherwise a key kept on this device, or a new one for an empty vault
    private suspend fun establishKey(s: Secret) {
        val r = app.api.vault()
        var key = r.wrappedKey?.let { w -> withContext(Dispatchers.Default) { Keys.unwrapWith(w, s.password, s) } }
        if (key == null) {
            val kept = loadDataKey(me.value!!.id)
            key = when {
                kept != null && (r.blob == null || openVault(kept, r.blob) != null) -> kept
                r.blob == null -> Crypto.random(32)
                else -> null
            }
            if (key != null) app.api.putVaultKey(Keys.wrap(key, s.pwKey, s.kdf))
        }
        if (key == null) pending = s
        setDataKey(key)
    }

    // unlocks a vault still locked under the pre-reset password
    suspend fun unlockOld(password: String) {
        val s = pending ?: throw IOException("Sign in again to unlock your contacts.")
        val r = app.api.vault()
        val key = r.wrappedKey?.let { w -> withContext(Dispatchers.Default) { Keys.unwrapWith(w, password) } }
        if (key == null || (r.blob != null && openVault(key, r.blob) == null)) throw IOException("Incorrect password.")
        app.api.putVaultKey(Keys.wrap(key, s.pwKey, s.kdf))
        setDataKey(key)
        pullVault()
    }

    // discards the locked vault; this device's contacts become the new list
    suspend fun startOver() {
        val s = pending ?: throw IOException("Sign in again to unlock your contacts.")
        val key = Crypto.random(32)
        app.api.putVaultKey(Keys.wrap(key, s.pwKey, s.kdf))
        setDataKey(key)
        pushVault(true)
    }

    private fun doc(): VaultDoc {
        val cutoff = stamp() - 180 * DAY * 1000
        val m = LinkedHashMap<String, VaultEntry>()
        for ((id, u) in gone) if (u > cutoff) m[id] = VaultEntry(u, gone = true)
        for (c in contacts.value) m[c.id] = VaultEntry(c.s.updatedAt, c = c.s)
        val g = LinkedHashMap<String, GroupEntry>()
        for ((id, u) in goneGroups) if (u > cutoff) g[id] = GroupEntry(u, gone = true)
        for (x in groups.value) g[x.g.id] = GroupEntry(x.u, g = x.g)
        return VaultDoc(m, g, vaultExtra)
    }

    // newer entry wins; returns true when local state has entries the copy lacks
    private fun merge(doc: VaultDoc): Boolean {
        var list = contacts.value
        val g = gone.toMutableMap()
        var changed = false
        for ((id, e) in doc.contacts) {
            val local = list.find { it.id == id }
            if (e.u <= (local?.s?.updatedAt ?: g[id] ?: -1L)) continue
            changed = true
            val c = e.c
            if (e.gone == true || c == null) {
                g[id] = e.u
                if (local != null) {
                    list = list - local
                    clearChat(local.num)
                }
                continue
            }
            g.remove(id)
            if (local != null) {
                list = list.map { if (it.id == id) it.copy(s = c) else it }
            } else {
                list = list + Contact(c, nextNum(list))
                prompts.update { p -> p.filter { it.linkId != c.inLink } }
            }
        }
        if (changed) {
            gone = g
            setContacts(list)
        }
        vaultExtra = doc.extra
        var groupsChanged = false
        var added = false
        for ((id, e) in doc.groups) {
            val local = groupById(id)
            val eg = e.g
            if (e.gone == true || eg == null) {
                if (e.u <= (goneGroups[id] ?: -1L)) continue
                goneGroups = goneGroups + (id to e.u)
                if (local != null && e.u > local.u) dropGroup(local)
                groupsChanged = true
                continue
            }
            if ((goneGroups[id] ?: -1L) >= e.u) continue
            if (local == null) {
                goneGroups = goneGroups - id
                val num = nextNum()
                groups.update { it + LocalGroup(eg, num, e.u) }
                groupPrompts.update { p -> p.filter { it.g != id } }
                groupsChanged = true
                added = true
                continue
            }
            val merged = mergeGroup(local.g, eg, stamp())
            if (e.u > local.u) editGroup(id) { it.copy(u = e.u) }
            if (merged == local.g) continue
            val old = local.g.own.linkId
            editGroup(id) { it.copy(g = merged) }
            if (old != merged.own.linkId) app.scope.launch { runCatching { app.api.revokeLink(old) } }
            groupsChanged = true
        }
        if (groupsChanged) saveContacts()
        if (added) app.scope.launch { runCatching { loadMessages() } }
        val ours = doc()
        return ours.contacts.any { (id, e) -> (doc.contacts[id]?.u ?: -1L) < e.u } || ours.groups.any { (id, e) -> doc.groups[id] != e }
    }

    private suspend fun pullVault() {
        if (dataKey == null) return
        vaultLock.withLock {
            val r = app.api.vault()
            val doc = r.blob?.let { openAny(it) } ?: if (r.blob == null) VaultDoc() else null
            if (doc == null) return keyReplaced()
            vaultVersion = r.version
            if (merge(doc)) schedulePush()
        }
    }

    // data key replaced by another device (after "that wasn't me"); sign-in fetches the new one
    private fun keyReplaced() {
        setDataKey(null)
        signedOut("Your password was changed on another device. Sign in again.")
    }

    private fun signedOut(notice: String) {
        toast(notice)
        app.endSession()
    }

    // coalesces concurrent changes into one push
    private fun schedulePush() {
        if (pushQueued) return
        pushQueued = true
        app.scope.launch { runCatching { pushVault() }.onFailure { Log.w(TAG, "vault", it) } }
    }

    // merges into the latest copy, then compare-and-swap; retries when another device wins the race
    private suspend fun pushVault(force: Boolean = false) = vaultLock.withLock {
        pushQueued = false
        repeat(5) {
            val key = dataKey ?: return@withLock
            val r = app.api.vault()
            val doc = r.blob?.let { openAny(it) } ?: if (r.blob == null) VaultDoc() else null
            if (doc == null && !force) return@withLock keyReplaced()
            vaultVersion = r.version
            if (doc != null && !merge(doc) && !force) return@withLock
            try {
                vaultVersion = app.api.putVault(r.version, sealVault(key, doc()))
                prevKey = null
                return@withLock
            } catch (e: ApiException) {
                if (e.code != 409) throw e
            }
        }
    }

    // drops links removed by the peer or another device of this account while offline
    private suspend fun reconcile() {
        val links = app.api.myLinks()
        val have = links.map { it.id }.toSet()
        groupLinks = links.filter { it.kind == "group" }.map { it.id }.toSet()
        for (c in contacts.value.toList()) if (c.inLink !in have) forget(c, "${name(c.num)} is no longer a contact.")
        for (g in groups.value.toList()) if (g.g.own.linkId !in have) forgetGroup(g.g.id, "You are no longer in ${g.g.name.v}.")
    }

    private fun clearChat(num: Long) {
        val uid = me.value?.id
        messages.update { it - num }
        events.update { it - num }
        drafts.update { it - num }
        opened.update { it - num }
        seen.update { it - num }
        deviceAlerts.update { it - num }
        if (outbox.value.any { it.to == num }) setOutbox(outbox.value.filter { it.to != num })
        detach(num)
        if (unreachable.value == num) unreachable.value = null
        uid?.let {
            sp.edit {
                remove("seen.$it.$num")
                remove("notified.$it.$num")
            }
        }
        Notifications.cancelMessages(app, num)
    }

    private fun forget(c: Contact, notice: String? = null) {
        if (contacts.value.none { it.id == c.id }) return
        gone = gone + (c.id to stamp())
        peers.remove(c.id)
        setContacts(contacts.value.filter { it.id != c.id })
        clearChat(c.num)
        schedulePush()
        notice?.let(::toast)
    }

    // also asks the contact's server to revoke the inbound link; not enforceable
    suspend fun removeContact(num: Long) {
        val c = contact(num) ?: return
        runCatching { app.api.revokeLink(c.inLink) }
        app.scope.launch { runCatching { app.api.linkDrop(c) } }
        forget(c)
    }

    // --- own devices

    // one fetch at a time; a change announced mid-fetch triggers one more
    suspend fun refreshDevices() {
        devicesAgain = true
        devicesLock.withLock {
            if (!devicesAgain) return
            devicesAgain = false
            loadDevices()
        }
    }

    private suspend fun loadDevices() {
        val uid = me.value?.id ?: return
        val list = app.api.myDevices()
        ownDevices.value = list
        ownAt = System.currentTimeMillis()
        val self = app.sealed.device?.id
        list.find { it.id == self }?.let { enrolledAt = it.createdAt }
        val first = known.isEmpty()
        val t = serverNow()
        val k = known.toMutableMap()
        for (d in list) {
            val prev = k[d.id]
            if (prev == null && !first && d.id != self && d.createdAt >= enrolledAt && newDevices.value.none { it.id == d.id }) {
                val label = d.label.ifBlank { "Device" }
                newDevices.update { it + NewDevice(d.id, label) }
                if (!app.visible) Notifications.security(app, "New device signed in", label)
            }
            val keys = (prev?.keys.orEmpty() + d.keys + listOfNotNull(d.encPub)).distinct().takeLast(30)
            k[d.id] = KnownDevice(keys, d.signPub, d.label, t)
        }
        known = k.filterValues { it.seen >= t - config.value.messageTtlMax - DAY }
        newDevices.update { n -> n.filter { x -> list.any { it.id == x.id } } }
        knownSp.edit {
            putString("k.$uid", json.encodeToString(knownSerializer, known))
            putString("n.$uid", json.encodeToString(newSerializer, newDevices.value))
        }
    }

    fun dismissDevice(id: String) {
        newDevices.update { n -> n.filter { it.id != id } }
        me.value?.id?.let { uid -> knownSp.edit { putString("n.$uid", json.encodeToString(newSerializer, newDevices.value)) } }
    }

    suspend fun removeDevice(id: String) {
        app.api.removeDevice(id)
        if (id == app.sealed.device?.id) {
            app.sealed.forgetLocal(me.value!!.id)
            setup()
            return
        }
        dismissDevice(id)
        refreshDevices()
    }

    // removes an unrecognized device, then rotates the password and vault key so it cannot return
    suspend fun notMe(id: String) {
        removeDevice(id)
        secureNow.value = true
    }

    private suspend fun ownSealTo(fresh: Boolean = false): List<PublicDevice> {
        if (fresh || System.currentTimeMillis() - ownAt > 60_000) refreshDevices()
        return ownDevices.value.map { it.public() }.filter { app.sealed.certOk(it) }
    }

    private fun ownSigners(): Map<String, String> = known.mapValues { it.value.sign }

    // every stanza for this account must target a device the server lists (or listed recently), with a known key
    private suspend fun checkDevices(num: Long, m: Mail, ours: String) {
        if (m.createdAt < enrolledAt) return
        fun odd() = m.keys.filter { it.o == ours && known[it.d]?.keys?.contains(it.k) != true }
        if (odd().isEmpty()) return
        runCatching { refreshDevices() }
        if (odd().isEmpty() || num in deviceAlerts.value) return
        deviceAlerts.update { it + num }
        Notifications.security(app, "Check your devices", "A message ${if (isGroup(num)) "in" else "with"} ${name(num)} was also encrypted for a device that isn't yours.")
    }

    fun dismissAlert(num: Long) = deviceAlerts.update { it - num }

    // a session description must be signed by a device the contact's server lists for them
    suspend fun verifyCall(num: Long, callId: String, type: String, sdp: String, device: String?, sig: String?): Boolean {
        val c = contact(num) ?: return false
        if (device == null || sig == null) return false
        val pub = signersFor(c, device)[device] ?: return false
        return runCatching { Crypto.verify(Crypto.unb64(pub), Crypto.unb64(sig), Crypto.callSig(callId, type, sdp)) }.getOrDefault(false)
    }

    // --- pairing

    suspend fun showCode(): Pair<String, Long> {
        app.sealed.device ?: throw IOException(deviceError.value ?: "This device is not set up yet.")
        val p = app.api.createPairing()
        val user = me.value!!
        val code = Code(1, ownServer(), p.ticket, user.id.toString(), user.displayName)
        return code.encode() to p.expiresAt
    }

    // scanning side: opens a link for them, redeems their ticket, then sends a hello through it
    suspend fun enterCode(text: String): Pair<Long, List<String>> {
        app.sealed.device ?: throw IOException(deviceError.value ?: "This device is not set up yet.")
        val code = Code.decode(text) ?: throw IOException("That isn't a Caw code.")
        val own = ownServer()
        val user = me.value!!
        val account = user.id.toString()
        val server = code.s
        if (server == own && code.a == account) throw IOException("That's your own code.")
        if (contacts.value.any { it.s.account == code.a && normServer(it.server) == server }) throw IOException("Already a contact.")
        val mine = app.api.createLink(server, code.a)
        val out = try {
            app.api.redeem(server, code.t, own, account)
        } catch (e: IOException) {
            runCatching { app.api.revokeLink(mine.linkId) }
            throw e
        }
        val words = app.sealed.words(out.linkId)
        val s = Synced(
            UUID.randomUUID().toString(), code.n.ifBlank { "Contact" }, server = server, account = code.a, out = out,
            inLink = mine.linkId, status = "pending", createdAt = serverNow(), words = words, updatedAt = stamp(),
        )
        val c = Contact(s, nextNum())
        val card = Card(own, account, user.displayName, mine.linkId, mine.token)
        try {
            postControl(c, Plain("hello", card = card).toJson())
        } catch (e: IOException) {
            peers.remove(c.id)
            runCatching { app.api.revokeLink(mine.linkId) }
            app.scope.launch { runCatching { app.api.linkDrop(c) } }
            throw when (e) {
                is Gone -> IOException("This code has expired or was already used.")
                is Unreachable -> IOException("Can't reach that server.")
                else -> e
            }
        }
        // kept to verify their reply: a decline revokes the link their keys are read through
        val signers = peers[c.id]?.second.orEmpty().associate { it.id to it.signPub }
        setContacts(contacts.value + c.copy(signers = signers))
        schedulePush()
        return c.num to words
    }

    // placeholder contact for a pending pairing
    private fun remote(card: Card) = Contact(
        Synced("", card.name, server = card.server, account = card.account, out = LinkCred(card.linkId, card.token), inLink = "", status = "pending", createdAt = 0, updatedAt = 0),
        0,
    )

    private suspend fun maybeHello(m: Mail) {
        if (prompts.value.any { it.linkId == m.linkId }) return
        val card = app.sealed.openHello(m)?.let { h -> normServer(h.server)?.let { h.copy(server = it) } } ?: return
        val devices = runCatching { peerDevices(remote(card)) }.getOrNull() ?: return
        if (!app.sealed.signedBy(m, devices.associate { it.id to it.signPub })) return
        if (prompts.value.any { it.linkId == m.linkId } || contacts.value.any { it.inLink == m.linkId }) return
        prompts.update { it + Prompt(m.linkId, card, app.sealed.words(m.linkId)) }
    }

    suspend fun acceptPrompt(p: Prompt): Long {
        prompts.update { l -> l.filter { it.linkId != p.linkId } }
        val s = remote(p.card).s.copy(
            id = UUID.randomUUID().toString(), name = p.card.name.ifBlank { "Contact" }, inLink = p.linkId, status = "active",
            createdAt = serverNow(), words = p.words, updatedAt = stamp(),
        )
        val c = Contact(s, nextNum())
        setContacts(contacts.value + c)
        schedulePush()
        addEvent(c.num, "Contact added.")
        app.scope.launch { runCatching { postControl(c, Plain("accept").toJson()) }.onFailure { sendFailed(c, it) } }
        return c.num
    }

    suspend fun declinePrompt(p: Prompt) {
        prompts.update { l -> l.filter { it.linkId != p.linkId } }
        runCatching { postControl(remote(p.card), Plain("decline").toJson()) }
        runCatching { app.api.revokeLink(p.linkId) }
    }

    fun words(num: Long): List<String> = contact(num)?.s?.words.orEmpty()

    // --- mail

    private suspend fun signersFor(c: Contact, from: String): Map<String, String> {
        if (contact(c.num)?.signers?.containsKey(from) != true) {
            runCatching { peerDevices(c, fresh = true) }.onFailure {
                if (it is Gone && c.status == "pending") {
                    app.scope.launch { runCatching { app.api.revokeLink(c.inLink) } }
                    forget(c, "${name(c.num)} didn't accept.")
                }
            }
        }
        return contact(c.num)?.signers.orEmpty()
    }

    private suspend fun openFrom(c: Contact, m: Mail): JsonObject? {
        val plain = app.sealed.openMail(m, signersFor(c, m.fromDevice))
        if (plain != null) checkDevices(c.num, m, "to")
        return plain
    }

    suspend fun loadMessages(notify: Boolean = false) = loadLock.withLock {
        app.sealed.device ?: return@withLock
        val uid = me.value?.id ?: return@withLock
        val res = app.api.myMail()
        skew = res.now - System.currentTimeMillis() / 1000
        val grouped = HashMap<Long, MutableList<Message>>()
        val byGroup = LinkedHashMap<String, MutableList<Mail>>()
        for (m in res.mail) {
            val c = contacts.value.find { it.inLink == m.linkId }
            if (c == null) {
                val g = groups.value.find { it.g.own.linkId == m.linkId }
                if (g != null) byGroup.getOrPut(g.g.id) { mutableListOf() } += m
                else if (m.linkId !in groupLinks) maybeHello(m)
                continue
            }
            val raw = openFrom(c, m)
            if (contact(c.num) == null) {
                grouped.remove(c.num)
                continue
            }
            if (raw != null && isGroupContent(raw)) continue
            if (raw != null && raw.string("t") in CONTROLS) {
                control(c, raw, m, false)
                continue
            }
            grouped.getOrPut(c.num) { mutableListOf() } += Message(RawMessage(m.id, c.num, uid, m.createdAt, m.expiresAt), raw?.let(::plainOf))
        }
        for ((gid, list) in byGroup) {
            val num = groupById(gid)?.num ?: continue
            val shown = readGroup(gid, list)
            if (shown.isNotEmpty()) grouped.getOrPut(num) { mutableListOf() } += shown
        }
        // sent copies live on the contacts' servers; any device of this account may have sent them
        for (c in contacts.value.filter { it.status == "active" }) {
            try {
                grouped.getOrPut(c.num) { mutableListOf() } += sentCopies(c)
            } catch (e: Gone) {
                forget(c, "${name(c.num)} is no longer reachable and was removed from your contacts.")
            } catch (e: Exception) {
                messages.value[c.num].orEmpty().filter { it.raw.fromUser == uid }.forEach { grouped.getOrPut(c.num) { mutableListOf() } += it }
            }
        }
        val sorted = grouped.filterValues { it.isNotEmpty() }.mapValues { (_, l) -> l.sortedBy { it.raw.createdAt } }
        messages.value = sorted
        app.media.keepOnly(sorted.values.flatten().mapNotNull { it.plain?.blob }.toSet())
        if (!notify) return@withLock
        for ((other, list) in sorted) {
            val key = "notified.$uid.$other"
            val last = sp.getLong(key, 0)
            val fresh = list.filter { it.raw.fromUser == other && it.raw.createdAt > maxOf(last, seen.value[other] ?: 0) }
            if (fresh.isEmpty()) continue
            notifyMessage(other, fresh.last())
            sp.edit { putLong(key, fresh.last().raw.createdAt) }
        }
    }

    private suspend fun sentCopies(c: Contact): List<Message> {
        val uid = me.value?.id ?: return emptyList()
        val out = mutableListOf<Message>()
        for (m in app.api.linkMail(c).mail) {
            if (m.fromDevice !in known) runCatching { refreshDevices() }
            val raw = app.sealed.openMail(m, ownSigners())
            if (raw != null) checkDevices(c.num, m, "from")
            if (raw != null && raw.string("t") in CONTROLS) continue
            out += Message(RawMessage(m.id, uid, c.num, m.createdAt, m.expiresAt), raw?.let(::plainOf))
        }
        return out
    }

    // reloads sent copies after another device of this account sends
    private suspend fun loadSent(c: Contact) {
        val uid = me.value?.id ?: return
        val mine = try {
            sentCopies(c)
        } catch (e: Gone) {
            return forget(c, "${name(c.num)} is no longer reachable and was removed from your contacts.")
        }
        if (contact(c.num) == null) return
        messages.update { n ->
            val list = (n[c.num].orEmpty().filter { it.raw.fromUser != uid } + mine).sortedBy { it.raw.createdAt }
            if (list.isEmpty()) n - c.num else n + (c.num to list)
        }
    }

    private suspend fun takeMail(m: Mail) {
        ensureSetup()
        loadLock.withLock { readOne(m) }
    }

    private suspend fun readOne(m: Mail) {
        app.sealed.device ?: return
        val uid = me.value?.id ?: return
        val c = contacts.value.find { it.inLink == m.linkId }
        if (c == null) {
            val g = groups.value.find { it.g.own.linkId == m.linkId }
            if (g != null) return takeGroupMail(g.g.id, m)
            if (m.linkId in groupLinks) return
            return maybeHello(m)
        }
        if (messages.value[c.num]?.any { it.raw.id == m.id } == true) return
        val raw = openFrom(c, m)
        if (contact(c.num) == null) return
        if (raw != null && isGroupContent(raw)) return
        if (raw != null && raw.string("t") in CONTROLS) return control(c, raw, m, true)
        val msg = Message(RawMessage(m.id, c.num, uid, m.createdAt, m.expiresAt), raw?.let(::plainOf))
        messages.update { n -> n + (c.num to (n[c.num].orEmpty() + msg).sortedBy { it.raw.createdAt }) }
        if (openChat == c.num && app.visible) {
            markSeen(c.num)
            if (app.calls.active.value == null) Notifications.chime(app)
        } else {
            notifyMessage(c.num, msg)
        }
    }

    // seen by every device of this account; stamping from the message keeps their vault writes identical
    private fun control(c: Contact, raw: JsonObject, m: Mail, live: Boolean) {
        val who = name(c.num)
        val at = m.createdAt * 1000
        when (raw.string("t")) {
            "accept" -> if (c.status == "pending") {
                touch(c.num, at) { it.copy(status = "active") }
                addEvent(c.num, "Contact added.")
                if (live) toast("$who accepted.")
            }
            "decline" -> {
                app.scope.launch { runCatching { app.api.revokeLink(c.inLink) } }
                forget(c, "$who declined.")
            }
            "bye" -> {
                app.scope.launch { runCatching { app.api.revokeLink(c.inLink) } }
                forget(c, "$who is no longer a contact.")
            }
            "timer" -> {
                val ttl = plainOf(raw)?.ttl ?: return
                if (c.ttl == ttl) return
                touch(c.num, at) { it.copy(ttl = ttl) }
                addEvent(c.num, "$who set messages to expire after ${fmtTtl(ttl)}")
            }
            "group_invite" -> groupInvite(c, raw, m)
            "group_join" -> groupJoin(c, raw, m)
            "group_decline" -> groupDecline(c, raw)
        }
    }

    private fun notifyMessage(other: Long, n: Message) {
        val text = when {
            n.plain == null -> "New message"
            n.plain.t == "call" -> callText(n)
            isGroup(other) -> "${memberName(other, n.raw.sender)}: ${plainText(n.plain)}"
            else -> plainText(n.plain)
        }
        Notifications.message(app, other, name(other), text)
    }

    // on push wake the device keys may not be loaded yet
    suspend fun ensureSetup() = setupLock.withLock {
        if (app.sealed.device == null && me.value != null) setup()
    }

    suspend fun refreshKeys() = setupLock.withLock {
        val uid = me.value?.id ?: return@withLock
        if (app.sealed.device == null) setup() else if (app.sealed.keyDue(serverNow())) app.sealed.ensure(uid, config.value.messageTtlMax)
    }

    private fun expire() {
        val t = serverNow()
        clock.value = t
        if (outbox.value.isNotEmpty()) dropStale()
        for (c in contacts.value.filter { it.status == "pending" && it.s.createdAt + PENDING_SECS < t }) {
            app.scope.launch { runCatching { app.api.revokeLink(c.inLink) } }
            forget(c, "${name(c.num)} didn't accept in time.")
        }
        val before = messages.value
        val after = before.mapValues { (_, l) -> l.filter { it.raw.expiresAt > t } }.filterValues { it.isNotEmpty() }
        if (after.size == before.size && after.values.sumOf { it.size } == before.values.sumOf { it.size }) return
        messages.value = after
        before.values.flatten().filter { it.raw.expiresAt <= t }.forEach { n -> n.plain?.blob?.let(app.media::drop) }
        (before.keys - after.keys).forEach { id -> events.update { it - id } }
    }

    fun open(id: Long) {
        if (messages.value[id].isNullOrEmpty()) opened.update { it + id }
        markSeen(id)
        Notifications.cancelMessages(app, id)
    }

    fun markSeen(id: Long) {
        val last = messages.value[id]?.lastOrNull()?.raw?.createdAt ?: return
        if ((seen.value[id] ?: 0) >= last) return
        seen.update { it + (id to last) }
        sp.edit { putLong("seen.${me.value?.id}.$id", last) }
    }

    private fun addEvent(id: Long, text: String) {
        events.update { it + (id to (it[id].orEmpty() + CawEvent(stamp() / 1000.0, text))) }
    }

    // contact's devices as reported by their server; only those whose identity key signed their encryption key
    private suspend fun peerDevices(c: Contact, fresh: Boolean = false): List<PublicDevice> {
        if (c.id.isNotEmpty() && !fresh) peers[c.id]?.let { (at, d) -> if (System.currentTimeMillis() - at < 60_000) return d }
        val r = app.api.linkDevices(c)
        if (r.account != c.s.account) throw IOException("This contact's server answered for someone else.")
        val devices = r.devices.filter { app.sealed.certOk(it) }
        if (c.id.isNotEmpty()) {
            peers[c.id] = System.currentTimeMillis() to devices
            val cur = contact(c.num)
            val signers = cur?.signers.orEmpty() + devices.associate { it.id to it.signPub }
            if (cur != null && signers != cur.signers) local(c.num) { it.copy(signers = signers) }
        }
        return devices
    }

    // their server rejects (412) a message missing one of their devices; refetches the list and retries once
    private suspend fun deliver(c: Contact, ttl: Long, plain: JsonObject, id: String = UUID.randomUUID().toString(), blobs: List<String> = emptyList()): Mail? {
        var i = 0
        while (true) {
            val to = peerDevices(c, fresh = i > 0)
            if (to.isEmpty()) throw IOException("${name(c.num).takeIf { c.num != 0L } ?: c.name} has no devices that can receive messages right now.")
            val m: NewMail = app.sealed.sealMail(to, ownSealTo(), ttl, plain, id).copy(blobs = blobs)
            try {
                return app.api.linkSend(c, m)
            } catch (e: ApiException) {
                if (e.code == 409) return null
                if (e.code != 412 || i > 0) throw e
            }
            i++
        }
    }

    private suspend fun postControl(c: Contact, plain: JsonObject, ttl: Long = config.value.messageTtlDefault) {
        deliver(c, ttl, plain)
    }

    // network errors queue the message in the outbox; a gone contact is removed; other errors are thrown
    suspend fun send(to: Long, plain: Plain, blobs: List<String> = emptyList()) {
        app.sealed.device ?: throw IOException(deviceError.value ?: "This device is not set up yet.")
        group(to)?.let { return sendGroup(it, plain, blobs) }
        val c = contact(to)?.takeIf { it.status == "active" } ?: throw IOException("This contact is not available.")
        val o = Outgoing(UUID.randomUUID().toString(), to, plain, blobs, serverNow(), timerFor(to))
        try {
            post(o)
        } catch (e: Unreachable) {
            setOutbox(outbox.value + o)
            unreachableServer(c, e.retryAfter)
        } catch (e: Gone) {
            forget(c, "${name(to)} is no longer reachable and was removed from your contacts.")
        }
    }

    private suspend fun sendFailed(c: Contact, e: Throwable) {
        when (e) {
            is Gone -> forget(c, "${name(c.num)} is no longer reachable and was removed from your contacts.")
            is Unreachable -> unreachableServer(c, e.retryAfter)
            else -> toast(e.message ?: "Something went wrong.")
        }
    }

    // stable id across retries; a send that already landed returns a conflict instead of a duplicate
    private suspend fun post(o: Outgoing) {
        val c = contact(o.to) ?: return
        val stored = deliver(c, o.ttl, o.plain.toJson(), o.id, o.blobs)
        if (stored != null) {
            addMine(c.num, stored.id, stored.createdAt, stored.expiresAt, o.plain)
            app.scope.launch { runCatching { app.api.sent(c.id) } }
        }
        reachable(c)
    }

    private fun addMine(num: Long, id: String, created: Long, expires: Long, plain: Plain) {
        val uid = me.value?.id ?: return
        val msg = Message(RawMessage(id, uid, num, created, expires), plain)
        messages.update { n -> if (n[num]?.any { it.raw.id == id } == true) n else n + (num to (n[num].orEmpty() + msg).sortedBy { it.raw.createdAt }) }
    }

    private fun reachable(c: Contact) {
        synchronized(retry) { retry.remove(c.server) }
        val cur = contact(c.num) ?: return
        if (cur.unreachableSince != null || cur.askedAt != null) local(c.num) { it.copy(unreachableSince = null, askedAt = null) }
        if (unreachable.value == c.num) unreachable.value = null
    }

    // their server is down only if ours responds; prompts at most once a day per contact
    private suspend fun unreachableServer(c: Contact, retryAfter: Long = 0) {
        val step = synchronized(retry) { retry[c.server]?.second?.plus(1) ?: 0 }
        val wait = (maxOf(retryAfter, BACKOFF[minOf(step, BACKOFF.size - 1)]) * (1 + Math.random() * 0.2)).toLong()
        synchronized(retry) { retry[c.server] = (System.currentTimeMillis() + wait * 1000) to step }
        val ours = runCatching { app.api.config(app.prefs.server!!) }.isSuccess
        if (!ours) return
        val t = serverNow()
        val cur = contact(c.num) ?: return
        val ask = cur.askedAt == null || t - cur.askedAt > DAY
        local(c.num) { it.copy(unreachableSince = it.unreachableSince ?: t, askedAt = if (ask) t else it.askedAt) }
        if (ask) unreachable.value = c.num
    }

    // a member's server: retried after its own delay, without the remove-contact prompt
    private fun later(server: String, retryAfter: Long = 0) = synchronized(retry) {
        val r = retry[server]
        val step = if (retryAfter > 0) r?.second ?: 0 else if (r != null) r.second + 1 else 0
        val wait = (if (retryAfter > 0) retryAfter else BACKOFF[minOf(step, BACKOFF.size - 1)]) * (1 + Math.random() * 0.2)
        retry[server] = (System.currentTimeMillis() + (wait * 1000).toLong()) to step
    }

    private fun due(server: String) = synchronized(retry) { retry[server]?.first ?: 0L }

    fun keepTrying() {
        unreachable.value = null
    }

    private fun setOutbox(list: List<Outgoing>, save: Boolean = true) {
        outbox.value = list
        if (!save) return
        val uid = me.value?.id ?: return
        outSp.edit {
            if (list.isEmpty()) remove("o.$uid")
            else putString("o.$uid", Crypto.b64(Vault.seal(json.encodeToString(outSerializer, list).toByteArray())))
        }
    }

    private fun editOutbox(id: String, f: (Outgoing) -> Outgoing) {
        setOutbox(outbox.value.map { if (it.id == id) f(it) else it }, save = false)
    }

    private fun loadOutbox(uid: Long) {
        val stored = outSp.getString("o.$uid", null) ?: return
        val list = runCatching { json.decodeFromString(outSerializer, String(Vault.open(Crypto.unb64(stored)))) }.getOrDefault(emptyList())
        val have = outbox.value.map { it.id }.toSet()
        outbox.value = outbox.value + list.filter { it.id !in have && (it.copies != null || contact(it.to) != null) }
        dropStale()
    }

    // deliberate sign-out removes all account data from the device
    fun forgetLocal() {
        val uid = me.value?.id ?: return
        outSp.edit { remove("o.$uid") }
        contactSp.edit { remove("c.$uid") }
        keySp.edit { remove("k.$uid") }
        knownSp.edit {
            remove("k.$uid")
            remove("n.$uid")
        }
    }

    // drops queued messages whose timer has already elapsed
    private fun dropStale() {
        val t = serverNow()
        val stale = outbox.value.filter { it.at + it.ttl <= t }
        if (stale.isEmpty()) return
        setOutbox(outbox.value.filter { it.at + it.ttl > t })
        val shown = stale.count { it.plain.t != "group" }
        if (shown > 0) toast(if (shown == 1) "An unsent message expired." else "$shown unsent messages expired.")
    }

    // one server at a time, in order, only after its backoff elapses
    suspend fun flush() = flushLock.withLock {
        if (app.sealed.device == null) return@withLock
        val skip = HashSet<String>()
        for (o in outbox.value.toList()) {
            if (o.copies != null) {
                if (synchronized(posting) { !posting.add(o.id) }) continue
                val done = try {
                    postGroup(o.id, skip)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("Message not sent: ${e.message}")
                    true
                } finally {
                    synchronized(posting) { posting -= o.id }
                }
                setOutbox(if (done) outbox.value.filter { it.id != o.id } else outbox.value)
                continue
            }
            val c = contact(o.to)
            if (c == null) {
                setOutbox(outbox.value.filter { it.id != o.id })
                continue
            }
            val due = synchronized(retry) { retry[c.server]?.first ?: 0 }
            if (c.server in skip || due > System.currentTimeMillis()) {
                skip += c.server
                continue
            }
            try {
                post(o)
            } catch (e: Unreachable) {
                skip += c.server
                unreachableServer(c, e.retryAfter)
                continue
            } catch (e: Gone) {
                forget(c, "${name(c.num)} is no longer reachable and was removed from your contacts.")
            } catch (e: Exception) {
                toast("Message not sent: ${e.message}")
            }
            setOutbox(outbox.value.filter { it.id != o.id })
        }
    }

    fun pendingFor(id: Long, sent: List<Message>): List<Message> {
        val uid = me.value?.id ?: return emptyList()
        val ids = sent.map { it.raw.id }.toSet()
        return outbox.value.filter { it.to == id && it.id !in ids && it.plain.t != "group" }.map {
            Message(RawMessage(it.id, uid, it.to, it.at, it.at), it.plain, pending = true)
        }
    }

    // --- attachments

    private fun setAttachment(to: Long, a: Attachment?) {
        attachments.update { if (a == null) it - to else it + (to to a) }
    }

    suspend fun attach(to: Long, uri: Uri) {
        val mb = config.value.maxUploadMb
        setAttachment(to, Attachment("preparing", 0f))
        try {
            val p = app.media.prepare(uri, mb * 1024 * 1024) { progress ->
                if (attachments.value[to]?.status == "preparing") setAttachment(to, Attachment("preparing", progress))
            }
            if (attachments.value[to]?.status != "preparing") p.file.delete() else setAttachment(to, Attachment("ready", 1f, p))
        } catch (e: TooLarge) {
            setAttachment(to, null)
            throw IOException("File exceeds the $mb MB limit, even after compression.")
        } catch (e: Exception) {
            setAttachment(to, null)
            throw e
        }
    }

    fun detach(to: Long) {
        attachments.value[to]?.prepared?.file?.delete()
        setAttachment(to, null)
    }

    suspend fun sendMedia(to: Long, text: String) {
        val a = attachments.value[to] ?: return
        val p = a.prepared ?: return
        val g = group(to)
        val c = contact(to)
        if ((c == null && g == null) || a.status != "ready") return
        setAttachment(to, a.copy(status = "sending", progress = 0f))
        try {
            val (body, key) = withContext(Dispatchers.IO) { app.media.encrypt(p.file.readBytes()) }
            val progress = { x: Float -> setAttachment(to, a.copy(status = "sending", progress = x)) }
            val blob = if (g != null) app.api.ownUpload(g.g.own.linkId, body, progress) else app.api.linkUpload(c!!, body, progress)
            val plain = Plain(
                "media", text = text.ifBlank { null }, kind = p.kind, mime = p.mime, blob = blob, key = key,
                size = p.size, w = p.w, h = p.h, dur = p.dur, thumb = p.thumb,
            )
            send(to, plain, listOf(blob))
            app.media.keep(blob, key, p, if (g != null) null else c)
            setAttachment(to, null)
        } catch (e: Gone) {
            setAttachment(to, a)
            if (c == null) throw e
            forget(c, "${name(to)} is no longer reachable and was removed from your contacts.")
        } catch (e: Unreachable) {
            setAttachment(to, a)
            if (c == null) throw IOException("Can't reach your server right now.")
            unreachableServer(c, e.retryAfter)
            throw IOException("Can't reach ${name(to)}'s server right now.")
        } catch (e: Exception) {
            setAttachment(to, a)
            throw e
        }
    }

    suspend fun setTimer(to: Long, ttl: Long) {
        val g = group(to)
        if (g != null) {
            editGroup(g.g.id) { it.copy(g = it.g.copy(ttl = Reg(ttl, maxOf(stamp(), it.g.ttl.u + 1)))) }
            touchGroup(g.g.id)
            addEvent(to, "You set messages to expire after ${fmtTtl(ttl)}")
            return sendState(g.g.id)
        }
        val c = contact(to) ?: return
        touch(to) { it.copy(ttl = ttl) }
        addEvent(to, "You set messages to expire after ${fmtTtl(ttl)}")
        runCatching { postControl(c, Plain("timer", ttl = ttl).toJson()) }.onFailure { sendFailed(c, it) }
    }

    // --- groups; see docs/protocol.md, section 9

    fun group(num: Long) = groups.value.find { it.num == num }

    fun isGroup(num: Long) = groups.value.any { it.num == num }

    private fun groupById(id: String) = groups.value.find { it.g.id == id }

    private fun editGroup(id: String, f: (LocalGroup) -> LocalGroup) {
        groups.update { list -> list.map { if (it.g.id == id) f(it) else it } }
    }

    private fun ownServer() = app.prefs.server.orEmpty()

    // cards hold normalized addresses; contacts may hold an address as it was typed
    private fun sameAccount(k: Contact, c: GroupCard) = k.s.account == c.account && normServer(k.server) == c.server

    private fun cardName(c: GroupCard): String =
        contacts.value.find { sameAccount(it, c) }?.let { it.s.alias ?: it.name } ?: c.name

    fun memberName(num: Long, id: String?): String {
        val g = group(num) ?: return ""
        if (id == null) return ""
        if (id == g.g.me) return "You"
        val c = g.g.members[id]?.c
        return if (c != null) cardName(c) else g.names[id] ?: "Former member"
    }

    fun groupRoom(num: Long?): Int {
        val max = config.value.maxGroupMembers
        val g = num?.let(::group) ?: return max - 1
        val t = serverNow()
        return max - liveCards(g.g.members).size - g.g.invites.values.count { it.expires > t }
    }

    fun memberCount(num: Long): String {
        val n = group(num)?.let { liveCards(it.g.members).size } ?: 0
        return "$n member${if (n == 1) "" else "s"}"
    }

    fun groupMembers(num: Long): List<GroupMember> {
        val g = group(num) ?: return emptyList()
        return liveCards(g.g.members).map { c ->
            val self = c.id == g.g.me
            GroupMember(c.id, if (self) "You" else cardName(c), hostOf(c.server), contacts.value.any { sameAccount(it, c) }, self)
        }.sortedWith(compareByDescending<GroupMember> { it.me }.thenBy(Collator.getInstance()) { it.name })
    }

    fun invitable(num: Long): List<Long> {
        val g = group(num) ?: return emptyList()
        val cards = liveCards(g.g.members)
        val t = serverNow()
        return contacts.value
            .filter { c -> c.status == "active" && (g.g.invites[c.id]?.expires ?: 0L) <= t && cards.none { sameAccount(c, it) } }
            .map { it.num }
    }

    fun joined(num: Long) = group(num)?.let { it.g.inviter == null } ?: false

    private fun ownCard(id: String, link: String, token: String, gen: Long): GroupCard {
        val user = me.value ?: throw IOException("This device is not set up yet.")
        return GroupCard(id, ownServer(), user.id.toString(), clip(user.displayName, 64).ifEmpty { user.username }, link, token, gen)
    }

    private suspend fun newGroupLink(): Triple<String, ByteArray, String> {
        val secret = Crypto.random(32)
        val token = Crypto.groupToken(secret, 0)
        val r = try {
            app.api.createGroupLink(0, token)
        } catch (e: ApiException) {
            if (e.code == 400) throw IOException("Your server doesn't support groups yet.")
            throw e
        }
        groupLinks = groupLinks + r.linkId
        return Triple(r.linkId, secret, token)
    }

    private fun checkSize(n: Int) {
        val max = config.value.maxGroupMembers
        if (n > max) throw IOException("Groups on this server can have up to $max members.")
    }

    suspend fun createGroup(name: String, nums: List<Long>): Long {
        app.sealed.device ?: throw IOException(deviceError.value ?: "This device is not set up yet.")
        val n = clip(name.trim(), 64)
        if (n.isEmpty()) throw IOException("Enter a group name.")
        checkSize(nums.size + 1)
        val (link, secret, token) = newGroupLink()
        val self = UUID.randomUUID().toString()
        val t = stamp()
        val g = LocalGroup(
            Group(
                id = UUID.randomUUID().toString(),
                me = self,
                name = Reg(n, t),
                ttl = Reg(config.value.messageTtlDefault, t),
                members = mapOf(self to Member(t, ownCard(self, link, token, 0))),
                own = Own(link, Crypto.b64(secret), 0, 0),
                invites = emptyMap(),
                createdAt = serverNow(),
            ),
            nextNum(),
            t,
        )
        groups.update { it + g }
        touchGroup(g.g.id)
        addEvent(g.num, "You created the group.")
        invite(g.g.id, nums)
        return g.num
    }

    fun inviteToGroup(num: Long, nums: List<Long>) {
        val g = group(num) ?: return
        invite(g.g.id, nums)
    }

    private fun invite(gid: String, nums: List<Long>) {
        val g = groupById(gid) ?: return
        val t = serverNow()
        val pending = g.g.invites.values.count { it.expires > t }
        checkSize(liveCards(g.g.members).size + pending + nums.size)
        val open = invitable(g.num)
        for (num in nums) {
            val c = contact(num) ?: continue
            if (num !in open) continue
            val member = UUID.randomUUID().toString()
            editGroup(gid) { it.copy(g = it.g.copy(invites = it.g.invites + (c.id to Invite(member, t + 7 * DAY)))) }
            touchGroup(gid)
            addEvent(g.num, "You invited ${name(num)}.")
            val cur = groupById(gid) ?: return
            val plain = buildJsonObject {
                put("t", "group_invite")
                put("g", gid)
                put("name", cur.g.name.v)
                put("members", JsonArray(liveCards(cur.g.members).map { JsonPrimitive(it.name) }))
                put("inviter", cur.g.me)
                put("member", member)
                put("text", "${me.value?.displayName.orEmpty()} invited you to a group. Update Caw to join.")
            }
            app.scope.launch { runCatching { postControl(c, plain, config.value.messageTtlMax) }.onFailure { sendFailed(c, it) } }
        }
    }

    suspend fun acceptGroup(p: GroupPrompt): Long {
        val c = contacts.value.find { it.id == p.contact } ?: throw IOException("This contact is not available.")
        checkSize(p.members.size + 1)
        val (link, secret, token) = newGroupLink()
        groupPrompts.update { l -> l.filter { it.g != p.g } }
        val t = stamp()
        val card = ownCard(p.member, link, token, 0)
        val g = LocalGroup(
            Group(
                id = p.g,
                me = p.member,
                name = Reg(p.name, 0),
                ttl = Reg(config.value.messageTtlDefault, 0),
                members = mapOf(p.member to Member(t, card)),
                own = Own(link, Crypto.b64(secret), 0, 0),
                invites = emptyMap(),
                inviter = Inviter(c.id, p.inviter),
                createdAt = serverNow(),
            ),
            nextNum(),
            t,
        )
        groups.update { it + g }
        touchGroup(p.g)
        addEvent(g.num, "Waiting for ${name(c.num)} to add you.")
        val plain = buildJsonObject {
            put("t", "group_join")
            put("g", p.g)
            put("card", json.encodeToJsonElement(GroupCard.serializer(), card))
        }
        app.scope.launch { runCatching { postControl(c, plain, config.value.messageTtlMax) }.onFailure { sendFailed(c, it) } }
        return g.num
    }

    suspend fun declineGroup(p: GroupPrompt) {
        groupPrompts.update { l -> l.filter { it.g != p.g } }
        goneGroups = goneGroups + (p.g to stamp())
        saveContacts()
        schedulePush()
        val c = contacts.value.find { it.id == p.contact } ?: return
        runCatching { postControl(c, buildJsonObject { put("t", "group_decline"); put("g", p.g) }, config.value.messageTtlMax) }
    }

    suspend fun renameGroup(num: Long, name: String) {
        val g = group(num) ?: return
        val n = clip(name.trim(), 64)
        if (n.isEmpty() || n == g.g.name.v) return
        editGroup(g.g.id) { it.copy(g = it.g.copy(name = Reg(n, maxOf(stamp(), it.g.name.u + 1)))) }
        touchGroup(g.g.id)
        addEvent(num, "You renamed the group to $n.")
        sendState(g.g.id)
    }

    suspend fun removeMember(num: Long, id: String) {
        val g = group(num) ?: return
        val e = g.g.members[id] ?: return
        val removed = e.c ?: return
        if (id == g.g.me) return
        cacheNames(g.g.id)
        val gone = Member(maxOf(stamp(), e.u + 1), gone = true)
        val t = serverNow()
        editGroup(g.g.id) { it.copy(g = it.g.copy(members = it.g.members + (id to gone), left = it.g.left.orEmpty() + (id to t))) }
        touchGroup(g.g.id)
        addEvent(num, "You removed ${cardName(removed)}.")
        val cur = groupById(g.g.id) ?: return
        val notice = buildJsonObject {
            put("t", "group")
            put("g", cur.g.id)
            put("m", cur.g.me)
            put("members", json.encodeToJsonElement(MEMBERS, mapOf(id to gone)))
        }
        queueGroup(cur, notice, listOf(removed), config.value.messageTtlMax)
        if (!rotate(g.g.id)) sendState(g.g.id)
    }

    suspend fun leaveGroup(num: Long) {
        val g = group(num) ?: return
        val mine = g.g.members[g.g.me]
        editGroup(g.g.id) { it.copy(g = it.g.copy(members = it.g.members + (it.g.me to Member(maxOf(stamp(), (mine?.u ?: 0L) + 1), gone = true)))) }
        val cur = groupById(g.g.id) ?: return
        runCatching { queueGroup(cur, stateOf(cur.g), liveCards(cur.g.members), config.value.messageTtlMax, keep = true) }
        runCatching { app.api.revokeLink(cur.g.own.linkId) }
        forgetGroup(g.g.id)
    }

    private fun stateOf(g: Group) = buildJsonObject {
        put("t", "group")
        put("g", g.id)
        put("m", g.me)
        put("name", json.encodeToJsonElement(NAME, g.name))
        put("ttl", json.encodeToJsonElement(TTL, g.ttl))
        put("members", json.encodeToJsonElement(MEMBERS, g.members))
    }

    private suspend fun sendState(gid: String, trigger: String? = null, to: List<GroupCard>? = null) {
        val g = groupById(gid) ?: return
        queueGroup(g, stateOf(g.g), to ?: liveCards(g.g.members).filter { it.id != g.g.me }, config.value.messageTtlMax, trigger)
    }

    private suspend fun sendGroup(g: LocalGroup, plain: Plain, blobs: List<String>) {
        if (g.g.inviter != null) throw IOException("You can send messages once you are added to the group.")
        val p = plain.copy(g = g.g.id, m = g.g.me, sv = Crypto.membershipDigest(digestList(g.g.members)))
        val cards = liveCards(g.g.members).sortedByDescending { it.id == g.g.me }
        queueGroup(g, null, cards, g.g.ttl.v, plain = p, blobs = blobs)
    }

    // cards travel with the entry when the group may be gone by delivery
    private suspend fun queueGroup(
        g: LocalGroup,
        body: JsonObject?,
        cards: List<GroupCard>,
        ttl: Long,
        trigger: String? = null,
        keep: Boolean = false,
        plain: Plain? = null,
        blobs: List<String> = emptyList(),
    ) {
        val id = UUID.randomUUID().toString()
        val secret = Crypto.unb64(g.g.own.secret)
        val copies = LinkedHashMap<String, String>()
        for (c in cards) {
            copies[c.id] = when {
                c.id == g.g.me -> id
                trigger != null -> Crypto.groupMessageId(secret, trigger, c.id)
                else -> UUID.randomUUID().toString()
            }
        }
        val carried = cards.filter { keep || g.g.members[it.id]?.c == null }
        val o = Outgoing(
            id, g.num, plain ?: Plain("group", g = g.g.id, m = g.g.me), blobs, serverNow(), ttl,
            group = g.g.id, copies = copies, cards = carried.takeIf { it.isNotEmpty() }?.associateBy { it.id }, body = body,
        )
        synchronized(posting) { posting += id }
        try {
            setOutbox(outbox.value + o)
            val done = try {
                postGroup(id, HashSet())
            } catch (e: CancellationException) {
                // the caller went away; flush delivers the remaining copies
                throw e
            } catch (e: Exception) {
                setOutbox(outbox.value.filter { it.id != id })
                throw e
            }
            setOutbox(if (done) outbox.value.filter { it.id != id } else outbox.value)
        } finally {
            synchronized(posting) { posting -= id }
        }
    }

    private suspend fun postGroup(id: String, skip: MutableSet<String>): Boolean {
        val start = outbox.value.find { it.id == id } ?: return true
        for ((mid, postId) in start.copies.orEmpty()) {
            val o = outbox.value.find { it.id == id } ?: return true
            if (mid !in o.copies.orEmpty()) continue
            val g = o.group?.let(::groupById)
            val card = g?.g?.members?.get(mid)?.c ?: o.cards?.get(mid)
            if (card == null) {
                editOutbox(id) { it.copy(copies = it.copies.orEmpty() - mid) }
                continue
            }
            val self = g != null && mid == g.g.me
            if (!self && (card.server in skip || due(card.server) > System.currentTimeMillis())) {
                skip += card.server
                continue
            }
            if (o.held?.get(mid) == card.token) continue
            try {
                val stored = deliverCopy(o, card, postId, if (self) g else null)
                editOutbox(id) { it.copy(copies = it.copies.orEmpty() - mid, held = it.held?.minus(mid)) }
                if (self && stored != null) addMine(g!!.num, stored.id, stored.createdAt, stored.expiresAt, o.plain)
                if (!self) synchronized(retry) { retry.remove(card.server) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unreachable) {
                skip += card.server
                later(card.server, e.retryAfter)
            } catch (e: Exception) {
                when {
                    e is Gone || (e is ApiException && e.code == 401) -> editOutbox(id) { it.copy(held = it.held.orEmpty() + (mid to card.token)) }
                    self -> throw e
                    else -> editOutbox(id) { it.copy(copies = it.copies.orEmpty() - mid) }
                }
            }
        }
        return outbox.value.find { it.id == id }?.copies.isNullOrEmpty()
    }

    private suspend fun deliverCopy(o: Outgoing, card: GroupCard, id: String, own: LocalGroup?): Mail? {
        val plain = o.body ?: o.plain.toJson()
        var i = 0
        while (true) {
            val devices = if (own != null) ownSealTo(i > 0) else memberDevices(o.group!!, card, i > 0)
            if (devices.isEmpty() && own == null) throw IOException("${card.name} has no devices that can receive messages right now.")
            val m = app.sealed.sealCopy(devices, o.ttl, plain, id, own != null)
            try {
                if (own == null) return app.api.linkSend(remoteOf(card), m)
                return try {
                    app.api.ownGroupMail(own.g.own.linkId, m.copy(blobs = o.blobs))
                } catch (e: ApiException) {
                    throw e
                } catch (e: IOException) {
                    throw Unreachable()
                }
            } catch (e: ApiException) {
                if (e.code == 409) return null
                if (e.code != 412 || i > 0) throw e
            }
            i++
        }
    }

    // signing keys are kept to read a former member's mail
    private suspend fun memberDevices(gid: String, c: GroupCard, fresh: Boolean = false): List<PublicDevice> {
        val key = "$gid/${c.id}"
        val hit = synchronized(memberLists) { memberLists[key] }
        if (hit != null && !fresh && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        val r = app.api.linkDevices(remoteOf(c))
        if (r.account != c.account) throw IOException("${c.name}'s server answered for someone else.")
        val devices = r.devices.filter { app.sealed.certOk(it) }
        synchronized(memberLists) { memberLists[key] = System.currentTimeMillis() to devices }
        val g = groupById(gid) ?: return devices
        val seen = devices.associate { it.id to it.signPub }
        val cur = g.signers[c.id].orEmpty()
        if (cur + seen != cur) {
            editGroup(gid) { it.copy(signers = it.signers + (c.id to (it.signers[c.id].orEmpty() + seen))) }
            saveContacts()
        }
        return devices
    }

    private suspend fun groupSigners(gid: String, sender: String, m: Mail): Map<String, String>? {
        val g = groupById(gid) ?: return null
        if (sender == g.g.me) {
            if (m.fromDevice !in known) runCatching { refreshDevices() }
            return ownSigners()
        }
        val e = g.g.members[sender]
        val card = e?.c
        if (card != null) {
            if (g.signers[sender]?.containsKey(m.fromDevice) != true) runCatching { memberDevices(gid, card, fresh = true) }
            return groupById(gid)?.signers?.get(sender).orEmpty()
        }
        val inv = g.g.inviter
        if (inv != null && inv.member == sender) {
            val c = contacts.value.find { it.id == inv.contact } ?: return null
            return signersFor(c, m.fromDevice)
        }
        // a former member: only mail its server received before this account learned it left
        val at = g.g.left?.get(sender)
        return if (e != null && at != null && m.createdAt <= at) g.signers[sender] else null
    }

    // decrypted first, since the sender is named inside; nothing is used before the signature checks out
    private suspend fun openGroup(gid: String, m: Mail): Opened? {
        val raw = app.sealed.openSealed(m) ?: return null
        val t = raw.string("t")
        if ((t != "text" && t != "media" && t != "group") || raw.string("g") != gid) return null
        val sender = raw.string("m") ?: return null
        val signers = groupSigners(gid, sender, m) ?: return null
        if (!app.sealed.signedBy(m, signers)) return null
        groupById(gid)?.let { checkDevices(it.num, m, "to") }
        return Opened(raw, sender)
    }

    private fun groupMessage(g: LocalGroup, m: Mail, plain: Plain?, sender: String): Message {
        val uid = me.value?.id ?: 0L
        val mine = sender == g.g.me
        val raw = RawMessage(m.id, if (mine) uid else g.num, if (mine) g.num else uid, m.createdAt, m.expiresAt, if (mine) null else sender)
        return Message(raw, plain)
    }

    // a second pass reads mail from members that states in the first pass added
    private suspend fun readGroup(gid: String, list: List<Mail>): List<Message> {
        val shown = mutableListOf<Pair<Mail, Opened>>()
        val order = list.withIndex().associate { (i, m) -> m.id to i }
        var rest = list
        var pass = 0
        while (pass < 2 && rest.isNotEmpty()) {
            val later = mutableListOf<Mail>()
            for (m in rest) {
                val r = openGroup(gid, m)
                when {
                    r == null -> later += m
                    r.raw.string("t") == "group" -> applyGroupState(gid, r.raw, m, r.sender)
                    else -> shown += m to r
                }
                if (groupById(gid) == null) return emptyList()
            }
            rest = later
            pass++
        }
        rotate(gid)
        val g = groupById(gid) ?: return emptyList()
        return shown
            .sortedWith(compareBy<Pair<Mail, Opened>> { it.first.createdAt }.thenBy { order[it.first.id] })
            .map { (m, r) -> groupMessage(g, m, r.plain, r.sender) }
    }

    private suspend fun takeGroupMail(gid: String, m: Mail) {
        val num = groupById(gid)?.num ?: return
        if (messages.value[num]?.any { it.raw.id == m.id } == true) return
        val r = openGroup(gid, m) ?: return
        if (r.raw.string("t") == "group") return applyGroupState(gid, r.raw, m, r.sender)
        val g = groupById(gid) ?: return
        val msg = groupMessage(g, m, r.plain, r.sender)
        messages.update { n -> if (n[num]?.any { it.raw.id == m.id } == true) n else n + (num to (n[num].orEmpty() + msg).sortedBy { it.raw.createdAt }) }
        if (r.sender == g.g.me) return
        if (differs(r.raw["sv"], Crypto.membershipDigest(digestList(g.g.members)))) syncTo(gid, r.sender, m.id)
        if (openChat == num && app.visible) {
            markSeen(num)
            if (app.calls.active.value == null) Notifications.chime(app)
        } else {
            notifyMessage(num, msg)
        }
    }

    private fun syncTo(gid: String, member: String, mail: String) {
        val key = "$gid/$member"
        val now = System.currentTimeMillis()
        synchronized(synced) {
            if (now - (synced[key] ?: 0L) < 60_000) return
            synced[key] = now
        }
        val c = groupById(gid)?.g?.members?.get(member)?.c ?: return
        app.scope.launch { runCatching { sendState(gid, "sync:$mail", listOf(c)) } }
    }

    private suspend fun applyGroupState(gid: String, p: JsonObject, m: Mail, sender: String) {
        val start = groupById(gid) ?: return
        if (sender == start.g.me) return
        val inviter = start.g.inviter
        val bootstrap = inviter != null && sender == inviter.member
        val before = goneCount(start.g.members)
        cacheNames(gid)
        val g = groupById(gid) ?: return
        val r = applyState(g.g.state(), p, sender, stamp(), bootstrap) ?: return
        if (bootstrap) {
            val mine = r.state.members[g.g.me]?.c
            val theirs = r.state.members[sender]?.c
            val c = contacts.value.find { it.id == inviter?.contact }
            val ok = c != null && mine != null && mine.server == ownServer() && mine.account == me.value?.id?.toString() &&
                theirs != null && sameAccount(c, theirs)
            if (!ok) return
        } else if (r.state == g.g.state()) {
            return
        }
        val who = memberName(g.num, sender)
        val t = serverNow()
        editGroup(gid) {
            val seen = it.g.left.orEmpty()
            val left = if (r.gone.isEmpty()) it.g.left else seen + r.gone.associateWith { x -> minOf(seen[x] ?: t, t) }
            val next = it.g.copy(name = r.state.name, ttl = r.state.ttl, members = r.state.members, left = left)
            it.copy(g = if (bootstrap) next.copy(inviter = null, own = next.own.copy(base = goneCount(next.members).toLong())) else next)
        }
        if (bootstrap) addEvent(g.num, "You joined the group.")
        cacheNames(gid)
        touchGroup(gid, m.createdAt * 1000)
        if (!bootstrap) for (x in r.added) addEvent(g.num, "$who added ${memberName(g.num, x)}.")
        for (x in r.gone) addEvent(g.num, if (x == sender) "$who left." else "$who removed ${memberName(g.num, x)}.")
        val cur = groupById(gid) ?: return
        if (r.renamed && !bootstrap) addEvent(g.num, "$who renamed the group to ${cur.g.name.v}.")
        if (r.timer && !bootstrap) addEvent(g.num, "$who set messages to expire after ${fmtTtl(cur.g.ttl.v)}.")
        if (cur.g.members[cur.g.me]?.c == null) return leftGroup(gid, "$who removed you from ${cur.g.name.v}.")
        if (goneCount(cur.g.members) > before) rotate(gid)
    }

    // every device of the account derives the same token; true when the full state went out
    private suspend fun rotate(gid: String): Boolean {
        val g = groupById(gid) ?: return false
        val gen = ownGen(g.g)
        val mine = g.g.members[g.g.me] ?: return false
        val card = mine.c ?: return false
        if (card.gen >= gen) return false
        val token = Crypto.groupToken(Crypto.unb64(g.g.own.secret), gen)
        try {
            app.api.setGroupToken(g.g.own.linkId, gen, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "group token", e)
            return false
        }
        val u = maxOf(stamp(), mine.u + 1)
        editGroup(gid) {
            it.copy(g = it.g.copy(members = it.g.members + (it.g.me to Member(u, card.copy(token = token, gen = gen))), own = it.g.own.copy(gen = maxOf(it.g.own.gen, gen))))
        }
        touchGroup(gid)
        sendState(gid, "gen:$gen")
        return true
    }

    private fun groupInvite(c: Contact, p: JsonObject, m: Mail) {
        val gid = p.string("g")
        val member = p.string("member")
        val inviter = p.string("inviter")
        val title = p.string("name")
        val members = p["members"] as? JsonArray
        if (gid == null || member == null || inviter == null || !isUuid(gid) || !isUuid(member) || !isUuid(inviter) || title == null || members == null) return
        if (groups.value.any { it.g.id == gid } || groupPrompts.value.any { it.g == gid }) return
        if ((goneGroups[gid] ?: -1L) >= m.createdAt * 1000 || m.createdAt + 7 * DAY < serverNow()) return
        val shown = clip(title, 64).ifEmpty { "Group" }
        val names = members.mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x.isString }?.content }.take(50)
        val from = name(c.num)
        groupPrompts.update { it + GroupPrompt(c.id, from, gid, shown, names, inviter, member) }
        if (!app.visible) Notifications.invitation(app, "Group invitation", "$from invited you to $shown.")
    }

    // any of the inviting account's devices adds the member; the same message gives every device the same result
    private fun groupJoin(c: Contact, p: JsonObject, m: Mail) {
        val gid = p.string("g") ?: return
        val g = groupById(gid) ?: return
        val inv = g.g.invites[c.id] ?: return
        if (inv.expires < serverNow()) return
        val card = validCard(p["card"], inv.member) ?: return
        if (!sameAccount(c, card)) return
        editGroup(gid) { it.copy(g = it.g.copy(invites = it.g.invites - c.id, members = it.g.members + (inv.member to Member(m.createdAt * 1000, card)))) }
        cacheNames(gid)
        touchGroup(gid, m.createdAt * 1000)
        addEvent(g.num, "${name(c.num)} joined.")
        app.scope.launch { runCatching { sendState(gid, "join:${m.id}") }.onFailure { Log.w(TAG, "group join", it) } }
    }

    private fun groupDecline(c: Contact, p: JsonObject) {
        val gid = p.string("g") ?: return
        val g = groupById(gid) ?: return
        if (c.id !in g.g.invites) return
        editGroup(gid) { it.copy(g = it.g.copy(invites = it.g.invites - c.id)) }
        touchGroup(gid)
        addEvent(g.num, "${name(c.num)} declined.")
    }

    private fun cacheNames(gid: String) {
        val g = groupById(gid) ?: return
        val names = g.names + liveCards(g.g.members).associate { it.id to cardName(it) }
        if (names != g.names) editGroup(gid) { it.copy(names = names) }
    }

    private fun touchGroup(gid: String, at: Long = stamp()) {
        editGroup(gid) { it.copy(u = maxOf(at, it.u + 1)) }
        saveContacts()
        schedulePush()
    }

    private fun forgetGroup(gid: String, notice: String? = null) {
        val g = groupById(gid) ?: return
        goneGroups = goneGroups + (gid to maxOf(stamp(), g.u + 1))
        dropGroup(g)
        schedulePush()
        notice?.let(::toast)
    }

    private fun leftGroup(gid: String, notice: String) {
        val g = groupById(gid) ?: return
        app.scope.launch { runCatching { app.api.revokeLink(g.g.own.linkId) } }
        forgetGroup(gid, notice)
    }

    private fun dropGroup(g: LocalGroup) {
        val num = g.num
        groups.update { list -> list.filter { it.g.id != g.g.id } }
        messages.update { it - num }
        events.update { it - num }
        drafts.update { it - num }
        opened.update { it - num }
        seen.update { it - num }
        deviceAlerts.update { it - num }
        val keep = outbox.value.filter { it.group != g.g.id || it.cards != null }
        if (keep.size != outbox.value.size) setOutbox(keep)
        detach(num)
        synchronized(memberLists) { memberLists.keys.removeAll { it.startsWith("${g.g.id}/") } }
        me.value?.id?.let {
            sp.edit {
                remove("seen.$it.$num")
                remove("notified.$it.$num")
            }
        }
        saveContacts()
        Notifications.cancelMessages(app, num)
    }

    // a former member's files went with its group link
    fun mediaGone(num: Long, n: Message): Boolean {
        val g = group(num) ?: return false
        val sender = n.raw.sender ?: return false
        return g.g.members[sender]?.c == null
    }

    fun mediaFrom(num: Long, n: Message): Remote? {
        val g = group(num) ?: return if (n.raw.fromUser == me.value?.id) contact(num) else null
        if (n.raw.fromUser == me.value?.id) return null
        val sender = n.raw.sender ?: return null
        return g.g.members[sender]?.c?.let(::remoteOf)
    }

    fun callEnded(peer: Peer, secs: Long?, reason: String) {
        val outcome = when {
            secs != null -> "answered"
            reason == "declined" -> "declined"
            else -> "missed"
        }
        app.scope.launch {
            runCatching { send(peer.id, Plain("call", outcome = outcome, secs = secs)) }
                .onFailure { Log.w(TAG, "call msg", it) }
        }
    }

    fun onPushCall() = app.scope.launch {
        runCatching { ensureSetup() }
    }

    // woken by push with the app closed
    fun onPushMessage() = app.scope.launch {
        if (me.value == null) return@launch
        runCatching { refreshKeys() }
        runCatching { loadMessages(notify = true) }.onFailure { Log.w(TAG, "push load", it) }
    }

    fun idleCheck() = app.scope.launch(Dispatchers.Main) {
        if (!app.visible && !app.calls.busy && app.prefs.pushEndpoint != null) app.socket.stop()
    }

    fun clear() {
        me.value = null
        contacts.value = emptyList()
        groups.value = emptyList()
        messages.value = emptyMap()
        events.value = emptyMap()
        opened.value = emptySet()
        favorites.value = emptySet()
        aliases.value = emptyMap()
        drafts.value = emptyMap()
        attachments.value = emptyMap()
        outbox.value = emptyList()
        prompts.value = emptyList()
        groupPrompts.value = emptyList()
        unreachable.value = null
        vaultLocked.value = false
        contactsLoaded.value = false
        ownDevices.value = emptyList()
        newDevices.value = emptyList()
        deviceAlerts.value = emptySet()
        secureNow.value = false
        dataKey = null
        prevKey = null
        pending = null
        signInSecret = null
        vaultVersion = 0
        gone = emptyMap()
        goneGroups = emptyMap()
        groupLinks = emptySet()
        vaultExtra = emptyMap()
        known = emptyMap()
        peers.clear()
        synchronized(memberLists) { memberLists.clear() }
        synchronized(synced) { synced.clear() }
        synchronized(posting) { posting.clear() }
        synchronized(retry) { retry.clear() }
        app.media.clear()
        openChat = null
    }

    fun onEvent(e: JsonObject) {
        when (e.str("type")) {
            "hello" -> {
                app.scope.launch {
                    runCatching { loadMessages() }
                    runCatching { pullVault() }
                    runCatching { refreshDevices() }
                    runCatching { flush() }
                }
                app.calls.handle(e)
            }
            "mail" -> {
                val m = runCatching { json.decodeFromJsonElement(Mail.serializer(), e) }.getOrNull() ?: return
                app.scope.launch { runCatching { takeMail(m) } }
            }
            "sent" -> {
                val c = contacts.value.find { it.id == e.str("contact") } ?: return
                app.scope.launch { runCatching { loadSent(c) } }
            }
            "vault" -> if ((e.long("version") ?: 0) > vaultVersion) app.scope.launch { runCatching { pullVault() } }
            "devices" -> app.scope.launch { runCatching { refreshDevices() } }
            "link_removed" -> {
                val c = contacts.value.find { it.inLink == e.str("link_id") } ?: return
                app.scope.launch { runCatching { app.api.linkDrop(c) } }
                forget(c, "${name(c.num)} is no longer a contact.")
            }
            "link_revoked" -> {
                val id = e.str("link_id")
                prompts.update { p -> p.filter { it.linkId != id } }
                contacts.value.find { it.inLink == id }?.let { forget(it) }
                groups.value.find { it.g.own.linkId == id }?.let { forgetGroup(it.g.id) }
            }
            "error" -> if (e["call_id"] == null) Log.w(TAG, "server: ${e.str("message")}") else app.calls.handle(e)
            "pong" -> {}
            else -> app.calls.handle(e)
        }
    }

    companion object {
        private const val TAG = "caw.repo"
        private const val DAY = 86400L
        private val VAULT_AAD = "caw-vault-v1".toByteArray()
        // unanswered pairings expire after this
        private const val PENDING_SECS = 1800L
        // deliberately slow: one attempt per step for an unreachable server
        private val BACKOFF = longArrayOf(60, 300, 900, 1800, 3600, 7200, 14400, 21600)
        private val NAME = Reg.serializer(String.serializer())
        private val TTL = Reg.serializer(Long.serializer())
        private val MEMBERS = MapSerializer(String.serializer(), Member.serializer())
    }
}

fun fmtTtl(secs: Long): String = when {
    secs % 86400 == 0L -> "${secs / 86400} day${if (secs == 86400L) "" else "s"}"
    secs % 3600 == 0L -> "${secs / 3600} hour${if (secs == 3600L) "" else "s"}"
    else -> "${secs / 60} min"
}

fun fmtLeft(secs: Long): String = when {
    secs >= 86400 -> "${secs / 86400}d left"
    secs >= 3600 -> "${secs / 3600}h left"
    else -> "${maxOf(1, (secs + 59) / 60)}m left"
}
