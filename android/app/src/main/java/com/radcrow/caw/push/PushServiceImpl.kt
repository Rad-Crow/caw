package com.radcrow.caw.push

import android.util.Log
import com.radcrow.caw.app
import com.radcrow.caw.json
import com.radcrow.caw.str
import kotlinx.serialization.json.jsonObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class PushServiceImpl : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        app.push.onEndpoint(endpoint)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // the server always encrypts; unencrypted messages are not from it
        if (!message.decrypted) return
        val msg = runCatching { json.parseToJsonElement(message.content.decodeToString()).jsonObject }.getOrNull() ?: return
        if (app.prefs.token == null) return
        when (msg.str("type")) {
            // the socket hello carries the call details; the push is only a wake-up
            "call" -> {
                app.socket.start()
                app.repo.onPushCall()
            }
            "cancel" -> msg.str("call_id")?.let { app.calls.dismiss(it) }
            "mail" -> if (!app.socket.connected.value) app.repo.onPushMessage()
            "request" -> app.repo.onPushRequest()
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w("caw.push", "registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        app.push.onUnregistered()
    }
}
