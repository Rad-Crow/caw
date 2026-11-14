package com.radcrow.caw

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("caw", Context.MODE_PRIVATE)

    var server: String?
        get() = sp.getString("server", null)
        set(v) = sp.edit { putString("server", v) }

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit { putString("token", v) }

    // kept after sign-out to prefill the sign-in screen
    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit { putString("username", v) }

    var serverName: String
        get() = sp.getString("server_name", null) ?: "Caw"
        set(v) = sp.edit { putString("server_name", v) }

    var me: Me?
        get() = sp.getString("me", null)?.let { runCatching { json.decodeFromString<Me>(it) }.getOrNull() }
        set(v) = sp.edit { putString("me", v?.let { json.encodeToString(Me.serializer(), it) }) }

    var vapid: String?
        get() = sp.getString("vapid", null)
        set(v) = sp.edit { putString("vapid", v) }

    var pushEndpoint: String?
        get() = sp.getString("push_endpoint", null)
        set(v) = sp.edit { putString("push_endpoint", v) }

    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit { putBoolean("app_lock", v) }

    var theme: String
        get() = sp.getString("theme", null) ?: "system"
        set(v) = sp.edit { putString("theme", v) }

    var sounds: String
        get() = sp.getString("sounds", null) ?: "classic"
        set(v) = sp.edit { putString("sounds", v) }

    var privateCalls: Boolean
        get() = sp.getBoolean("private_calls", false)
        set(v) = sp.edit { putBoolean("private_calls", v) }

    var privateMessages: Boolean
        get() = sp.getBoolean("private_messages", false)
        set(v) = sp.edit { putBoolean("private_messages", v) }

    fun setupShown(userId: Long) = sp.getBoolean("setup_shown.$userId", false)

    fun markSetupShown(userId: Long) = sp.edit { putBoolean("setup_shown.$userId", true) }

    fun setupDismissed(userId: Long) = sp.getBoolean("setup_dismissed.$userId", false)

    fun setSetupDismissed(userId: Long, v: Boolean) = sp.edit { putBoolean("setup_dismissed.$userId", v) }

    fun clearSession() = sp.edit {
        me?.username?.let { putString("username", it) }
        remove("token")
        remove("me")
        remove("push_endpoint")
    }
}
