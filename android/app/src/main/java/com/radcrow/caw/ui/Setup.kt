package com.radcrow.caw.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radcrow.caw.app

data class SetupState(
    val notifications: Boolean,
    val mic: Boolean,
    val pushApp: String?,
    val pushReady: Boolean,
    val pushApps: List<String>,
    val lockScreen: Boolean,
    val bluetooth: Boolean,
) {
    val complete get() = notifications && mic && pushReady && lockScreen && bluetooth
    val pending get() = listOf(notifications, mic, pushReady, lockScreen, bluetooth).count { !it }
}

fun setupState(ctx: Context): SetupState {
    val app = ctx.app
    val nm = ctx.getSystemService(NotificationManager::class.java)
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return SetupState(
        notifications = nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)),
        mic = granted(Manifest.permission.RECORD_AUDIO),
        pushApp = app.push.chosen(),
        pushReady = app.push.chosen() != null && app.prefs.pushEndpoint != null,
        pushApps = app.push.distributors(),
        lockScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
        bluetooth = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT),
    )
}

// rechecked whenever the app returns to the foreground, e.g. after a permission prompt or installing ntfy
@Composable
fun rememberSetupState(): SetupState {
    val ctx = LocalContext.current
    val distributor by ctx.app.push.distributor.collectAsStateWithLifecycle()
    val endpoint by ctx.app.push.endpoint.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf(setupState(ctx)) }
    LifecycleResumeEffect(Unit) {
        ctx.app.push.autoPick()
        state = setupState(ctx)
        onPauseOrDispose {}
    }
    LaunchedEffect(distributor, endpoint) { state = setupState(ctx) }
    // a dismissal applies only to the setup items incomplete at that time
    LaunchedEffect(state.complete) {
        val uid = ctx.app.repo.me.value?.id
        if (state.complete && uid != null) ctx.app.prefs.setSetupDismissed(uid, false)
    }
    return state
}

fun openApp(ctx: Context, pkg: String) {
    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
}

@Composable
private fun Step(done: Boolean, title: String, detail: String?, action: @Composable () -> Unit = {}) {
    ListItem(
        leadingContent = {
            if (done) Icon(Icons.Default.CheckCircle, "Done", tint = Online)
            else Icon(Icons.Default.RadioButtonUnchecked, "To do", tint = MaterialTheme.colorScheme.outline)
        },
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = if (done) null else action,
    )
}

@Composable
private fun PushAppDialog(apps: List<String>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Push app") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                apps.forEach { pkg ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pkg == current, onClick = null)
                        Text(appLabel(ctx, pkg), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(activity: MainActivity, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val s = rememberSetupState()

    androidx.activity.compose.BackHandler(onBack = onBack)

    Scaffold(topBar = { TopAppBar(title = { Text("Set up Caw") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Step(s.notifications, "Notifications", "Alerts for calls and messages.") {
                TextButton(onClick = { activity.requestNotifications() }) { Text("Allow") }
            }
            Step(s.mic, "Microphone", "Needed for calls.") {
                TextButton(onClick = { activity.requestPermission(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
            }
            val app = s.pushApp
            var choosing by remember { mutableStateOf(false) }
            Step(
                s.pushReady,
                "Push app",
                when {
                    s.pushReady -> "Using ${appLabel(ctx, app!!)}. Allow its battery prompt if it asks."
                    app != null -> "Open ${appLabel(ctx, app)} once to finish connecting."
                    s.pushApps.size > 1 -> "Choose the app that delivers calls and messages while Caw is closed."
                    else -> "Delivers calls and messages while Caw is closed."
                },
            ) {
                when {
                    app != null -> TextButton(onClick = { openApp(ctx, app) }) { Text("Open ${appLabel(ctx, app)}") }
                    s.pushApps.isEmpty() -> TextButton(onClick = { installNtfy(ctx) }) { Text("Install ntfy") }
                }
            }
            if (s.pushApps.size > 1) {
                TextButton(onClick = { choosing = true }, Modifier.padding(start = 56.dp)) { Text("Choose") }
            }
            if (choosing) {
                PushAppDialog(s.pushApps, app, onDismiss = { choosing = false }) { pkg ->
                    choosing = false
                    if (pkg != app) ctx.app.push.use(pkg)
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Step(s.bluetooth, "Bluetooth", "Call audio in cars and on headsets.") {
                    TextButton(onClick = { activity.requestPermission(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow") }
                }
            }
            if (Build.VERSION.SDK_INT >= 34) {
                Step(s.lockScreen, "Calls on lock screen", "Shows incoming calls over the lock screen.") {
                    TextButton(onClick = { activity.openFullScreenSettings() }) { Text("Allow") }
                }
            }
            Spacer(Modifier.size(16.dp))
            Button(onClick = onDone, Modifier.fillMaxWidth()) { Text(if (s.complete) "Done" else "Skip for now") }
            if (!s.complete) {
                TextButton(
                    onClick = {
                        ctx.app.repo.me.value?.id?.let { ctx.app.prefs.setSetupDismissed(it, true) }
                        onDone()
                    },
                    Modifier.fillMaxWidth(),
                ) { Text("Don't remind me") }
            }
        }
    }
}

// shown at the top of the caw list until setup is complete
@Composable
fun SetupNotice(s: SetupState, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val uid = ctx.app.repo.me.value?.id
    if (s.complete || (uid != null && ctx.app.prefs.setupDismissed(uid))) return
    val missing = buildList {
        if (s.pushApp == null) add("No push app selected. Calls and messages won't arrive while Caw is closed.")
        else if (!s.pushReady) add("Open ${appLabel(LocalContext.current, s.pushApp)} once to finish connecting.")
        if (!s.notifications) add("Notifications are off.")
        if (!s.mic) add("Microphone access is off.")
        if (!s.lockScreen) add("Calls won't show on the lock screen.")
        if (!s.bluetooth) add("Bluetooth access is off. Car and headset audio is unavailable.")
    }
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            missing.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = onFinish) { Text("Finish setup") }
        }
    }
}
