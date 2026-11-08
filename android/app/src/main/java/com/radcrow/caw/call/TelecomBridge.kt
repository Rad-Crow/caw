package com.radcrow.caw.call

import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.radcrow.caw.CawApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// registers calls with Android Telecom so cars, headsets and the system treat them as phone calls
class TelecomBridge(private val app: CawApp) {
    private sealed interface Cmd {
        data object Answer : Cmd
        data class Active(val done: CompletableDeferred<Boolean>? = null) : Cmd
        data class Route(val ep: CallEndpointCompat) : Cmd
        data class End(val cause: Int) : Cmd
    }

    private val manager: CallsManager? = runCatching {
        CallsManager(app).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
    }.onFailure { Log.w(TAG, "telecom unavailable", it) }.getOrNull()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var cmds: Channel<Cmd>? = null
    @Volatile private var callId: String? = null

    val endpoint = MutableStateFlow<CallEndpointCompat?>(null)
    val endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())

    val inCall get() = job?.isActive == true

    fun start(callId: String, name: String, userId: Long, incoming: Boolean) {
        val m = manager ?: return
        if (this.callId == callId && inCall) return
        val ch = Channel<Cmd>(Channel.UNLIMITED)
        cmds = ch
        this.callId = callId
        val attrs = CallAttributesCompat(
            name,
            Uri.parse("caw:$userId"),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        job = scope.launch {
            try {
                m.addCall(
                    attrs,
                    onAnswer = { app.calls.acceptFromSystem(callId) },
                    onDisconnect = { app.calls.endFromSystem(callId) },
                    onSetActive = { app.calls.holdFromSystem(callId, false) },
                    onSetInactive = { app.calls.holdFromSystem(callId, true) },
                ) {
                    val call = this
                    launch { currentCallEndpoint.collect { endpoint.value = it } }
                    launch { availableEndpoints.collect { endpoints.value = it } }
                    launch { isMuted.collect { app.calls.mutedBySystem(callId, it) } }
                    launch {
                        for (c in ch) {
                            when (c) {
                                Cmd.Answer -> answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                                is Cmd.Active -> {
                                    val ok = setActive() is CallControlResult.Success
                                    c.done?.complete(ok)
                                }
                                is Cmd.Route -> requestEndpointChange(c.ep)
                                is Cmd.End -> {
                                    disconnect(DisconnectCause(c.cause))
                                    call.cancel()
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancelled locally
            } catch (e: Exception) {
                Log.w(TAG, "telecom call failed", e)
            } finally {
                if (this@TelecomBridge.callId == callId) {
                    this@TelecomBridge.callId = null
                    endpoint.value = null
                    endpoints.value = emptyList()
                }
            }
        }
    }

    fun answer() {
        cmds?.trySend(Cmd.Answer)
    }

    fun active() {
        cmds?.trySend(Cmd.Active())
    }

    suspend fun resume(): Boolean {
        val done = CompletableDeferred<Boolean>()
        cmds?.trySend(Cmd.Active(done)) ?: return false
        return withTimeoutOrNull(5000) { done.await() } ?: false
    }

    fun route(ep: CallEndpointCompat) {
        cmds?.trySend(Cmd.Route(ep))
    }

    fun end(cause: Int) {
        val j = job ?: return
        cmds?.trySend(Cmd.End(cause))
        // Telecom may never confirm the disconnect; force cleanup after a timeout
        scope.launch {
            delay(3000)
            j.cancel()
        }
    }

    companion object {
        private const val TAG = "caw.telecom"
    }
}
