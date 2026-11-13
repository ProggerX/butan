@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.butan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.butan.core.Butan
import dev.butan.data.Contact
import dev.butan.nfc.NfcGateway

@Composable
fun ButanRoot(nfc: NfcGateway) {
    val viewModel: ButanViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val beam by viewModel.beam.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var editingName by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var focused by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { focused = true }
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

    val peer = openPeer
    if (peer != null) {
        val peerName = current.contacts.firstOrNull { it.age == peer }?.name
            ?: current.messages.lastOrNull { it.peerAge == peer }?.peerName
            ?: "Неизвестный"
        ChatScreen(
            state = current,
            peerAge = peer,
            onBack = { openPeer = null },
            onSend = { body, onResult ->
                viewModel.send(peer, peerName, body, onResult)
            },
            onDelete = viewModel::deleteMessage,
            onRequeue = viewModel::requeueMessage,
            onMarkRead = viewModel::markConversationRead,
        )
        return
    }

    val pendingCount = current.queue.size

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 46.dp)
                        Spacer(Modifier.width(13.dp))
                        Column {
                            Text(
                                text = "Butan",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (tab == 0) {
                                    "Чаты и люди рядом"
                                } else {
                                    "Передача дальше"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    NfcPill(enabled = nfc.enabled)
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.MailOutline, contentDescription = null) },
                    label = { Text("Чаты") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingCount > 0) {
                                    Badge { Text("$pendingCount") }
                                }
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                        }
                    },
                    label = { Text("Передача") },
                )
            }
        },
    ) { innerPadding ->
        when (tab) {
            0 -> ChatsScreen(
                state = current,
                contentPadding = innerPadding,
                onOpenChat = { openPeer = it },
                onEditName = { editingName = true },
            )

            else -> TransferScreen(
                state = current,
                beam = beam,
                contentPadding = innerPadding,
                onShareOwn = viewModel::shareOwnContact,
                onShareContact = viewModel::shareContact,
                onDelete = viewModel::deleteTransfer,
            )
        }
    }

    if (editingName) {
        NameDialog(
            initial = current.profileName,
            onDismiss = { editingName = false },
            onSave = { name ->
                viewModel.updateName(name)
                editingName = false
            },
        )
    }
}

@Composable
private fun NfcPill(enabled: Boolean) {
    Surface(
        modifier = Modifier.padding(end = 10.dp),
        shape = CircleShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        shape = CircleShape,
                    ),
            )
            Text(
                text = if (enabled) "NFC" else "NFC выкл",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
        }
    }
}

@Composable
private fun NameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, contentDescription = null) },
        title = { Text("Ваше имя") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Это имя увидят люди, с которыми вы обмениваетесь сообщениями и контактами.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        if (it.length <= Butan.MAX_NAME_CHARS) name = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Имя") },
                    supportingText = {
                        Text("${name.length} / ${Butan.MAX_NAME_CHARS}")
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
