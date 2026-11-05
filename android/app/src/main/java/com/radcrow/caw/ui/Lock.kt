package com.radcrow.caw.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object AppLock {
    const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Caw is locked", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
