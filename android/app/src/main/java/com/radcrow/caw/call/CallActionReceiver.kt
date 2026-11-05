package com.radcrow.caw.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.radcrow.caw.app

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> context.app.calls.decline(intent.getStringExtra(EXTRA_CALL))
            ACTION_HANGUP -> context.app.calls.hangup()
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.radcrow.caw.DECLINE"
        const val ACTION_HANGUP = "com.radcrow.caw.HANGUP"
        const val EXTRA_CALL = "call_id"
    }
}
