package com.radcrow.caw.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import android.app.KeyguardManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.radcrow.caw.app
import com.radcrow.caw.call.CallManager
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallActivity : FragmentActivity() {
    private var afterMic: (() -> Unit)? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterMic?.invoke() else Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
        afterMic = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prompt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            app.calls.notices.collect { Toast.makeText(this@CallActivity, it, Toast.LENGTH_SHORT).show() }
        }
        handle(intent)
        setContent { CawTheme { CallScreen(::answer) { finish() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        if (i.action == ACTION_ANSWER) answer()
    }

    private val prompt by lazy {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = unlocked()
        })
    }

    private fun unlocked() {
        app.present = true
        app.locked.value = false
        answerNow()
    }

    private fun answer() {
        if (!app.calls.answerNeedsUnlock()) return answerNow()
        val km = getSystemService(KeyguardManager::class.java)
        when {
            km.isKeyguardLocked -> km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = unlocked()
            })
            AppLock.available(this) -> prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder().setTitle("Answer call").setAllowedAuthenticators(AppLock.AUTH).build(),
            )
            else -> unlocked()
        }
    }

    private fun answerNow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            app.calls.accept()
        } else {
            afterMic = { app.calls.accept() }
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.radcrow.caw.ANSWER"
    }
}

@Composable
private fun CallScreen(onAnswer: () -> Unit, onDone: () -> Unit) {
    val calls = androidx.compose.ui.platform.LocalContext.current.app.calls
    val incoming by calls.incoming.collectAsStateWithLifecycle()
    val active by calls.active.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    // a short gap between incoming and active is expected while answering
    LaunchedEffect(incoming == null && active == null) {
        if (incoming == null && active == null) {
            delay(800)
            onDone()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val app = androidx.compose.ui.platform.LocalContext.current.app
            val name = if (calls.hideCaller()) "Caw" else (active?.with ?: incoming?.from)?.let { app.repo.nameFor(it) }.orEmpty()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.size(48.dp))
                Avatar(name, size = 112.dp)
                Spacer(Modifier.size(16.dp))
                Text(name, style = MaterialTheme.typography.headlineMedium)
                val a = active
                val status = when {
                    a == null && incoming != null -> "Incoming call"
                    a == null -> ""
                    a.reconnecting -> "Reconnecting..."
                    a.status == CallManager.Status.ACTIVE && a.startedAt != null -> {
                        val s = (now - a.startedAt) / 1000
                        "%d:%02d".format(s / 60, s % 60)
                    }
                    a.status == CallManager.Status.CALLING -> "Ringing..."
                    else -> "Connecting..."
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val a = active
            if (a != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconToggleButton(a.muted, { calls.toggleMute() }, Modifier.size(64.dp)) {
                        Icon(if (a.muted) Icons.Default.MicOff else Icons.Default.Mic, "Mute")
                    }
                    RoundButton(Icons.Default.CallEnd, "Hang up", Color(0xFFDC2626)) { calls.hangup() }
                    AudioRoute(a.speaker) { calls.toggleSpeaker() }
                }
            } else if (incoming != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    RoundButton(Icons.Default.CallEnd, "Decline", Color(0xFFDC2626)) { calls.decline() }
                    RoundButton(Icons.Default.Call, "Answer", Color(0xFF16A34A), onAnswer)
                }
            } else {
                Spacer(Modifier.size(72.dp))
            }
        }
    }
}

// with Telecom every route (earpiece, speaker, bluetooth, car) is selectable; otherwise a speaker toggle
@Composable
private fun AudioRoute(speaker: Boolean, onToggle: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.app
    val endpoints by app.telecom.endpoints.collectAsStateWithLifecycle()
    val current by app.telecom.endpoint.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    if (endpoints.size < 2) {
        FilledTonalIconToggleButton(speaker, { onToggle() }, Modifier.size(64.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speaker")
        }
        return
    }
    Box {
        FilledTonalIconToggleButton(current?.type == CallEndpointCompat.TYPE_SPEAKER, { open = true }, Modifier.size(64.dp)) {
            Icon(routeIcon(current?.type), "Audio output: ${current?.name ?: ""}")
        }
        DropdownMenu(open, { open = false }) {
            endpoints.forEach { ep ->
                DropdownMenuItem(
                    leadingIcon = { Icon(routeIcon(ep.type), null) },
                    text = { Text(ep.name.toString(), fontWeight = if (ep.identifier == current?.identifier) FontWeight.Bold else null) },
                    onClick = {
                        open = false
                        app.calls.route(ep)
                    },
                )
            }
        }
    }
}

private fun routeIcon(type: Int?): ImageVector = when (type) {
    CallEndpointCompat.TYPE_SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    CallEndpointCompat.TYPE_BLUETOOTH -> Icons.Default.Bluetooth
    CallEndpointCompat.TYPE_WIRED_HEADSET -> Icons.Default.Headset
    else -> Icons.Default.PhoneInTalk
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
    ) { Icon(icon, label, Modifier.size(32.dp)) }
}
