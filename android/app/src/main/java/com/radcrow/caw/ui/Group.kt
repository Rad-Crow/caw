package com.radcrow.caw.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radcrow.caw.CawApp
import com.radcrow.caw.GroupPrompt
import com.radcrow.caw.app
import kotlinx.coroutines.launch

private fun failed(app: CawApp, e: Throwable) = Toast.makeText(app, e.message ?: "Something went wrong.", Toast.LENGTH_LONG).show()

private const val SHOWN = 50

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewGroupScreen(group: Long?, onDone: (Long?) -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val contacts by app.repo.contacts.collectAsStateWithLifecycle()
    val groups by app.repo.groups.collectAsStateWithLifecycle()
    val messages by app.repo.messages.collectAsStateWithLifecycle()
    val config by app.repo.config.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<Long>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pool = remember(contacts, groups) { (if (group != null) app.repo.invitable(group) else contacts.filter { it.status == "active" }.map { it.num }).toSet() }
    val matches = remember(pool, messages, query) { app.repo.contactIds(query).filter { it in pool } }
    val shown = matches.take(SHOWN)
    val room = remember(groups, config) { app.repo.groupRoom(group) }
    val full = picked.size >= room
    BackHandler(onBack = onBack)

    fun toggle(num: Long) {
        if (num in picked) picked = picked - num else if (!full) picked = picked + num
    }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                if (group != null) {
                    app.repo.inviteToGroup(group, picked)
                    onDone(null)
                } else {
                    onDone(app.repo.createGroup(name, picked))
                }
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(if (group != null) "Add members" else "New group") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (group == null) {
                item { OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("Group name") }, singleLine = true) }
            }
            if (picked.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        picked.forEach { num ->
                            val n = app.repo.name(num)
                            InputChip(
                                selected = true,
                                onClick = { toggle(num) },
                                label = { Text(n) },
                                trailingIcon = { Icon(Icons.Default.Close, "Remove $n", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    query, { query = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("Search contacts") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                )
            }
            items(shown, key = { it }) { num ->
                val on = num in picked
                val n = app.repo.name(num)
                val enabled = on || !full
                ListItem(
                    modifier = Modifier.clickable(enabled = enabled) { toggle(num) }.alpha(if (enabled) 1f else 0.45f),
                    leadingContent = { Avatar(n) },
                    headlineContent = { Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(app.repo.peer(num)?.host.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        if (on) Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Default.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
            if (shown.isEmpty()) {
                item { Text(if (query.isNotEmpty()) "No matches." else "No contacts to invite.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (matches.size > shown.size) {
                item { Text("Search to find more contacts.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                Text(
                    if (full) "Groups on this server can have up to ${config.maxGroupMembers} members."
                    else "Contacts join after they accept. Members can see each other's names and servers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = ::submit, enabled = !busy && if (group != null) picked.isNotEmpty() else name.isNotBlank()) {
                    Text(if (busy) "Saving..." else if (group != null) "Invite" else "Create group")
                }
            }
        }
    }
}

// changes run in the app scope so closing the dialog does not cancel them
@Composable
fun GroupDialog(num: Long, onAddMembers: () -> Unit, onClose: () -> Unit) {
    val app = LocalContext.current.app
    val groups by app.repo.groups.collectAsStateWithLifecycle()
    val contacts by app.repo.contacts.collectAsStateWithLifecycle()
    val g = groups.find { it.num == num } ?: return
    val joined = g.g.inviter == null
    var draft by remember { mutableStateOf(app.repo.name(num)) }
    var removing by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val members = remember(groups, contacts) { app.repo.groupMembers(num) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Group") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (joined) {
                    OutlinedTextField(draft, { draft = it.take(64) }, label = { Text("Name") }, singleLine = true)
                    Text("Members", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    members.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name)
                                if (!m.me) {
                                    Text(
                                        m.host + if (m.contact) " \u00b7 Contact" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!m.me) {
                                TextButton(onClick = {
                                    if (removing != m.id) {
                                        removing = m.id
                                    } else {
                                        removing = null
                                        app.scope.launch { runCatching { app.repo.removeMember(num, m.id) }.onFailure { failed(app, it) } }
                                    }
                                }) { Text(if (removing == m.id) "Tap again" else "Remove", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    TextButton(onClick = {
                        onClose()
                        onAddMembers()
                    }) {
                        Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                        Text("  Add members")
                    }
                }
                TextButton(onClick = {
                    if (!leaving) {
                        leaving = true
                    } else {
                        leaving = false
                        onClose()
                        app.scope.launch { runCatching { app.repo.leaveGroup(num) }.onFailure { failed(app, it) } }
                    }
                }) { Text(if (leaving) "Tap again to leave" else "Leave group", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (joined) {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    onClose()
                    app.scope.launch { runCatching { app.repo.renameGroup(num, draft) }.onFailure { failed(app, it) } }
                }) { Text("Save") }
            }
        },
    )
}

@Composable
fun GroupInvitation(p: GroupPrompt, onOpen: (Long) -> Unit) {
    val app = LocalContext.current.app
    var busy by remember(p.g) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${p.from} invited you to ${p.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Members: ${p.members.joinToString(", ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Members can see your name and server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                app.scope.launch {
                    runCatching { app.repo.acceptGroup(p) }.onSuccess(onOpen).onFailure { failed(app, it) }
                    busy = false
                }
            }) { Text("Join") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { app.scope.launch { app.repo.declineGroup(p) } }) { Text("Decline") } },
    )
}
