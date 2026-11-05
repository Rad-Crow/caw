package com.radcrow.caw

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import kotlin.math.min

// home server by default; with a contact, a guest connection to the contact's server for calling
class Socket(private val ctx: Context, private val prefs: Prefs, private val http: OkHttpClient, private val contact: Remote? = null) {
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized = _unauthorized.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    private val reconnect = Runnable { open() }

    @Volatile private var ws: WebSocket? = null
    private var wanted = false
    private var retry = 0
    // stable across reconnects so the server can restore the call session
    private val client = UUID.randomUUID().toString()
    private var network: Network? = null
    private var openedOn: Network? = null

    // reconnects immediately on a new network instead of waiting out the backoff
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                main.post {
                    network = n
                    if (!wanted) return@post
                    if (_connected.value && openedOn == n) return@post
                    if (!_connected.value && ws != null && openedOn == n) return@post
                    reopen()
                }
            }

            override fun onLost(n: Network) {
                main.post { if (network == n) network = null }
            }
        }

    init {
        ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
    }

    // guest sockets last for a single call
    fun dispose() {
        stop()
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
    }

    private fun reopen() {
        main.removeCallbacks(reconnect)
        retry = 0
        val old = ws
        ws = null
        old?.cancel()
        _connected.value = false
        open()
    }

    fun start() {
        main.post {
            if (wanted) return@post
            wanted = true
            if (ws == null) open()
        }
    }

    fun stop() {
        main.post {
            wanted = false
            main.removeCallbacks(reconnect)
            ws?.close(1000, null)
            ws = null
            _connected.value = false
        }
    }

    fun send(obj: JsonObject): Boolean {
        val w = ws ?: return false
        return _connected.value && w.send(obj.toString())
    }

    private fun open() {
        openedOn = network
        val req = if (contact != null) {
            Request.Builder()
                .url(contact.server + "/api/link/ws?client=$client")
                .header("Authorization", "Link ${contact.out.linkId}.${contact.out.token}")
                .build()
        } else {
            val server = prefs.server ?: return
            val token = prefs.token ?: return
            Request.Builder()
                .url(server.trimEnd('/') + "/ws?client=$client")
                .header("Authorization", "Bearer $token")
                .build()
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (ws !== webSocket) return@post
                    retry = 0
                    _connected.value = true
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                _events.tryEmit(obj)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                dropped(webSocket, response?.code)
        })
    }

    private fun dropped(webSocket: WebSocket, code: Int?) {
        main.post {
            if (ws !== webSocket) return@post
            ws = null
            _connected.value = false
            if (code == 401 && contact == null) {
                wanted = false
                _unauthorized.tryEmit(Unit)
                return@post
            }
            if (code == 410 || code == 401) {
                wanted = false
                return@post
            }
            if (!wanted) return@post
            val delay = min(30_000L, 1000L shl min(retry++, 5))
            main.postDelayed(reconnect, delay)
        }
    }
}
