@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.butan.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.butan.BuildConfig
import dev.butan.core.Butan
import dev.butan.core.Identity
import dev.butan.data.AppState
import dev.butan.data.Contact
import dev.butan.data.InboxMessage
import dev.butan.data.QueueItem
import dev.butan.nfc.NfcGateway
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Destination(val label: String, val icon: ImageVector) {
    Inbox("Inbox", Icons.Default.MailOutline),
    Contacts("Keys", Icons.Default.Person),
    Beam("Sync", Icons.Default.Share),
}

@Composable
fun ButanRoot(nfc: NfcGateway) {
    val viewModel: ButanViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val beam by viewModel.beam.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var destination by rememberSaveable { mutableStateOf(Destination.Inbox) }
    var composing by remember { mutableStateOf<Contact?>(null) }
    var editingName by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var focused by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        focused = true
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        focused = false
        viewModel.onBlur(nfc)
    }

    val view = LocalView.current
    DisposableEffect(focused, nfc.enabled) {
        view.keepScreenOn = focused && nfc.enabled
        onDispose { view.keepScreenOn = false }
    }

    val current = state
    LaunchedEffect(focused, current != null) {
        if (focused && current != null) viewModel.onFocus(nfc)
    }

    if (current == null) {
        LoadingScreen()
        return
    }

    val activeContact = composing
    if (activeContact != null) {
        BackHandler { composing = null }
        ComposeScreen(
            contact = activeContact,
            onBack = { composing = null },
            onSend = { body ->
                viewModel.send(activeContact, body) {
                    composing = null
                    destination = Destination.Beam
                }
            },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark()
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Butan",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (nfc.enabled) "NFC ready • carry-and-forward" else "NFC is off",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { editingName = true }) {
                        Icon(Icons.Default.Person, contentDescription = "Edit name")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (destination) {
            Destination.Inbox -> InboxScreen(
                state = current,
                contentPadding = innerPadding,
                onDelete = viewModel::deleteMessage,
            )

            Destination.Contacts -> ContactsScreen(
                state = current,
                contentPadding = innerPadding,
                onCompose = { composing = it },
                onEditName = { editingName = true },
            )

            Destination.Beam -> BeamScreen(
                state = current,
                beam = beam,
                nfc = nfc,
                contentPadding = innerPadding,
            )
        }
    }

    if (editingName) {
        NameDialog(
            initial = current.profileName,
            onDismiss = { editingName = false },
            onSave = {
                viewModel.updateName(it)
                editingName = false
            },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(64.dp))
    }
}

@Composable
private fun BrandMark() {
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(MaterialTheme.colorScheme.primary, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun InboxScreen(
    state: AppState,
    contentPadding: PaddingValues,
    onDelete: (String) -> Unit,
) {
    if (state.inbox.isEmpty()) {
        EmptyState(
            icon = Icons.Default.MailOutline,
            title = "No messages yet",
            body = "A message arrives when two phones touch. If an envelope is not addressed to you, your phone carries it to the next phone.",
            contentPadding = contentPadding,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Inbox",
                detail = "${state.inbox.size} single message${if (state.inbox.size == 1) "" else "s"}",
            )
        }
        items(state.inbox, key = { it.id }) { message ->
            MessageCard(message = message, onDelete = { onDelete(message.id) })
        }
    }
}

@Composable
private fun MessageCard(message: InboxMessage, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = message.fromName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (message.verified) "Verified" else "Unverified") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (message.verified) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Warning
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyLarge,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete message")
                }
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    state: AppState,
    contentPadding: PaddingValues,
    onCompose: (Contact) -> Unit,
    onEditName: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MyCard(
                name = state.profileName,
                identity = state.identity,
                onEditName = onEditName,
            )
        }

        item {
            SectionHeader(
                title = "Contacts",
                detail = if (state.contacts.isEmpty()) "Tap two phones to exchange keys" else "${state.contacts.size} key${if (state.contacts.size == 1) "" else "s"}",
            )
        }

        if (state.contacts.isEmpty()) {
            item {
                InfoCard(
                    title = "Exchange keys with NFC",
                    body = "Just touch two Butan phones together. Contact cards and all queued envelopes are exchanged automatically while the app is open.",
                )
            }
        } else {
            items(state.contacts, key = { it.age }) { contact ->
                ContactRow(contact = contact, onCompose = { onCompose(contact) })
            }
        }

        item {
            InfoCard(
                title = "What is stored",
                body = "A contact card contains an age recipient and an Ed25519 verification key, signed by the contact. Messages are encrypted with age and signed, so relays cannot read or alter them.",
            )
        }
    }
}

