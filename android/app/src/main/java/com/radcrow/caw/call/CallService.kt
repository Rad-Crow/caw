package com.radcrow.caw.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.radcrow.caw.app

class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = app.calls.active.value?.with?.let { app.repo.nameFor(it) } ?: "Caw"
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_ONGOING, Notifications.ongoing(this, name), type)
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
                .onFailure { Log.w("caw.call", "couldn't start call service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
