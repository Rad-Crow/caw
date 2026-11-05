package com.radcrow.caw.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radcrow.caw.CawEvent
import com.radcrow.caw.Message
import com.radcrow.caw.app
import com.radcrow.caw.Contact
import com.radcrow.caw.fmtLeft
import com.radcrow.caw.fmtTtl
import com.radcrow.caw.sealed.OwnDevice
import com.radcrow.caw.sealed.Plain
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun fmtTime(ts: Long): String {
    val d = Date(ts * 1000)
    val sameDay = DateFormat.getDateInstance().format(d) == DateFormat.getDateInstance().format(Date())
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(d)
}

private val FavRed = Color(0xFFD93025)

private val THEMES = linkedMapOf("system" to "System", "light" to "Light", "dark" to "Dark")

private val PRESETS = listOf(300L, 3600L, 8 * 3600L, 86400L, 3 * 86400L, 7 * 86400L, 30 * 86400L)

@Composable
fun Root(activity: MainActivity) {
    val app = LocalContext.current.app
    val me by app.repo.me.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("home") }
    var chatWith by rememberSaveable { mutableStateOf<Long?>(null) }
    var setupBack by rememberSaveable { mutableStateOf("home") }
    val openUser by activity.openUser.collectAsStateWithLifecycle()

    LaunchedEffect(openUser) {
        openUser?.let {
            chatWith = it
            activity.openUser.value = null
        }
    }
    LaunchedEffect(me?.id) {
        val m = me ?: return@LaunchedEffect
        activity.afterLogin()
        if (!app.prefs.setupShown(m.id)) {
            setupBack = "home"
            screen = "setup"
        }
    }

    if (me != null) ContactPrompts { chatWith = it }

    when {
        me == null -> LoginScreen()
        me?.mustChange == true -> ChoosePasswordScreen()
        chatWith != null -> ChatScreen(chatWith!!, activity, onDevices = {
            chatWith = null
            screen = "settings"
        }) { chatWith = null }
        screen == "setup" -> SetupScreen(activity, onBack = { screen = setupBack }) {
            me?.let { app.prefs.markSetupShown(it.id) }
            screen = setupBack
        }
        screen == "settings" -> SettingsScreen(
            activity,
            onBack = { screen = "home" },
            onSetup = {
                setupBack = "settings"
                screen = "setup"
            },
        )
        screen == "add" -> AddContactScreen(
            onAdded = {
                screen = "home"
                chatWith = it
            },
            onBack = { screen = "home" },
        )
        else -> HomeScreen(
            activity,
            onOpen = { chatWith = it },
            onMessage = {
                app.repo.composeFor.value = it
                chatWith = it
            },
            onAdd = { screen = "add" },
            onSettings = { screen = "settings" },
            onSetup = {
                setupBack = "home"
                screen = "setup"
            },
        )
    }
}