@Composable
private fun MyCard(
    name: String,
    identity: Identity,
    onEditName: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "My contact card",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                IconButton(onClick = onEditName) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit display name",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Text(
                text = shortKey(identity.ageRecipient),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "fingerprint ${identity.fingerprint}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Text(
                text = "Shared automatically when two Butan phones sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onCompose: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = shortKey(contact.age),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onCompose) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Message")
            }
        }
    }
}

@Composable
private fun BeamScreen(
    state: AppState,
    beam: BeamState,
    nfc: NfcGateway,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { NfcStateCard(nfc) }

        item {
            Text(
                text = "Butan ${BuildConfig.VERSION_NAME} • ISO-DEP transport",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            BeamStatusCard(beam = beam)
        }

        if (state.queue.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Carry queue",
                    detail = "${state.queue.size} waiting • sends with the next sync",
                )
            }
            items(state.queue.sortedBy { it.addedAt }, key = { it.id }) { item ->
                QueueRow(item)
            }
        }

        item {
            InfoCard(
                title = "Touch to sync",
                body = "Phones alternate between reading and card emulation. Each bundle contains your contact card and all queued envelopes. A phone that is not the recipient keeps the envelope and forwards it on the next sync.",
            )
        }

        item {
            InfoCard(
                title = "Store and forward",
                body = "Every phone holds a small queue. A message addressed to someone else is kept and offered to the next phone. The receiver verifies the age header MAC before accepting the file key, so relaying does not expose the message.",
            )
        }
    }
}

@Composable
private fun NfcStateCard(nfc: NfcGateway) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(nfc.enabled) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = nfc.enabled
    }

    val available = nfc.available
    val container = when {
        !available -> MaterialTheme.colorScheme.errorContainer
        enabled -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when {
        !available -> MaterialTheme.colorScheme.onErrorContainer
        enabled -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (enabled) Icons.Default.Phone else Icons.Default.Warning,
                contentDescription = null,
                tint = content,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        !available -> "No NFC hardware"
                        enabled -> "NFC is on"
                        else -> "NFC is off"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
                Text(
                    text = when {
                        !available -> "This messenger needs two NFC phones."
                        enabled -> "Ready for phone-to-phone taps."
                        else -> "Enable NFC to send, receive or relay."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
            if (available && !enabled) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }) {
                    Text("Settings", color = content)
                }
            }
        }
    }
}

@Composable
private fun BeamStatusCard(beam: BeamState) {
    val progress = beam.progress
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (progress != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress != null) {
                    LoadingIndicator(Modifier.size(42.dp))
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = beam.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = beam.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (item.mine) Icons.AutoMirrored.Filled.Send else Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.mine) "Your envelope" else "Carrying for another phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ComposeScreen(
    contact: Contact,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var body by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("To ${contact.name}")
                        Text(
                            text = shortKey(contact.age),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InfoCard(
                title = "Single-use envelope",
                body = "The message is encrypted with age to ${contact.name} and signed with your key. It is queued as one envelope and travels through NFC relays.",
            )

            OutlinedTextField(
                value = body,
                onValueChange = {
                    if (it.toByteArray(Charsets.UTF_8).size <= Butan.MAX_BODY_BYTES) body = it
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("Message") },
                placeholder = { Text("Write something…") },
                supportingText = {
                    val bytes = body.toByteArray(Charsets.UTF_8).size
                    Text("$bytes / ${Butan.MAX_BODY_BYTES} bytes")
                },
                shape = MaterialTheme.shapes.large,
            )

            Button(
                onClick = { onSend(body.trim()) },
                enabled = body.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Encrypt and queue")
            }
        }
    }
}

@Composable
private fun NameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your display name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "This name is included in contact cards and message envelopes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        if (it.length <= Butan.MAX_NAME_CHARS) name = it
                    },
                    singleLine = true,
                    label = { Text("Name") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    contentPadding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun shortKey(value: String): String =
    if (value.length <= 30) value else "${value.take(18)}…${value.takeLast(8)}"

private fun formatTimestamp(seconds: Long): String =
    Instant.ofEpochSecond(seconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
