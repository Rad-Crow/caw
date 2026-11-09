package com.radcrow.caw

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

// see docs/protocol.md, section 9

@Serializable
data class GroupCard(
    val id: String,
    val server: String,
    val account: String,
    val name: String,
    @SerialName("link_id") val linkId: String,
    val token: String,
    val gen: Long,
)

@Serializable
data class Member(val u: Long, val c: GroupCard? = null, val gone: Boolean? = null)

@Serializable
data class Reg<T>(val v: T, val u: Long)

@Serializable
data class GroupState(val name: Reg<String>, val ttl: Reg<Long>, val members: Map<String, Member>)

@Serializable
data class Own(@SerialName("link_id") val linkId: String, val secret: String, val base: Long, val gen: Long)

@Serializable
data class Invite(val member: String, val expires: Long)

@Serializable
data class Inviter(val contact: String, val member: String)

@Serializable
data class Group(
    val id: String,
    val me: String,
    val name: Reg<String>,
    val ttl: Reg<Long>,
    val members: Map<String, Member>,
    val own: Own,
    val invites: Map<String, Invite>,
    val inviter: Inviter? = null,
    // when this account learned each member left, in its server's time; their earlier mail stays readable
    val left: Map<String, Long>? = null,
    @SerialName("created_at") val createdAt: Long,
) {
    fun state() = GroupState(name, ttl, members)
}

data class Applied(val state: GroupState, val added: List<String>, val gone: List<String>, val renamed: Boolean, val timer: Boolean)

