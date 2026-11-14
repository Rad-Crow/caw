package com.radcrow.caw.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.radcrow.caw.R
import com.radcrow.caw.Peer
import com.radcrow.caw.app
import com.radcrow.caw.ui.CallActivity
import com.radcrow.caw.ui.MainActivity

object Notifications {
    private const val CH_ONGOING = "ongoing_call"
    private const val ID_INCOMING = 1
    const val ID_ONGOING = 2
    private const val ID_MESSAGE_BASE = 1000
    private const val ID_SECURITY = 900
    private const val ID_INVITATION = 901

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    // a channel's sound is fixed once created, so each audio theme has its own channels
    private fun incomingId(theme: String) = "incoming_calls_$theme"
    private fun messagesId(theme: String) = "messages_$theme"
    private fun incomingId(ctx: Context) = incomingId(Sounds.theme(ctx))
    private fun messagesId(ctx: Context) = messagesId(Sounds.theme(ctx))

    // callers are never phone contacts, so only "calls from anyone" passes priority mode
    fun dndSilencesCalls(ctx: Context): Boolean {
        val nm = nm(ctx)
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> {
                if (nm.getNotificationChannel(incomingId(ctx))?.canBypassDnd() == true) return false
                val p = nm.notificationPolicy
                val calls = p.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0
                !(calls && p.priorityCallSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY)
            }
            else -> true
        }
    }

    fun createChannels(ctx: Context) {
        val nm = nm(ctx)
        val theme = Sounds.theme(ctx)
        val others = Sounds.THEMES.keys - theme
        val oldIncoming = listOf("incoming_calls") + others.map { incomingId(it) }
        val oldMessages = listOf("messages") + others.map { messagesId(it) }
        val ring = NotificationChannel(incomingId(theme), "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Sounds.uri(ctx, "ring", theme), Sounds.RING)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            keepImportance(nm, oldIncoming)
        }
        val ongoing = NotificationChannel(CH_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW)
        val messages = NotificationChannel(messagesId(theme), "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Sounds.uri(ctx, "message", theme), Sounds.NOTIFY)
            enableVibration(true)
            keepImportance(nm, oldMessages)
        }
        nm.createNotificationChannels(listOf(ring, ongoing, messages))
        (oldIncoming + oldMessages).forEach(nm::deleteNotificationChannel)
    }

    // a user who silenced or turned off the replaced channel keeps that setting
    private fun NotificationChannel.keepImportance(nm: NotificationManager, replaced: List<String>) {
        replaced.firstNotNullOfOrNull { nm.getNotificationChannel(it) }?.let { importance = it.importance }
    }

    private fun person(name: String) = Person.Builder().setName(name).setImportant(true).build()

    private fun callScreen(ctx: Context, action: String? = null, callId: String? = null): PendingIntent {
        val i = Intent(ctx, CallActivity::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_CALL, callId)
        return PendingIntent.getActivity(ctx, (action ?: "open").hashCode(), i, FLAGS)
    }

    private fun broadcast(ctx: Context, action: String, callId: String?): PendingIntent {
        val i = Intent(ctx, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_CALL, callId)
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, FLAGS)
    }

    fun incoming(ctx: Context, callId: String, from: Peer) {
        val full = callScreen(ctx)
        val hide = ctx.app.prefs.privateCalls
        val n = NotificationCompat.Builder(ctx, incomingId(ctx))
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(if (hide) "Caw" else from.name)
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setTimeoutAfter(90_000)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(if (hide) "Caw" else from.name),
                    broadcast(ctx, CallActionReceiver.ACTION_DECLINE, callId),
                    callScreen(ctx, CallActivity.ACTION_ANSWER, callId),
                ),
            )
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm(ctx).notify(ID_INCOMING, n)
    }

    fun cancelIncoming(ctx: Context) = nm(ctx).cancel(ID_INCOMING)

    fun ongoing(ctx: Context, name: String): Notification {
        val shown = if (ctx.app.prefs.privateCalls) "Caw" else name
        return NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(shown)
            .setContentText("Ongoing call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(callScreen(ctx))
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person(shown),
                    broadcast(ctx, CallActionReceiver.ACTION_HANGUP, null),
                ),
            )
            .build()
    }

    // private: one generic notification, no sender, no text
    fun message(ctx: Context, fromId: Long, name: String, body: String) {
        val show = !ctx.app.prefs.privateMessages
        val open = PendingIntent.getActivity(
            ctx,
            if (show) fromId.toInt() else 0,
            Intent(ctx, MainActivity::class.java).apply { if (show) putExtra(MainActivity.EXTRA_OPEN_USER, fromId) },
            FLAGS,
        )
        val b = NotificationCompat.Builder(ctx, messagesId(ctx))
            .setSmallIcon(R.drawable.ic_logo)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (show) {
            b.setContentTitle(name).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        } else {
            b.setContentTitle("New message")
        }
        nm(ctx).notify(if (show) ID_MESSAGE_BASE + fromId.toInt() else ID_MESSAGE_BASE, b.build())
    }

    fun invitation(ctx: Context, title: String, body: String) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        val b = NotificationCompat.Builder(ctx, messagesId(ctx))
            .setSmallIcon(R.drawable.ic_logo)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentTitle(title)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (!ctx.app.prefs.privateMessages) b.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        nm(ctx).notify(ID_INVITATION, b.build())
    }

    // account security: a new device, or a message also sealed for an unknown device
    fun security(ctx: Context, title: String, body: String) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        val b = NotificationCompat.Builder(ctx, messagesId(ctx))
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
        nm(ctx).notify(ID_SECURITY, b.build())
    }

    // conversation already on screen: no notification, only the channel sound
    fun chime(ctx: Context) {
        val nm = nm(ctx)
        val ch = nm.getNotificationChannel(messagesId(ctx)) ?: return
        if (ch.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return
        val uri = ch.sound ?: return
        runCatching { RingtoneManager.getRingtone(ctx, uri)?.apply { audioAttributes = ch.audioAttributes }?.play() }
    }

    fun cancelMessages(ctx: Context, fromId: Long) {
        nm(ctx).cancel(ID_MESSAGE_BASE + fromId.toInt())
        nm(ctx).cancel(ID_MESSAGE_BASE)
    }
}
