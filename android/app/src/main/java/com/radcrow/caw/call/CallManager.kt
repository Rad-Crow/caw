package com.radcrow.caw.call

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.util.Log
import com.radcrow.caw.CawApp
import com.radcrow.caw.Peer
import com.radcrow.caw.int
import com.radcrow.caw.json
import com.radcrow.caw.str
import com.radcrow.caw.ui.CallActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import com.radcrow.caw.Socket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CallManager(private val app: CawApp) {
    data class Incoming(val callId: String, val from: Peer)

    enum class Status { CALLING, CONNECTING, ACTIVE }

    data class Active(
        val id: String,
        val status: Status,
        val with: Peer,
        val outgoing: Boolean = false,
        val rang: Boolean = false,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val startedAt: Long? = null,
        val reconnecting: Boolean = false,
        val held: Boolean = false,
    )

    val incoming = MutableStateFlow<Incoming?>(null)
    val active = MutableStateFlow<Active?>(null)
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val busy get() = incoming.value != null || active.value != null

    // the initial offerer drives ICE restarts so both ends never offer at once
    private class PeerConn(val pc: PeerConnection, val pending: MutableList<IceCandidate> = mutableListOf()) {
        var offerer = false
        var retry: Job? = null
    }

    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var giveUp: Job? = null

    // all call handling runs sequentially, one job at a time
    private val inbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val peers = HashMap<String, PeerConn>()
    private val audio = app.getSystemService(AudioManager::class.java)
    private var factory: PeerConnectionFactory? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private var ringback: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var reason = "hangup"
    private var ending: (() -> Unit)? = null
    private var endTimer: Job? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for (job in inbox) runCatching { job() }.onFailure { Log.e(TAG, "call job failed", it) }
        }
    }

    private fun post(job: suspend () -> Unit) {
        inbox.trySend(job)
    }

    fun handle(e: JsonObject, viaGuest: Boolean = false) = post { process(e, viaGuest) }

    // outgoing calls run on the contact's server as a guest; incoming calls on the home server
    private var guest: Socket? = null
    private var guestJob: Job? = null
    private var viaGuest = false
    private var dialed: CompletableDeferred<Unit>? = null

    private fun own(obj: JsonObject) = app.socket.send(obj)

    fun call(user: Peer) = post {
        if (busy) return@post
        finishEnding()
        val c = app.repo.contact(user.id)?.takeIf { it.status == "active" }
        if (c == null) {
            notices.tryEmit("This contact is not available.")
            return@post
        }
        val id = UUID.randomUUID().toString()
        active.value = Active(id, Status.CALLING, user, outgoing = true)
        app.telecom.start(id, if (app.prefs.privateCalls) "Caw" else app.repo.nameFor(user), user.id, incoming = false)
        try {
            prepare()
        } catch (e: Exception) {
            cleanup()
            notices.tryEmit("Call failed: ${e.message}")
            return@post
        }
        if (active.value?.id != id) return@post
        viaGuest = true
        val ready = CompletableDeferred<Unit>().also { dialed = it }
        val g = Socket(app, app.prefs, app.http, c)
        guest = g
        // collected directly: the job queue is blocked waiting for this hello
        guestJob = timers.launch {
            g.events.collect { e ->
                if (e.str("type") == "hello") ready.complete(Unit)
                handle(e, true)
            }
        }
        g.start()
        if (withTimeoutOrNull(DIAL_MS) { ready.await() } == null || active.value?.id != id || !send(simple("call", id))) {
            cleanup()
            notices.tryEmit("Can't reach this contact's server.")
            return@post
        }
        startTone()
    }

    fun accept() = post {
        val inc = incoming.value ?: return@post
        if (active.value != null) return@post
        viaGuest = false
        active.value = Active(inc.callId, Status.CONNECTING, inc.from)
        incoming.value = null
        Notifications.cancelIncoming(app)
        try {
            prepare()
        } catch (e: Exception) {
            cleanup()
            notices.tryEmit("Answer failed: ${e.message}")
            return@post
        }
        if (active.value?.id == inc.callId) {
            send(simple("accept", inc.callId))
            app.telecom.answer()
        }
    }

    // for answers from a car, headset or the system call UI
    fun answerNeedsUnlock() = app.prefs.appLock && !app.present

    // caller stays anonymous until the user is in the app
    fun hideCaller() = app.prefs.privateCalls && !app.present

    // headset and car buttons cannot show an unlock prompt; opens the call screen instead
    fun acceptFromSystem(callId: String) {
        if (incoming.value?.callId != callId) return
        if (answerNeedsUnlock()) {
            runCatching {
                app.startActivity(
                    Intent(app, CallActivity::class.java).setAction(CallActivity.ACTION_ANSWER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            throw IllegalStateException("unlock needed to answer")
        }
        accept()
    }

    fun endFromSystem(callId: String) = post {
        when (callId) {
            incoming.value?.callId -> {
                own(simple("reject", callId))
                incoming.value = null
                Notifications.cancelIncoming(app)
                app.repo.idleCheck()
            }
            active.value?.id -> hangupNow()
        }
    }

    fun mutedBySystem(callId: String, muted: Boolean) = post {
        val a = active.value?.takeIf { it.id == callId && it.muted != muted } ?: return@post
        setState(a.copy(muted = muted))
    }

    fun holdFromSystem(callId: String, held: Boolean) = post {
        val a = active.value?.takeIf { it.id == callId && it.held != held } ?: return@post
        setState(a.copy(held = held))
    }

    fun resume() = post {
        val a = active.value?.takeIf { it.held } ?: return@post
        if (app.telecom.inCall && !app.telecom.resume()) {
            notices.tryEmit("Can't resume the call.")
            return@post
        }
        setState(a.copy(held = false))
    }

    fun route(ep: androidx.core.telecom.CallEndpointCompat) = app.telecom.route(ep)

    fun decline(callId: String? = null) = post {
        val inc = incoming.value ?: return@post
        if (callId != null && callId != inc.callId) return@post
        own(simple("reject", inc.callId))
        incoming.value = null
        Notifications.cancelIncoming(app)
        app.telecom.end(DisconnectCause.REJECTED)
        app.repo.idleCheck()
    }

    fun dismiss(callId: String) = post {
        if (incoming.value?.callId != callId) return@post
        incoming.value = null
        Notifications.cancelIncoming(app)
        app.telecom.end(DisconnectCause.MISSED)
        app.repo.idleCheck()
    }

    fun hangup() = post { hangupNow() }

    fun toggleMute() = post {
        val a = active.value ?: return@post
        setState(a.copy(muted = !a.muted))
    }

    private fun setState(a: Active) {
        active.value = a
        track?.setEnabled(!a.muted && !a.held)
        peers.values.forEach { p -> p.pc.receivers.forEach { it.track()?.setEnabled(!a.held) } }
    }

    fun toggleSpeaker() = post {
        val a = active.value ?: return@post
        if (app.telecom.inCall) {
            val eps = app.telecom.endpoints.value
            val target = if (a.speaker) {
                eps.firstOrNull { it.type == androidx.core.telecom.CallEndpointCompat.TYPE_BLUETOOTH }
                    ?: eps.firstOrNull { it.type == androidx.core.telecom.CallEndpointCompat.TYPE_WIRED_HEADSET }
                    ?: eps.firstOrNull { it.type == androidx.core.telecom.CallEndpointCompat.TYPE_EARPIECE }
            } else {
                eps.firstOrNull { it.type == androidx.core.telecom.CallEndpointCompat.TYPE_SPEAKER }
            }
            target?.let { app.telecom.route(it) }
        } else {
            setSpeaker(!a.speaker)
        }
        active.value = a.copy(speaker = !a.speaker)
    }

    private fun hangupNow() {
        val a = active.value ?: return
        send(simple("hangup", a.id))
        reason = "hangup"
        cleanup(tone = true)
    }

    private suspend fun process(e: JsonObject, fromGuest: Boolean) {
        val callId = e.str("call_id")
        val mine = callId != null && active.value?.id == callId
        when (e.str("type")) {
            "hello" -> {
                // reconnected after a drop: either the server kept the seat and media needs a new path, or the call has ended
                active.value?.takeIf { it.status != Status.CALLING && fromGuest == viaGuest }?.let { a ->
                    val kept = e["calls"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == a.id } == true
                    if (kept) peers.keys.toList().forEach { recover(it) } else lost()
                }
                e["incoming"]?.jsonArray?.forEach { inc ->
                    process(JsonObject(inc.jsonObject + ("type" to kotlinx.serialization.json.JsonPrimitive("incoming"))), fromGuest)
                }
            }

            "incoming" -> {
                if (callId == null || mine || incoming.value?.callId == callId || fromGuest) return
                // woken by a push, the contacts may still be loading
                if (!app.repo.contactsLoaded.value) withTimeoutOrNull(15_000) { app.repo.contactsLoaded.first { it } }
                val c = app.repo.contacts.value.find { it.inLink == e.str("link_id") && it.status == "active" }
                // another of the account's devices may still take it
                if (busy || c == null) return
                finishEnding()
                val from = app.repo.peer(c)
                incoming.value = Incoming(callId, from)
                Notifications.incoming(app, callId, from.copy(name = app.repo.nameFor(from)))
                app.telecom.start(callId, if (app.prefs.privateCalls) "Caw" else app.repo.nameFor(from), from.id, incoming = true)
                if (app.visible && !Notifications.dndSilencesCalls(app)) {
                    app.startActivity(Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            "ringing" -> if (mine) active.update { it?.copy(rang = true) }

            "joined" -> if (mine) {
                for (p in e["peers"]?.jsonArray.orEmpty()) {
                    val peerId = p.jsonPrimitive.contentOrNull ?: continue
                    val peer = peer(peerId)
                    peer.offerer = true
                    val offer = peer.pc.createOfferAsync()
                    peer.pc.setLocalAsync(offer)
                    sendSdp(callId!!, peerId, offer)
                }
            }

            "peer_joined" -> if (mine) {
                stopTone()
                active.update { it?.copy(status = Status.CONNECTING) }
                e.str("peer_id")?.let { peer(it) }
            }

            "signal" -> if (mine) {
                val from = e.str("from") ?: return
                val p = peers[from] ?: return
                val data = e["data"]?.jsonObject ?: return
                if (data["restart"] != null) {
                    if (p.offerer) restart(from)
                    return
                }
                data["sdp"]?.jsonObject?.let { sdp ->
                    val type = sdp.str("type") ?: return
                    val text = sdp.str("sdp") ?: return
                    val with = active.value?.with ?: return
                    if (!app.repo.verifyCall(with.id, callId!!, type, text, data.str("device"), data.str("sig"))) return unverified()
                    val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), text)
                    p.pc.setRemoteAsync(desc)
                    p.pending.forEach { p.pc.addIceCandidate(it) }
                    p.pending.clear()
                    if (desc.type == SessionDescription.Type.OFFER) {
                        val answer = p.pc.createAnswerAsync()
                        p.pc.setLocalAsync(answer)
                        sendSdp(callId!!, from, answer)
                    }
                }
                data["candidate"]?.jsonObject?.let { c ->
                    val cand = IceCandidate(c.str("sdpMid") ?: "", c.int("sdpMLineIndex") ?: 0, c.str("candidate") ?: return)
                    if (p.pc.remoteDescription != null) p.pc.addIceCandidate(cand) else p.pending += cand
                }
            }

            "peer_left" -> if (mine) e.str("peer_id")?.let { closePeer(it) }

            "ended" -> {
                if (callId != null && incoming.value?.callId == callId) {
                    incoming.value = null
                    Notifications.cancelIncoming(app)
                    app.telecom.end(
                        if (e.str("reason") == "answered_elsewhere") DisconnectCause.ANSWERED_ELSEWHERE else DisconnectCause.MISSED,
                    )
                    app.repo.idleCheck()
                }
                if (mine) {
                    reason = e.str("reason") ?: "hangup"
                    cleanup(tone = true)
                    notices.tryEmit(REASONS[e.str("reason")] ?: "Call ended")
                }
            }

            "error" -> if (mine) {
                cleanup()
                notices.tryEmit(e.str("message") ?: "Call failed")
            }
        }
    }

    private suspend fun prepare() {
        ice = app.api.turn().iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        startAudio()
        CallService.start(app)
    }

    private fun factory(): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory().also { factory = it }
    }

    private fun localTrack(): AudioTrack = track ?: run {
        val src = factory().createAudioSource(MediaConstraints())
        source = src
        factory().createAudioTrack("caw-audio", src).also { track = it }
    }

    private fun peer(peerId: String): PeerConn {
        peers[peerId]?.let { return it }
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory().createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                val id = active.value?.id ?: return
                send(buildJsonObject {
                    put("type", "signal")
                    put("call_id", id)
                    put("to", peerId)
                    putJsonObject("data") {
                        putJsonObject("candidate") {
                            put("candidate", c.sdp)
                            put("sdpMid", c.sdpMid)
                            put("sdpMLineIndex", c.sdpMLineIndex)
                        }
                    }
                })
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                post { onState(peerId, state) }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                if (s != PeerConnection.IceConnectionState.CONNECTED && s != PeerConnection.IceConnectionState.COMPLETED) return
                post {
                    val p = peers[peerId] ?: return@post
                    if (p.retry != null && p.pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) recovered(p)
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IOException("connection setup failed")
        pc.addTrack(localTrack(), listOf("caw"))
        preferRed(pc)
        return PeerConn(pc).also { peers[peerId] = it }
    }

    private fun preferRed(pc: PeerConnection) {
        val (red, rest) = factory().getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).codecs
            .partition { it.name.equals("red", ignoreCase = true) }
        if (red.isEmpty()) return
        pc.transceivers.forEach { it.setCodecPreferences(red + rest) }
    }

    private suspend fun onState(peerId: String, state: PeerConnection.PeerConnectionState) {
        val p = peers[peerId] ?: return
        val a = active.value ?: return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                recovered(p)
                if (a.status != Status.ACTIVE) {
                    app.telecom.active()
                    stopTone()
                    active.update { it?.copy(status = Status.ACTIVE, startedAt = SystemClock.elapsedRealtime()) }
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> recover(peerId)
            // often recovers on its own within a few seconds
            PeerConnection.PeerConnectionState.DISCONNECTED -> timers.launch {
                delay(2000)
                post {
                    val q = peers[peerId]
                    if (q === p && q.pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) recover(peerId)
                }
            }
            else -> {}
        }
    }

    // media path lost (network change, dropped socket): retries for RECOVER_MS before hanging up
    private suspend fun recover(peerId: String) {
        val p = peers[peerId] ?: return
        if (active.value == null) return
        active.update { it?.copy(reconnecting = true) }
        if (giveUp == null) giveUp = timers.launch {
            delay(RECOVER_MS)
            post { lost() }
        }
        if (p.retry != null) return
        restart(peerId)
        // the path may not have dropped (only the socket), so a settled connection also counts as recovered
        p.retry = timers.launch {
            while (true) {
                delay(5000)
                post {
                    val q = peers[peerId] ?: return@post
                    if (q.pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED &&
                        q.pc.signalingState() == PeerConnection.SignalingState.STABLE
                    ) {
                        recovered(q)
                    } else {
                        restart(peerId)
                    }
                }
            }
        }
    }

    private fun recovered(p: PeerConn) {
        p.retry?.cancel()
        p.retry = null
        if (peers.values.any { it.retry != null }) return
        giveUp?.cancel()
        giveUp = null
        active.update { it?.copy(reconnecting = false) }
    }

    private suspend fun restart(peerId: String) {
        val p = peers[peerId] ?: return
        val callId = active.value?.id ?: return
        if (!p.offerer) {
            send(buildJsonObject {
                put("type", "signal")
                put("call_id", callId)
                put("to", peerId)
                putJsonObject("data") { put("restart", true) }
            })
            return
        }
        runCatching {
            p.pc.restartIce()
            val offer = p.pc.createOfferAsync()
            p.pc.setLocalAsync(offer)
            sendSdp(callId, peerId, offer)
        }.onFailure { Log.w(TAG, "ice restart", it) }
    }

    private fun lost() {
        val a = active.value ?: return
        send(simple("hangup", a.id))
        reason = "lost"
        cleanup(tone = true)
        notices.tryEmit(REASONS.getValue("lost"))
    }

    private fun closePeer(peerId: String) {
        peers.remove(peerId)?.let {
            it.retry?.cancel()
            it.pc.dispose()
        }
    }

    private fun cleanup(tone: Boolean = false) {
        guestJob?.cancel()
        guestJob = null
        guest?.dispose()
        guest = null
        viaGuest = false
        dialed = null
        active.value?.takeIf { it.outgoing && it.rang && reason != "unverified" }?.let { a ->
            val secs = a.startedAt?.let { (SystemClock.elapsedRealtime() - it) / 1000 }
            app.repo.callEnded(a.with, secs, reason)
        }
        val why = reason
        reason = "hangup"
        giveUp?.cancel()
        giveUp = null
        stopTone()
        peers.keys.toList().forEach(::closePeer)
        track?.dispose()
        source?.dispose()
        track = null
        source = null
        val cause = active.value?.let { if (why == "hangup") DisconnectCause.LOCAL else DisconnectCause.REMOTE }
        active.value = null
        finishEnding()
        ending = {
            stopAudio()
            cause?.let { app.telecom.end(it) }
            CallService.stop(app)
            app.repo.idleCheck()
        }
        val sound = if (tone) Sounds.play(app, "hangup", Sounds.VOICE) else null
        if (sound == null) return finishEnding()
        val ms = runCatching { sound.duration }.getOrDefault(0).coerceIn(0, 2000) + 100L
        // the call's audio route stays until the tone has played
        endTimer = timers.launch {
            delay(ms)
            post { finishEnding() }
        }
    }

    // also runs before a new call starts, since telecom.end() ends whichever call is current
    private fun finishEnding() {
        endTimer?.cancel()
        endTimer = null
        ending?.invoke()
        ending = null
    }

    private fun send(obj: JsonObject) = if (viaGuest) guest?.send(obj) ?: false else app.socket.send(obj)

    private fun simple(type: String, callId: String) = buildJsonObject {
        put("type", type)
        put("call_id", callId)
    }

    private fun sendSdp(callId: String, to: String, d: SessionDescription) {
        val type = d.type.canonicalForm()
        val (device, sig) = app.sealed.signCall(callId, type, d.description) ?: return
        send(buildJsonObject {
            put("type", "signal")
            put("call_id", callId)
            put("to", to)
            putJsonObject("data") {
                putJsonObject("sdp") {
                    put("type", type)
                    put("sdp", d.description)
                }
                put("device", device)
                put("sig", sig)
            }
        })
    }

    private fun unverified() {
        val a = active.value ?: return
        send(simple("hangup", a.id))
        reason = "unverified"
        cleanup(tone = true)
        notices.tryEmit(REASONS.getValue("unverified"))
    }

    private fun startAudio() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            .also { audio.requestAudioFocus(it) }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!app.telecom.inCall) setSpeaker(false)
    }

    private fun stopAudio() {
        if (!app.telecom.inCall) setSpeaker(false)
        audio.mode = AudioManager.MODE_NORMAL
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!on) {
                audio.clearCommunicationDevice()
                return
            }
            audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
        } else {
            audio.isSpeakerphoneOn = on
        }
    }

    private fun startTone() {
        stopTone()
        ringback = Sounds.play(app, "ringback", Sounds.VOICE, loop = true)
    }

    private fun stopTone() {
        ringback?.release()
        ringback = null
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private suspend fun PeerConnection.createOfferAsync(): SessionDescription = suspendCancellableCoroutine { cont ->
        createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) = cont.resume(d!!)
            override fun onCreateFailure(e: String?) = cont.resumeWithException(IOException(e))
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.createAnswerAsync(): SessionDescription = suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) = cont.resume(d!!)
            override fun onCreateFailure(e: String?) = cont.resumeWithException(IOException(e))
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.setLocalAsync(d: SessionDescription) = suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String?) = cont.resumeWithException(IOException(e))
        }, d)
    }

    private suspend fun PeerConnection.setRemoteAsync(d: SessionDescription) = suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String?) = cont.resumeWithException(IOException(e))
        }, d)
    }

    companion object {
        private const val TAG = "caw.call"
        private const val RECOVER_MS = 30_000L
        private const val DIAL_MS = 10_000L

        private val REASONS = mapOf(
            "lost" to "Connection lost",
            "unverified" to "Call could not be verified",
            "declined" to "Call declined",
            "no_answer" to "No answer",
            "cancelled" to "Call cancelled",
            "completed" to "Call ended",
            "answered_elsewhere" to "Answered on another device",
            "left" to "Call ended",
        )
    }
}
