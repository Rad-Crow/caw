package com.radcrow.caw.call

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.radcrow.caw.app

object Sounds {
    val THEMES = linkedMapOf("classic" to "Classic", "crow" to "Crow")

    val RING: AudioAttributes = attrs(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
    val NOTIFY: AudioAttributes = attrs(AudioAttributes.USAGE_NOTIFICATION)
    val VOICE: AudioAttributes = attrs(AudioAttributes.USAGE_VOICE_COMMUNICATION)

    private fun attrs(usage: Int) = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun theme(ctx: Context) = ctx.app.prefs.sounds.takeIf { it in THEMES } ?: "classic"

    // by name, not R.raw id: notification channels keep this uri across app updates
    fun uri(ctx: Context, sound: String, theme: String = theme(ctx)): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${ctx.packageName}/raw/${theme}_$sound")

    // one-shot players stay referenced until done: a collected MediaPlayer stops playing
    private val oneShots = mutableSetOf<MediaPlayer>()

    fun play(ctx: Context, sound: String, attrs: AudioAttributes, loop: Boolean = false): MediaPlayer? {
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(ctx, uri(ctx, sound))
            mp.isLooping = loop
            if (!loop) {
                synchronized(oneShots) { oneShots += mp }
                mp.setOnCompletionListener(::done)
                mp.setOnErrorListener { p, _, _ ->
                    done(p)
                    true
                }
            }
            mp.prepare()
            mp.start()
            mp
        }.getOrElse {
            done(mp)
            null
        }
    }

    private fun done(mp: MediaPlayer) {
        synchronized(oneShots) { oneShots -= mp }
        mp.release()
    }
}
