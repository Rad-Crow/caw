package com.radcrow.caw

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

// the other side of a conversation or call; id is the contact's local number
data class Peer(val id: Long, val host: String, val name: String)

@Serializable
data class Me(
    val id: Long,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("must_change") val mustChange: Boolean = false,
)

@Serializable
data class MailResponse(val now: Long, val mail: List<com.radcrow.caw.sealed.Mail>)

@Serializable
data class LinkCred(@SerialName("link_id") val linkId: String, val token: String)

@Serializable
data class LinkRow(val id: String)

@Serializable
data class Pairing(val ticket: String, @SerialName("expires_at") val expiresAt: Long, val account: String = "")

// server response describing how to derive the login key for a username
@Serializable
data class Salt(val salt: String, val m: Int, val t: Int, val p: Int)

@Serializable
data class VaultResp(val version: Long = 0, val blob: String? = null, @SerialName("wrapped_key") val wrappedKey: String? = null)

// one per account, encrypted with the data key; field names are the wire format shared with the web client
@Serializable
data class Synced(
    val id: String,
    val name: String,
    val alias: String? = null,
    val favorite: Boolean? = null,
    val server: String,
    val account: String,
    val out: LinkCred,
    @SerialName("in") val inLink: String,
    val status: String,
    @SerialName("created_at") val createdAt: Long,
    val ttl: Long? = null,
    val words: List<String>? = null,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class VaultEntry(val u: Long, val c: Synced? = null, val gone: Boolean? = null)

@Serializable
data class VaultDoc(val v: Int, val contacts: Map<String, VaultEntry>)

interface Remote {
    val server: String
    val out: LinkCred
}

// account on a home server; s syncs across the account's devices through the vault, other fields stay local (docs/protocol.md, section 4.2)
@Serializable
data class Contact(
    val s: Synced,
    val num: Long,
    val unreachableSince: Long? = null,
    val askedAt: Long? = null,
    // identity keys of their devices seen so far; verifies their messages while their server is unreachable
    val signers: Map<String, String> = emptyMap(),
) : Remote {
    val id get() = s.id
    val name get() = s.name
    override val server get() = s.server
    override val out get() = s.out
    val inLink get() = s.inLink
    val status get() = s.status
    val ttl get() = s.ttl
}

class Gone : IOException("gone")

class Unreachable(val retryAfter: Long = 0) : IOException("unreachable")

// payload of a pairing QR or pasted code
@Serializable
data class Code(val v: Int, val s: String, val t: String, val a: String, val n: String = "") {
    fun encode() = PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(serializer(), this).toByteArray())

    companion object {
        private const val PREFIX = "caw:1:"

        fun decode(text: String): Code? {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) return null
            return runCatching {
                json.decodeFromString(serializer(), String(java.util.Base64.getUrlDecoder().decode(t.removePrefix(PREFIX))))
            }.getOrNull()?.takeIf { it.v == 1 && it.s.isNotBlank() && it.t.isNotBlank() && it.a.isNotBlank() }
        }
    }
}

fun hostOf(server: String): String = runCatching { java.net.URI(server).authority }.getOrNull() ?: server

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class IceConfig(@SerialName("ice_servers") val iceServers: List<IceServer>)

@Serializable
data class LoginResponse(val token: String, val user: Me)

@Serializable
data class ServerConfig(
    @SerialName("server_name") val serverName: String = "Caw",
    @SerialName("vapid_public_key") val vapidPublicKey: String? = null,
    @SerialName("message_ttl_default") val messageTtlDefault: Long = 86400,
    @SerialName("message_ttl_min") val messageTtlMin: Long = 300,
    @SerialName("message_ttl_max") val messageTtlMax: Long = 7 * 86400,
    @SerialName("max_upload_mb") val maxUploadMb: Long = 25,
)
