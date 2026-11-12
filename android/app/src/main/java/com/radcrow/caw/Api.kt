package com.radcrow.caw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import com.radcrow.caw.sealed.Mail
import com.radcrow.caw.sealed.NewMail
import com.radcrow.caw.sealed.OwnDevice
import com.radcrow.caw.sealed.LinkDevices
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException

class ApiException(val code: Int, message: String) : IOException(message)

class Api(private val prefs: Prefs, private val http: OkHttpClient) {
    private val jsonType = "application/json".toMediaType()

    private suspend fun call(method: String, path: String, body: JsonObject? = null, server: String? = null): String =
        withContext(Dispatchers.IO) {
            val base = (server ?: prefs.server ?: throw IOException("no server set")).trimEnd('/')
            val req = Request.Builder()
                .url(base + path)
                .method(method, body?.toString()?.toRequestBody(jsonType))
                .apply { prefs.token?.takeIf { server == null }?.let { header("Authorization", "Bearer $it") } }
                .build()
            execute(req).first
        }

    private fun execute(req: Request): Pair<String, HttpUrl> =
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject.str("error") }.getOrNull()
                throw ApiException(res.code, msg ?: "HTTP ${res.code}")
            }
            text to res.request.url
        }

    // the address the server answers on after any redirect, with its config
    suspend fun reach(server: String): Pair<String, ServerConfig> =
        withContext(Dispatchers.IO) {
            val (text, url) = execute(Request.Builder().url("$server/api/config").build())
            (normServer(url.toString()) ?: server) to json.decodeFromString<ServerConfig>(text)
        }

    private suspend inline fun <reified T> get(path: String): T = json.decodeFromString(call("GET", path))

    suspend fun salt(server: String, username: String): Salt =
        json.decodeFromString(call("POST", "/api/auth/salt", buildJsonObject { put("username", username) }, server))

    suspend fun login(server: String, username: String, loginKey: String): LoginResponse {
        val body = buildJsonObject {
            put("username", username)
            put("login_key", loginKey)
        }
        return json.decodeFromString(call("POST", "/api/auth/login", body, server))
    }

    suspend fun setLogin(loginKey: String, salt: String, current: String? = null, wrappedKey: String? = null): Me {
        val body = buildJsonObject {
            put("login_key", loginKey)
            put("salt", salt)
            current?.let { put("current_login_key", it) }
            wrappedKey?.let { put("wrapped_key", it) }
        }
        return json.decodeFromString(call("PUT", "/api/me/login", body))
    }

    suspend fun vault(): VaultResp = get("/api/vault")

    suspend fun putVault(version: Long, blob: String): Long {
        val body = buildJsonObject {
            put("version", version)
            put("blob", blob)
        }
        return json.parseToJsonElement(call("PUT", "/api/vault", body)).jsonObject.long("version")!!
    }

    suspend fun putVaultKey(wrapped: String) {
        call("PUT", "/api/vault/key", buildJsonObject { put("wrapped_key", wrapped) })
    }

    suspend fun sent(contact: String) {
        call("POST", "/api/sent", buildJsonObject { put("contact", contact) })
    }

    suspend fun config(server: String): ServerConfig = json.decodeFromString(call("GET", "/api/config", server = server))

    suspend fun logout() {
        call("POST", "/api/auth/logout", JsonObject(emptyMap()))
    }

    suspend fun me(): Me = get("/api/me")

    suspend fun myDevices(): List<OwnDevice> = get("/api/devices")

    suspend fun addDevice(label: String, signPub: String): String {
        val body = buildJsonObject {
            put("label", label)
            put("sign_pub", signPub)
        }
        return json.parseToJsonElement(call("POST", "/api/devices", body)).jsonObject.str("id")!!
    }

    suspend fun putDeviceKey(id: String, encPub: String, createdAt: Long, sig: String) {
        val body = buildJsonObject {
            put("enc_pub", encPub)
            put("created_at", createdAt)
            put("sig", sig)
        }
        call("PUT", "/api/devices/$id/key", body)
    }

    suspend fun removeDevice(id: String) {
        call("DELETE", "/api/devices/$id")
    }

    suspend fun createPairing(): Pairing = json.decodeFromString(call("POST", "/api/pairings", JsonObject(emptyMap())))

    suspend fun createLink(peerServer: String, peerAccount: String): LinkCred {
        val body = buildJsonObject {
            put("peer_server", peerServer)
            put("peer_account", peerAccount)
        }
        return json.decodeFromString(call("POST", "/api/links", body))
    }

    suspend fun myLinks(): List<LinkRow> = get("/api/links")

    suspend fun createGroupLink(gen: Long, token: String): LinkCred {
        val body = buildJsonObject {
            put("kind", "group")
            put("gen", gen)
            put("token", token)
        }
        return json.decodeFromString(call("POST", "/api/links", body))
    }

    suspend fun setGroupToken(id: String, gen: Long, token: String) {
        val body = buildJsonObject {
            put("gen", gen)
            put("token", token)
        }
        call("PUT", "/api/links/$id/token", body)
    }

    suspend fun ownGroupMail(id: String, m: NewMail): Mail =
        json.decodeFromString(call("POST", "/api/links/$id/mail", json.encodeToJsonElement(NewMail.serializer(), m).jsonObject))

    suspend fun ownUpload(id: String, body: ByteArray, onProgress: (Float) -> Unit): String {
        val base = prefs.server?.trimEnd('/') ?: throw IOException("no server set")
        return blobId(send(base + "/api/links/$id/blobs", "Bearer ${prefs.token}", "POST", counted(body, onProgress)))
    }

    suspend fun revokeLink(id: String) {
        call("DELETE", "/api/links/$id")
    }

    suspend fun acceptRequest(id: String) {
        call("POST", "/api/links/$id/accept", JsonObject(emptyMap()))
    }

    suspend fun myMail(): MailResponse = get("/api/mail")

    suspend fun downloadBlob(id: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(prefs.server!!.trimEnd('/') + "/api/blobs/$id")
            .header("Authorization", "Bearer ${prefs.token}")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "Attachment unavailable.")
            res.body!!.bytes()
        }
    }

    // --- contact's server via the outbound link. 410: link revoked; no response or 5xx: server down

    private suspend fun remote(c: Remote, method: String, path: String, body: RequestBody? = null): String =
        send(c.server + path, "Link ${c.out.linkId}.${c.out.token}", method, body)

    private suspend fun send(url: String, auth: String, method: String, body: RequestBody?): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .method(method, body)
            .header("Authorization", auth)
            .build()
        val res = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw Unreachable()
        }
        res.use {
            val text = it.body?.string().orEmpty()
            when {
                it.code == 410 -> throw Gone()
                it.code == 429 -> throw Unreachable(it.header("Retry-After")?.toLongOrNull() ?: 60)
                it.code >= 500 -> throw Unreachable()
                !it.isSuccessful -> throw ApiException(it.code, runCatching { json.parseToJsonElement(text).jsonObject.str("error") }.getOrNull() ?: "HTTP ${it.code}")
            }
            text
        }
    }

    suspend fun linkState(c: Remote): LinkState = json.decodeFromString(remote(c, "GET", "/api/link"))

    suspend fun linkDevices(c: Remote): LinkDevices = json.decodeFromString(remote(c, "GET", "/api/link/devices"))

    suspend fun linkSend(c: Remote, m: NewMail): Mail =
        json.decodeFromString(remote(c, "POST", "/api/link/mail", json.encodeToString(NewMail.serializer(), m).toRequestBody(jsonType)))

    suspend fun linkMail(c: Remote): MailResponse = json.decodeFromString(remote(c, "GET", "/api/link/mail"))

    suspend fun linkDrop(c: Remote) {
        remote(c, "DELETE", "/api/link")
    }

    suspend fun linkDownload(c: Remote, id: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(c.server + "/api/link/blobs/$id").header("Authorization", "Link ${c.out.linkId}.${c.out.token}").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "Attachment unavailable.")
            res.body!!.bytes()
        }
    }

    private fun counted(body: ByteArray, onProgress: (Float) -> Unit): RequestBody {
        val type = "application/octet-stream".toMediaType()
        return object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = body.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                var off = 0
                while (off < body.size) {
                    val n = minOf(64 * 1024, body.size - off)
                    sink.write(body, off, n)
                    off += n
                    onProgress(off.toFloat() / body.size)
                }
            }
        }
    }

    private fun blobId(text: String): String = json.parseToJsonElement(text).jsonObject.str("id") ?: throw IOException("Upload failed.")

    suspend fun linkUpload(c: Remote, body: ByteArray, onProgress: (Float) -> Unit): String =
        blobId(remote(c, "POST", "/api/link/blobs", counted(body, onProgress)))

    // authorized by the ticket alone
    suspend fun redeem(server: String, ticket: String, wait: Long, request: String?): Redeemed = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("ticket", ticket)
            put("wait", wait)
            request?.let { put("request", it) }
        }
        val req = Request.Builder().url(server.trimEnd('/') + "/api/pairings/redeem").post(body.toString().toRequestBody(jsonType)).build()
        val res = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw IOException("Can't reach that server.")
        }
        res.use {
            if (it.code == 410) throw IOException("This code has expired or was already used.")
            if (!it.isSuccessful) throw IOException("Couldn't add this contact.")
            json.decodeFromString(it.body!!.string())
        }
    }

    suspend fun turn(): IceConfig = get("/api/turn")

    suspend fun registerPush(endpoint: String, p256dh: String, auth: String) {
        val body = buildJsonObject {
            put("endpoint", endpoint)
            put("p256dh", p256dh)
            put("auth", auth)
        }
        call("POST", "/api/push", body)
    }

    suspend fun unregisterPush(endpoint: String) {
        call("DELETE", "/api/push", buildJsonObject { put("endpoint", endpoint) })
    }
}