const val MAX_ENTRIES = 256
private const val AHEAD_MS = 86_400_000L
private const val MAX_SAFE = 9007199254740991.0
private val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val TOKEN_RE = Regex("[A-Za-z0-9_-]{43}")
// javascript's \s also covers these
private val SERVER_RE = Regex("https?://[^/\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

fun liveCards(members: Map<String, Member>): List<GroupCard> = members.values.mapNotNull { it.c }

fun goneCount(members: Map<String, Member>): Int = members.values.count { it.c == null }

fun ownGen(g: Group): Long = maxOf(0L, goneCount(g.members) - g.own.base)

fun isUuid(v: String?): Boolean = v != null && UUID_RE.matches(v)

// untrusted values are read as javascript reads them, so both clients accept the same states
private fun num(v: JsonElement?): Double? {
    val p = v as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun safeInt(v: JsonElement?): Long? = num(v)?.takeIf { it == floor(it) && abs(it) <= MAX_SAFE }?.toLong()

private fun stamp(v: JsonElement?): Long? = safeInt(v)?.takeIf { it >= 0 }

private fun string(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun isTrue(v: JsonElement?): Boolean = v is JsonPrimitive && !v.isString && v.content == "true"

private fun text(v: String?, max: Int): Boolean = v != null && v.codePointCount(0, v.length) in 1..max

fun validCard(c: JsonElement?, id: String): GroupCard? {
    val o = c as? JsonObject ?: return null
    val server = string(o["server"]) ?: return null
    val account = string(o["account"]) ?: return null
    val name = string(o["name"]) ?: return null
    val link = string(o["link_id"]) ?: return null
    val token = string(o["token"]) ?: return null
    val gen = stamp(o["gen"]) ?: return null
    val ok = string(o["id"]) == id && isUuid(id) && isUuid(link) && server.length <= 255 && SERVER_RE.matches(server) &&
        text(account, 64) && text(name, 64) && TOKEN_RE.matches(token)
    return if (ok) GroupCard(id, server, account, name, link, token, gen) else null
}

fun digestList(members: Map<String, Member>): String =
    members.keys.sorted().joinToString(",") { id -> "$id:${members.getValue(id).c?.gen?.toString() ?: "x"}" }

private fun <T : Comparable<T>> newer(a: Reg<T>, b: Reg<T>) = b.u > a.u || (b.u == a.u && b.v > a.v)

private fun earlier(u1: Long, id1: String, u2: Long, id2: String) = u1 < u2 || (u1 == u2 && id1 < id2)

private fun <T : Comparable<T>> register(cur: Reg<T>, raw: JsonElement?, ok: (JsonElement?) -> T?, now: Long): Reg<T>? {
    val o = raw as? JsonObject ?: return null
    val v = ok(o["v"]) ?: return null
    val u = stamp(o["u"]) ?: return null
    if (u > now + AHEAD_MS) return null
    return Reg(v, u).takeIf { newer(cur, it) }
}

// null if not accepted. bootstrap: the inviter's first state, which the caller has verified
fun applyState(local: GroupState, incoming: JsonElement?, sender: String, now: Long, bootstrap: Boolean = false): Applied? {
    val inc = incoming as? JsonObject ?: return null
    if (!bootstrap && local.members[sender]?.c == null) return null
    val members = LinkedHashMap(local.members)
    val added = mutableListOf<String>()
    val gone = mutableListOf<String>()
    for ((x, raw) in (inc["members"] as? JsonObject).orEmpty()) {
        val e = raw as? JsonObject ?: continue
        if (!isUuid(x)) continue
        val u = stamp(e["u"]) ?: continue
        val l = members[x]
        if (l != null && l.c == null) continue
        if (isTrue(e["gone"])) {
            members[x] = Member(maxOf(l?.u ?: 0L, u), gone = true)
            if (l != null) gone += x
            continue
        }
        val card = validCard(e["c"], x) ?: continue
        val cur = l?.c
        if (cur == null) {
            members[x] = Member(u, card)
            added += x
        } else if (sender == x && card.server == cur.server && card.account == cur.account && card.linkId == cur.linkId && card.gen > cur.gen) {
            members[x] = Member(u, card)
        }
    }
    // one live entry per account: the earliest stays
    val first = HashMap<String, String>()
    for ((id, e) in members.entries.toList()) {
        val c = e.c ?: continue
        val key = "${c.server}\n${c.account}"
        val other = first[key]
        if (other == null) {
            first[key] = id
            continue
        }
        val o = members.getValue(other)
        val (keep, drop) = if (earlier(o.u, other, e.u, id)) other to id else id to other
        first[key] = keep
        members[drop] = Member(members.getValue(drop).u, gone = true)
        if (!added.remove(drop)) gone += drop
    }
    if (members.size > MAX_ENTRIES) return null
    val name = register(local.name, inc["name"], { v -> string(v)?.takeIf { text(it, 64) } }, now)
    val ttl = register(local.ttl, inc["ttl"], { v -> safeInt(v)?.takeIf { it > 0 } }, now)
    return Applied(GroupState(name ?: local.name, ttl ?: local.ttl, members), added, gone, name != null, ttl != null)
}

// copies of one group held by the account's own devices; order does not matter
fun mergeGroup(a: Group, b: Group, now: Long): Group {
    val members = LinkedHashMap(a.members)
    for ((id, e) in b.members) {
        val l = members[id]
        val lc = l?.c
        val ec = e.c
        if (l == null) {
            members[id] = e
        } else if (lc == null || ec == null) {
            members[id] = Member(maxOf(l.u, e.u), gone = true)
        } else if (ec.gen > lc.gen || (ec.gen == lc.gen && (e.u > l.u || (e.u == l.u && ec.linkId > lc.linkId)))) {
            members[id] = e
        }
    }
    val link = members[a.me]?.c?.linkId
    val own = when {
        a.own.linkId == link -> a.own
        b.own.linkId == link -> b.own
        a.own.linkId > b.own.linkId -> a.own
        else -> b.own
    }
    val invites = LinkedHashMap<String, Invite>()
    for (src in listOf(a.invites, b.invites)) {
        for ((contact, inv) in src) {
            val cur = invites[contact]
            if (inv.expires.toDouble() > now / 1000.0 && (cur == null || cur.expires < inv.expires)) invites[contact] = inv
        }
    }
    val left = LinkedHashMap(a.left.orEmpty())
    for ((id, t) in b.left.orEmpty()) left[id] = minOf(left[id] ?: t, t)
    return a.copy(
        name = if (newer(a.name, b.name)) b.name else a.name,
        ttl = if (newer(a.ttl, b.ttl)) b.ttl else a.ttl,
        members = members,
        own = own.copy(gen = maxOf(a.own.gen, b.own.gen)),
        invites = invites,
        inviter = if (a.inviter != null && b.inviter != null) a.inviter else null,
        left = left.takeIf { it.isNotEmpty() },
    )
}
