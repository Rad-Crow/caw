package com.radcrow.caw.ui

import android.Manifest
import android.app.NotificationManager
import android.net.Uri
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.radcrow.caw.Peer
import com.radcrow.caw.app
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    val openUser = MutableStateFlow<Long?>(null)
    private var afterMic: (() -> Unit)? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterMic?.invoke() else Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
        afterMic = null
    }

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val asked by lazy { getSharedPreferences("caw_asked", MODE_PRIVATE) }

    private lateinit var prompt: BiometricPrompt
    private var afterAuth: (() -> Unit)? = null
    private var autoPrompt = true
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                (afterAuth ?: {
                    app.locked.value = false
                    app.present = true
                })()
                afterAuth = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                afterAuth = null
            }
        })
        lifecycleScope.launch {
            app.calls.notices.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
        }
        setContent {
            CawTheme {
                val locked by app.locked.collectAsStateWithLifecycle()
                val saved = rememberSaveableStateHolder()
                if (locked) LockScreen(::unlock) else saved.SaveableStateProvider("root") { Root(this) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent) {
        i.getLongExtra(EXTRA_OPEN_USER, -1).takeIf { it > 0 }?.let { openUser.value = it }
    }

    // the pin screen is its own activity and also stops this one; re-arm only when the user actually left
    override fun onStop() {
        super.onStop()
        if (!prompting) autoPrompt = true
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!app.prefs.appLock)
        if (!app.locked.value) app.present = true
        if (app.locked.value && autoPrompt) {
            autoPrompt = false
            unlock()
        }
        if (app.prefs.token != null) app.push.autoPick()
    }

    // without a device screen lock there is nothing to authenticate against; allow access
    fun authenticate(title: String, onOk: () -> Unit) {
        if (!AppLock.available(this)) {
            onOk()
            return
        }
        afterAuth = onOk
        prompting = true
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AppLock.AUTH).build())
    }

    private fun unlock() = authenticate("Unlock Caw") { app.locked.value = false }

    fun afterLogin() {
        lifecycleScope.launch {
            app.repo.setup()
            app.push.ensure()
        }
    }

    // after repeated denials Android stops showing the prompt; opens app settings instead
    fun requestPermission(perm: String) {
        if (asked.getBoolean(perm, false) && !shouldShowRequestPermissionRationale(perm)) {
            openAppSettings(perm)
            return
        }
        asked.edit().putBoolean(perm, true).apply()
        askPermission.launch(perm)
    }

    fun requestNotifications() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && nm.areNotificationsEnabled().not() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppSettings(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppSettings(perm: String) {
        val i = if (perm == Manifest.permission.POST_NOTIFICATIONS) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(i)
    }

    fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
    }

    fun call(user: Peer) {
        withMic {
            app.calls.call(user)
            startActivity(Intent(this, CallActivity::class.java))
        }
    }

    private fun withMic(block: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            block()
        } else {
            afterMic = block
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val EXTRA_OPEN_USER = "open_user"
    }
}
