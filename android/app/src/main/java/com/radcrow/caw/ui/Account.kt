package com.radcrow.caw.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radcrow.caw.app
import com.radcrow.caw.sealed.Password
import kotlinx.coroutines.launch

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

// first sign-in with a password an admin set
@Composable
fun ChoosePasswordScreen() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Text("Choose a password", style = MaterialTheme.typography.headlineSmall)
            Text("Your account was set up with a temporary password. Choose your own to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            PasswordField(password, { password = it }, "New password")
            PasswordField(confirm, { confirm = it }, "Confirm password")
            Text(Password.HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    error = Password.problem(password, app.repo.me.value?.username.orEmpty()) ?: if (password != confirm) "Passwords do not match." else null
                    if (error != null) return@Button
                    busy = true
                    scope.launch {
                        runCatching { app.repo.choosePassword(password) }.onFailure { error = it.message ?: "Could not save the password." }
                        busy = false
                    }
                },
                enabled = !busy && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Saving..." else "Continue") }
            TextButton(onClick = { app.logout() }) { Text("Sign out") }
        }
    }
}

@Composable
fun ChangePasswordDialog(rotate: Boolean, title: String, text: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                text?.let { Text(it) }
                PasswordField(current, { current = it }, "Current password")
                PasswordField(next, { next = it }, "New password")
                PasswordField(confirm, { confirm = it }, "Confirm new password")
                Text(Password.HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && current.isNotEmpty() && next.isNotEmpty(), onClick = {
                error = Password.problem(next, app.repo.me.value?.username.orEmpty()) ?: if (next != confirm) "Passwords do not match." else null
                if (error != null) return@TextButton
                busy = true
                scope.launch {
                    runCatching { app.repo.changePassword(current, next, rotate) }
                        .onSuccess {
                            Toast.makeText(ctx, "Password changed.", Toast.LENGTH_SHORT).show()
                            onDone()
                        }
                        .onFailure { error = it.message ?: "Could not change the password." }
                    busy = false
                }
            }) { Text(if (busy) "Saving..." else "Change password") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text(if (rotate) "Later" else "Cancel") } },
    )
}

// shown at the top of the contact list while the vault is locked under an old password, and for each new device
@Composable
fun AccountNotices() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val locked by app.repo.vaultLocked.collectAsStateWithLifecycle()
    val fresh by app.repo.newDevices.collectAsStateWithLifecycle()
    val secure by app.repo.secureNow.collectAsStateWithLifecycle()

    if (secure) {
        ChangePasswordDialog(
            rotate = true,
            title = "Change your password",
            text = "The device was removed. Change your password so it can't sign in again.",
        ) { app.repo.secureNow.value = false }
    }

    if (locked) {
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var confirmOver by remember { mutableStateOf(false) }
        fun run(f: suspend () -> Unit) {
            busy = true
            error = null
            scope.launch {
                runCatching { f() }.onSuccess { password = "" }.onFailure { error = it.message ?: "Could not unlock." }
                busy = false
            }
        }
        Card(
            Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your contacts are locked with your previous password. Enter it to unlock them.")
                PasswordField(password, { password = it }, "Previous password")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy && password.isNotEmpty(), onClick = {
                        run { app.repo.unlockOld(password) }
                    }) { Text("Unlock") }
                    Spacer(Modifier.size(8.dp))
                    if (confirmOver) TextButton(enabled = !busy, onClick = { run { app.repo.startOver() } }) { Text("Start over with this device's contacts") }
                    else TextButton(onClick = { confirmOver = true }) { Text("Forgot it?") }
                }
            }
        }
    }

    fresh.forEach { d ->
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("New device signed in: ${d.label}")
                Row {
                    TextButton(onClick = {
                        scope.launch { runCatching { app.repo.notMe(d.id) }.onFailure { e -> Toast.makeText(app, e.message, Toast.LENGTH_LONG).show() } }
                    }) { Text("That wasn't me") }
                    TextButton(onClick = { app.repo.dismissDevice(d.id) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
fun DeviceAlert(num: Long, name: String, onDevices: () -> Unit) {
    val app = LocalContext.current.app
    val alerts by app.repo.deviceAlerts.collectAsStateWithLifecycle()
    if (num !in alerts) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("A message with $name was also encrypted for a device that isn't yours.")
            Row {
                TextButton(onClick = onDevices) { Text("Check your devices") }
                TextButton(onClick = { app.repo.dismissAlert(num) }) { Text("Dismiss") }
            }
        }
    }
}