@Composable
fun LoginScreen() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf(app.prefs.server ?: "https://") }
    var username by rememberSaveable { mutableStateOf(app.prefs.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                app.repo.signIn(server.trim().trimEnd('/'), username.trim(), password)
            } catch (e: Exception) {
                error = e.message ?: "Login failed"
            } finally {
                busy = false
            }
        }
    }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Caw", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.size(24.dp))
            OutlinedTextField(
                server, { server = it },
                label = { Text("Server") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                username, { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                password, { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.size(16.dp))
            Button(
                onClick = ::submit,
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Signing in..." else "Sign in") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    activity: MainActivity,
    onOpen: (Long) -> Unit,
    onMessage: (Long) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
) {
    val setup = rememberSetupState()
    val app = LocalContext.current.app
    val contacts by app.repo.contacts.collectAsStateWithLifecycle()
    val messages by app.repo.messages.collectAsStateWithLifecycle()
    val opened by app.repo.opened.collectAsStateWithLifecycle()
    val seen by app.repo.seen.collectAsStateWithLifecycle()
    val favorites by app.repo.favorites.collectAsStateWithLifecycle()
    val calls by app.calls.active.collectAsStateWithLifecycle()
    val connected by app.socket.connected.collectAsStateWithLifecycle()
    val deviceError by app.repo.deviceError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }

    val all = remember(messages, opened, contacts, seen, favorites, query) { app.repo.contactIds(query) }
    val ids = if (showAll || query.isNotBlank()) all else all.take(LIMIT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(app.prefs.serverName)
                        Box(
                            Modifier.padding(start = 8.dp).size(8.dp)
                                .background(if (connected) Online else MaterialTheme.colorScheme.error, CircleShape),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.PersonAdd, null) },
                text = { Text("Add contact") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            deviceError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            AccountNotices()
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search contacts") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                item { SetupNotice(setup, onSetup) }
                items(ids, key = { it }) { id ->
                    val u = app.repo.peer(id) ?: return@items
                    val active = contacts.find { it.num == id }?.status == "active"
                    val (preview, missed) = app.repo.preview(id)
                    val unread = app.repo.unread(id)
                    val last = messages[id]?.lastOrNull()?.raw?.createdAt
                    ListItem(
                        modifier = Modifier.clickable { onOpen(id) },
                        leadingContent = { Avatar(u.name) },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (id in favorites) Icon(Icons.Default.Favorite, "Favorite", Modifier.padding(start = 4.dp).size(14.dp), tint = FavRed)
                                last?.let { Text("  ${fmtTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        },
                        supportingContent = {
                            Text(
                                preview,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (missed) MaterialTheme.colorScheme.error else Color.Unspecified,
                                fontWeight = if (unread > 0) FontWeight.SemiBold else null,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (unread > 0) {
                                    Box(
                                        Modifier.padding(end = 4.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) { Text("$unread", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                                }
                                IconButton(onClick = { onMessage(id) }) { Icon(Icons.AutoMirrored.Filled.Message, "Message ${u.name}") }
                                IconButton(onClick = { activity.call(u) }, enabled = calls == null && active) {
                                    Icon(Icons.Default.Call, "Call ${u.name}")
                                }
                            }
                        },
                    )
                }
                if (ids.isEmpty()) item { Text(if (query.isBlank()) "No contacts yet." else "No matches.", Modifier.padding(24.dp)) }
                if (!showAll && query.isBlank() && all.size > LIMIT) {
                    item { TextButton(onClick = { showAll = true }, Modifier.fillMaxWidth()) { Text("Show all (${all.size})") } }
                }
                item { Spacer(Modifier.size(24.dp)) }
            }
        }
    }
}

private const val LIMIT = 50

private sealed interface Item {
    val at: Long

    data class N(val n: Message) : Item {
        override val at get() = n.raw.createdAt
    }

    data class E(val e: CawEvent) : Item {
        override val at get() = e.at
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(userId: Long, activity: MainActivity, onDevices: () -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val contacts by app.repo.contacts.collectAsStateWithLifecycle()
    val messages by app.repo.messages.collectAsStateWithLifecycle()
    val events by app.repo.events.collectAsStateWithLifecycle()
    val config by app.repo.config.collectAsStateWithLifecycle()
    val drafts by app.repo.drafts.collectAsStateWithLifecycle()
    val clock by app.repo.clock.collectAsStateWithLifecycle()
    val me by app.repo.me.collectAsStateWithLifecycle()
    val favorites by app.repo.favorites.collectAsStateWithLifecycle()
    val composeFor by app.repo.composeFor.collectAsStateWithLifecycle()
    val attachments by app.repo.attachments.collectAsStateWithLifecycle()
    val attachment = attachments[userId]
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { app.repo.attach(userId, uri) }
                    .onFailure { Toast.makeText(activity, it.message ?: "Could not attach the file.", Toast.LENGTH_LONG).show() }
            }
        }
    }
    val focus = remember { FocusRequester() }
    val user = contacts.find { it.num == userId }?.let { app.repo.peer(it) }
    val name = user?.name ?: ""
    val contact = contacts.find { it.num == userId }
    val ttl = contact?.ttl ?: config.messageTtlDefault
    val pendingContact = contact?.status == "pending"
    val canSend = contact?.status == "active"
    val draft = drafts[userId].orEmpty()
    var timerOpen by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf<List<String>?>(null) }
    var wordsFailed by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var wordsOpen by remember { mutableStateOf(false) }
    var contactOpen by remember { mutableStateOf(false) }
    val aliases by app.repo.aliases.collectAsStateWithLifecycle()
    val list = rememberLazyListState()

    val outbox by app.repo.outbox.collectAsStateWithLifecycle()
    val shown = messages[userId].orEmpty()
    val pending = remember(outbox, shown) { app.repo.pendingFor(userId, shown) }
    val items = ((shown + pending).map { Item.N(it) } + events[userId].orEmpty().map { Item.E(it) }).sortedBy { it.at }

    BackHandler(onBack = onBack)
    LaunchedEffect(userId) {
        app.repo.openChat = userId
        app.repo.open(userId)
    }
    LaunchedEffect(messages[userId]?.size) { app.repo.markSeen(userId) }
    LaunchedEffect(Unit) {
        try {
            awaitCancellation()
        } finally {
            app.repo.openChat = null
        }
    }
    LaunchedEffect(composeFor, canSend) {
        if (composeFor == userId && canSend) {
            runCatching { focus.requestFocus() }
            app.repo.composeFor.value = null
        }
    }
    LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(0) }

    if (contactOpen) {
        var draftName by remember { mutableStateOf(name) }
        val aliased = userId in aliases
        AlertDialog(
            onDismissRequest = { contactOpen = false },
            title = { Text("Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draftName, { draftName = it.take(64) }, label = { Text("Name") }, singleLine = true)
                    Text(
                        user?.host.orEmpty() + if (aliased) " - their name ${app.repo.accountName(userId)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        if (!removing) {
                            removing = true
                        } else {
                            contactOpen = false
                            scope.launch { app.repo.removeContact(userId) }
                            onBack()
                        }
                    }) { Text(if (removing) "Tap again to remove" else "Remove contact", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = draftName.isNotBlank(), onClick = {
                    app.repo.setAlias(userId, draftName)
                    contactOpen = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(enabled = aliased, onClick = {
                    app.repo.setAlias(userId, "")
                    contactOpen = false
                }) { Text("Reset") }
            },
        )
    }

    LaunchedEffect(wordsOpen) {
        if (!wordsOpen || words != null) return@LaunchedEffect
        wordsFailed = false
        runCatching { app.repo.words(userId) }
            .onSuccess { words = it }
            .onFailure {
                Log.w("caw.words", "verification words", it)
                wordsFailed = true
            }
    }

    if (wordsOpen) {
        AlertDialog(
            onDismissRequest = { wordsOpen = false },
            icon = { Icon(Icons.Default.VerifiedUser, null) },
            title = { Text("Verification words") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        words?.joinToString(" ") ?: if (wordsFailed) "Could not load the words." else "Loading...",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("If $name sees the same words, your connection is verified.")
                }
            },
            confirmButton = { TextButton(onClick = { wordsOpen = false }) { Text("Done") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column(Modifier.clickable { contactOpen = true }) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (pendingContact) "Waiting to accept" else user?.host.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { app.repo.toggleFavorite(userId) }) {
                        if (userId in favorites) Icon(Icons.Default.Favorite, "Remove favorite", tint = FavRed)
                        else Icon(Icons.Default.FavoriteBorder, "Add favorite")
                    }
                    Box {
                        TextButton(onClick = { timerOpen = true }) {
                            Icon(Icons.Default.Timer, null, Modifier.size(18.dp))
                            Text(" ${fmtTtl(ttl)}")
                        }
                        DropdownMenu(timerOpen, { timerOpen = false }) {
                            Text("Expire after", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                            PRESETS.filter { it in config.messageTtlMin..config.messageTtlMax }.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(fmtTtl(p), fontWeight = if (p == ttl) FontWeight.Bold else null) },
                                    onClick = {
                                        timerOpen = false
                                        if (p != ttl) scope.launch { runCatching { app.repo.setTimer(userId, p) } }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { wordsOpen = true }) { Icon(Icons.Default.VerifiedUser, "Verification words") }
                    if (user != null) IconButton(onClick = { activity.call(user) }) { Icon(Icons.Default.Call, "Call") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            DeviceAlert(userId, name, onDevices)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items.asReversed(), key = { if (it is Item.N) it.n.raw.id else "e${it.at}${(it as Item.E).e.text.hashCode()}" }) { it ->
                    when (it) {
                        is Item.E -> EventPill(it.e.text, null)
                        is Item.N -> {
                            val n = it.n
                            val p = n.plain
                            if (p?.t == "call") {
                                val missed = p.outcome == "missed" && n.raw.fromUser == userId
                                val icon = when {
                                    missed -> Icons.AutoMirrored.Filled.CallMissed
                                    n.raw.fromUser == userId -> Icons.AutoMirrored.Filled.CallReceived
                                    else -> Icons.AutoMirrored.Filled.CallMade
                                }
                                EventPill("${app.repo.callText(n)} - ${fmtTime(n.raw.createdAt)}", icon, missed)
                            } else {
                                Bubble(n, p, n.raw.fromUser == me?.id, clock, contact)
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            " End-to-end encrypted. Messages expire.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
            if (pendingContact) {
                Text(
                    "Waiting for $name to accept.",
                    Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            attachment?.let { a -> AttachmentBar(a) { app.repo.detach(userId) } }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    enabled = canSend && attachment == null,
                    onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                ) { Icon(Icons.Default.AttachFile, "Attach photo or video") }
                OutlinedTextField(
                    draft,
                    { v -> app.repo.drafts.value = app.repo.drafts.value + (userId to v) },
                    Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(if (canSend) "Message to $name" else "Unavailable") },
                    enabled = canSend,
                    maxLines = 5,
                )
                IconButton(
                    enabled = canSend && if (attachment != null) attachment.status == "ready" else draft.isNotBlank(),
                    onClick = {
                        val text = draft.trim()
                        app.repo.drafts.value = app.repo.drafts.value + (userId to "")
                        scope.launch {
                            runCatching {
                                if (attachment != null) app.repo.sendMedia(userId, text) else app.repo.send(userId, Plain("text", text = text))
                            }.onFailure {
                                app.repo.drafts.value = app.repo.drafts.value + (userId to text)
                                Toast.makeText(activity, "Message not sent: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun EventPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, missed: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            icon?.let { Icon(it, null, Modifier.size(16.dp), tint = color) }
            Text(" $text", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
private fun Bubble(n: Message, p: Plain?, mine: Boolean, clock: Long, contact: Contact?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (p?.t == "media") MediaBubble(p, if (mine) contact else null)
        if (p?.t != "media" || !p.text.isNullOrBlank()) Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                p?.text ?: "Not readable on this device",
                color = if (mine) MaterialTheme.colorScheme.onPrimary else Color.Unspecified,
            )
        }
        Text(
            if (n.pending) "Sending..." else "${fmtTime(n.raw.createdAt)} - ${fmtLeft(n.raw.expiresAt - clock)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val NTFY = "io.heckel.ntfy"

// F-Droid app first, then any store handling market://, then the F-Droid web page
fun installNtfy(ctx: android.content.Context) {
    val market = Uri.parse("market://details?id=$NTFY")
    val tries = listOf(
        Intent(Intent.ACTION_VIEW, market).setPackage("org.fdroid.fdroid"),
        Intent(Intent.ACTION_VIEW, market),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$NTFY/")),
    )
    for (i in tries) {
        try {
            ctx.startActivity(i)
            return
        } catch (e: android.content.ActivityNotFoundException) {
            continue
        }
    }
}

fun appLabel(ctx: android.content.Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PrivateSwitch(
    activity: MainActivity,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String,
    initial: Boolean,
    save: (Boolean) -> Unit,
) {
    var on by remember { mutableStateOf(initial) }
    val toggle = { v: Boolean ->
        activity.authenticate("${if (v) "Hide" else "Show"} ${title.lowercase()} in notifications") {
            save(v)
            on = v
        }
    }
    ListItem(
        modifier = Modifier.clickable { toggle(!on) },
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        trailingContent = { Switch(checked = on, onCheckedChange = toggle) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(activity: MainActivity, onBack: () -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val me by app.repo.me.collectAsStateWithLifecycle()
    val devices by app.repo.ownDevices.collectAsStateWithLifecycle()
    val setup = rememberSetupState()
    var changing by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { runCatching { app.repo.refreshDevices() } }
    if (changing) ChangePasswordDialog(rotate = false, title = "Change password", text = null) { changing = false }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionTitle("Account") }
            item {
                ListItem(
                    leadingContent = { Avatar(me?.displayName.orEmpty()) },
                    headlineContent = { Text(me?.displayName.orEmpty()) },
                    supportingContent = { Text("@${me?.username} - ${Uri.parse(app.prefs.server.orEmpty()).host.orEmpty()}") },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { changing = true }) { Text("Change password") }
                    OutlinedButton(onClick = { app.logout() }) { Text("Sign out") }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Setup") }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onSetup),
                    leadingContent = {
                        if (setup.complete) Icon(Icons.Default.CheckCircle, null, tint = Online)
                        else Icon(Icons.Default.Settings, null)
                    },
                    headlineContent = { Text("Setup") },
                    supportingContent = {
                        Text(if (setup.complete) "Complete" else "${setup.pending} ${if (setup.pending == 1) "item needs" else "items need"} attention")
                    },
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Appearance") }
            item {
                val theme by app.theme.collectAsStateWithLifecycle()
                var open by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { open = true },
                    leadingContent = { Icon(Icons.Default.Palette, null) },
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(THEMES.getValue(theme)) },
                )
                if (open) {
                    AlertDialog(
                        onDismissRequest = { open = false },
                        title = { Text("Theme") },
                        text = {
                            Column {
                                THEMES.forEach { (value, label) ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            app.prefs.theme = value
                                            app.theme.value = value
                                            open = false
                                        }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = theme == value, onClick = null)
                                        Text(label, Modifier.padding(start = 12.dp))
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Security") }
            item {
                val available = remember { AppLock.available(ctx) }
                var lock by remember { mutableStateOf(app.prefs.appLock) }
                val toggle = { v: Boolean ->
                    activity.authenticate(if (v) "Turn on app lock" else "Turn off app lock") {
                        app.prefs.appLock = v
                        lock = v
                    }
                }
                ListItem(
                    modifier = Modifier.clickable(enabled = available || lock) { toggle(!lock) },
                    leadingContent = { Icon(Icons.Default.Lock, null) },
                    headlineContent = { Text("App lock") },
                    supportingContent = {
                        Text(if (available || lock) "Require the device screen lock to open Caw and answer calls." else "Set a screen lock on this device to use app lock.")
                    },
                    trailingContent = {
                        Switch(
                            checked = lock,
                            enabled = available || lock,
                            onCheckedChange = toggle,
                        )
                    },
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Private notifications") }
            item {
                PrivateSwitch(activity, Icons.Default.Call, "Calls", "Hide who's calling.", app.prefs.privateCalls) {
                    app.prefs.privateCalls = it
                }
            }
            item {
                PrivateSwitch(activity, Icons.AutoMirrored.Filled.Message, "Messages", "Hide who sent a message and what it says.", app.prefs.privateMessages) {
                    app.prefs.privateMessages = it
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Devices") }
            item { Text("As reported by this server. Messages are encrypted for each of these devices.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(devices, key = { it.id }) { d ->
                val self = d.id == app.sealed.device?.id
                ListItem(
                    leadingContent = { Icon(Icons.Default.Devices, null) },
                    headlineContent = { Text(d.label.ifBlank { "Device" } + if (self) " (this device)" else "") },
                    supportingContent = { Text("Last seen ${fmtTime(d.lastSeen)}") },
                    trailingContent = {
                        TextButton(onClick = {
                            scope.launch { runCatching { app.repo.removeDevice(d.id) } }
                        }) { Text("Remove") }
                    },
                )
            }
        }
    }
}
