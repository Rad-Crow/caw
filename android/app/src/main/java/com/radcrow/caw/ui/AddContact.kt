package com.radcrow.caw.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.radcrow.caw.app
import com.radcrow.caw.hostOf
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun qr(text: String, size: Int = 640, margin: Int = 1): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to margin))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun fmtLeft(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

// image for scanning, text for pasting; apps that accept only one get what they support
private fun shareCode(ctx: Context, code: String) {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, "caw-invite.png")
    f.outputStream().use { qr(code, 900, 4).compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Caw invite")
        putExtra(Intent.EXTRA_TEXT, "Add me on Caw: scan the QR code with Caw, or paste this invite into Caw.\n\n$code")
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share invite"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareInviteScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf<String?>(null) }
    var expires by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var error by remember { mutableStateOf<String?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    val prompts by app.repo.prompts.collectAsStateWithLifecycle()
    val start = remember { prompts.size }
    BackHandler(onBack = onBack)

    LaunchedEffect(gen) {
        code = null
        error = null
        runCatching { app.repo.showCode() }
            .onSuccess { (c, exp) ->
                code = c
                expires = exp - app.repo.serverNow() + System.currentTimeMillis() / 1000
            }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    // a scan of this invite arrives as a prompt; closes this screen
    LaunchedEffect(prompts.size) { if (prompts.size > start) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Share invite") },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val c = code
            val left = maxOf(0L, expires - now)
            when {
                c != null && left > 0 -> {
                    val bmp = remember(c) { qr(c) }
                    Image(
                        bmp.asImageBitmap(),
                        "Invite",
                        Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)
                            .background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp),
                    )
                    Text(
                        "Scan with Caw on the other device, or share it. Expires in ${fmtLeft(left)}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(c)) }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Text("  Copy")
                        }
                        Button(onClick = { runCatching { shareCode(ctx, c) } }) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                            Text("  Share")
                        }
                    }
                }
                c != null -> {
                    Text("This invite expired.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { gen++ }) { Text("New invite") }
                }
                error == null -> Text("Loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// scans changes with every scan result, so the same invite can be tried again after an error
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanInviteScreen(code: String?, scans: Int, onScan: () -> Unit, onAdded: (Long) -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.app
    // saved, so recreating the screen does not redeem the single-use ticket again
    var added by rememberSaveable(code, scans) { mutableStateOf<Long?>(null) }
    var error by rememberSaveable(code, scans) { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(code, scans) {
        if (code == null || added != null || error != null) return@LaunchedEffect
        runCatching { app.repo.enterCode(code) }
            .onSuccess { added = it.first }
            .onFailure { error = it.message ?: "Couldn't add this contact." }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Add contact") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val num = added
            val e = error
            when {
                num != null -> {
                    val name = app.repo.name(num)
                    Text("Waiting for $name to accept.")
                    Text(app.repo.words(num).joinToString(" "), style = MaterialTheme.typography.titleLarge)
                    val hours = maxOf(1L, Math.round(((app.repo.contact(num)?.s?.expiresAt ?: 0L) - app.repo.serverNow()) / 3600.0))
                    Text(
                        "Check that $name sees the same words. The request ends in ${if (hours == 1L) "1 hour" else "$hours hours"}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onAdded(num) }) { Text("Done") }
                }
                e != null -> {
                    Text(e, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onScan) { Text("Scan again") }
                }
                else -> Text("Adding...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ContactPrompts(onOpen: (Long) -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val prompts by app.repo.prompts.collectAsStateWithLifecycle()
    val groupPrompts by app.repo.groupPrompts.collectAsStateWithLifecycle()
    val unreachable by app.repo.unreachable.collectAsStateWithLifecycle()
    val contacts by app.repo.contacts.collectAsStateWithLifecycle()

    val p = prompts.firstOrNull()
    if (p != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${p.name.ifBlank { "Someone" }} wants to add you") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(p.words.joinToString(" "), style = MaterialTheme.typography.titleLarge)
                    Text(if (p.name.isBlank()) "Check that they see the same words." else "Check that ${p.name} sees the same words.")
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { app.repo.acceptPrompt(p) } }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { scope.launch { app.repo.declinePrompt(p) } }) { Text("Decline") } },
        )
        return
    }
    groupPrompts.firstOrNull()?.let {
        GroupInvitation(it, onOpen)
        return
    }
    val c = unreachable?.let { n -> contacts.find { it.num == n } } ?: return
    AlertDialog(
        onDismissRequest = { app.repo.keepTrying() },
        title = { Text("Can't reach ${app.repo.name(c.num)}") },
        text = { Text("Their server (${hostOf(c.server)}) isn't answering. Messages will wait and be sent when it's back.") },
        confirmButton = { TextButton(onClick = { app.repo.keepTrying() }) { Text("Keep trying") } },
        dismissButton = { TextButton(onClick = { scope.launch { app.repo.removeContact(c.num) } }) { Text("Remove contact") } },
    )
}
