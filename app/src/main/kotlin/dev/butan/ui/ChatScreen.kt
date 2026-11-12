@file:OptIn(ExperimentalMaterial3Api::class)

package dev.butan.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import dev.butan.core.Butan
import dev.butan.data.AppState
import dev.butan.data.ChatMessage
import dev.butan.data.Contact
import dev.butan.data.QueueItem
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    state: AppState,
    peerAge: String,
    onBack: () -> Unit,
    onSend: (String, (Boolean) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onRetry: (String) -> Unit,
    onMarkRead: (String) -> Unit,
) {
    BackHandler(onBack = onBack)

    val contact = state.contacts.firstOrNull { it.age == peerAge }
    val messages = remember(state.messages, peerAge) {
        state.messages.filter { it.peerAge == peerAge }.sortedByDescending { it.receivedAt }
    }
    val peerName = contact?.name ?: messages.firstOrNull()?.peerName ?: "Неизвестный"
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    var draft by rememberSaveable(peerAge) { mutableStateOf("") }
    var sending by remember(peerAge) { mutableStateOf(false) }
    var showInfo by rememberSaveable(peerAge) { mutableStateOf(false) }

    val notify: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    LaunchedEffect(peerAge, messages.size) { onMarkRead(peerAge) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    fun submit() {
        val body = draft.trim()
        if (body.isEmpty() || sending) return
        sending = true
        onSend(body) { ok ->
            sending = false
            if (ok) {
                draft = ""
            } else {
                notify("Не удалось отправить сообщение")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ChatTopBar(
                peerName = peerName,
                peerAge = peerAge,
                contact = contact,
                onBack = onBack,
                onInfo = { showInfo = true },
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = sending,
                onSend = { submit() },
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (messages.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.MailOutline,
                    title = "Начните разговор",
                    body = "Напишите первое сообщение. Оно зашифруется для этого человека и уйдёт " +
                        "при следующем касании телефонами.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        val queueItem = state.queue.firstOrNull { it.messageId == message.id }
                        MessageBubble(
                            message = message,
                            queueItem = queueItem,
                            onCopy = {
                                clipboard.setText(AnnotatedString(message.body))
                                notify("Сообщение скопировано")
                            },
                            onOpenLink = { url ->
                                runCatching { uriHandler.openUri(url) }
                                    .onFailure { notify("Не удалось открыть ссылку") }
                            },
                            onRetry = { queueItem?.let { onRetry(it.id) } },
                            onDelete = { onDelete(message.id) },
                        )
                    }
                    item { ChatIntro() }
                }
            }
        }
    }

    if (showInfo) {
        ContactInfoDialog(
            peerName = peerName,
            peerAge = peerAge,
            contact = contact,
            onDismiss = { showInfo = false },
            onCopyAddress = {
                clipboard.setText(AnnotatedString(peerAge))
                notify("Адрес скопирован")
            },
        )
    }
}

@Composable
private fun ChatTopBar(
    peerName: String,
    peerAge: String,
    contact: Contact?,
    onBack: () -> Unit,
    onInfo: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientAvatar(name = peerName, seed = peerAge, size = 38.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (contact != null) Icons.Default.Lock else Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = if (contact != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = if (contact != null) "Проверенный контакт" else "Ключ не подтверждён",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (contact != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onInfo) {
                Icon(Icons.Default.Info, contentDescription = "Информация о чате")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun ChatIntro() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Сквозное шифрование",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Сообщения хранятся только на ваших телефонах. Передача идёт " +
                        "напрямую через NFC, без интернета и серверов.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    queueItem: QueueItem?,
    onCopy: () -> Unit,
    onOpenLink: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val links = remember(message.body) { extractLinks(message.body) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val outgoing = message.outgoing
    val bubbleColor = if (outgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val shape = if (outgoing) {
        RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    } else {
        RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = shape,
                color = bubbleColor,
                tonalElevation = if (outgoing) 0.dp else 1.dp,
            ) {
                Column(modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
                    SelectionContainer {
                        Text(
                            text = linkify(message.body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MessageStatus(message = message, queueItem = queueItem)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = formatChatTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Действия с сообщением",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Копировать текст") },
                                    onClick = {
                                        menuOpen = false
                                        onCopy()
                                    },
                                )
                                if (links.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Открыть ссылку") },
                                        onClick = {
                                            menuOpen = false
                                            onOpenLink(links.first())
                                        },
                                    )
                                }
                                if (outgoing && queueItem?.passedAt != null) {
                                    DropdownMenuItem(
                                        text = { Text("Вернуть в очередь") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            onRetry()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Удалить сообщение", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Удалить сообщение?") },
            text = {
                Text("Оно исчезнет из этого чата и из очереди передачи. Это действие нельзя отменить.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun MessageStatus(message: ChatMessage, queueItem: QueueItem?) {
    val (text, icon, tint) = when {
        message.outgoing && queueItem?.passedAt != null -> Triple(
            "Передано",
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
        )
        message.outgoing -> Triple(
            "В очереди",
            Icons.Default.Lock,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message.verified -> Triple(
            "Подпись",
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
        )
        else -> Triple(
            "Без подписи",
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = tint,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { value ->
                    if (byteCount(value) <= Butan.MAX_BODY_BYTES) onDraftChange(value)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение…") },
                maxLines = 5,
                shape = RoundedCornerShape(28.dp),
                supportingText = {
                    Text(
                        text = "${byteCount(draft)} / ${Butan.MAX_BODY_BYTES}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank() && !sending,
                modifier = Modifier
                    .padding(bottom = 22.dp)
                    .size(52.dp),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}

@Composable
private fun ContactInfoDialog(
    peerName: String,
    peerAge: String,
    contact: Contact?,
    onDismiss: () -> Unit,
    onCopyAddress: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text(peerName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (contact != null) {
                        "Контакт подтверждён: ключ подписи совпадает с карточкой, которой с вами поделились."
                    } else {
                        "Этот человек ещё не в контактах. Сообщения принимаются, но ключ подписи не подтверждён."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (contact != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                HorizontalDivider()
                Text("Адрес", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = peerAge,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (contact != null) {
                    Text("Ключ подписи", style = MaterialTheme.typography.labelMedium)
                    KeyText(shortKey(contact.signKey))
                } else {
                    Text("Ключ подписи", style = MaterialTheme.typography.labelMedium)
                    KeyText("неизвестен")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyAddress) { Text("Копировать адрес") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

private val urlRegex = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

private fun extractLinks(body: String): List<String> {
    return urlRegex.findAll(body).mapNotNull { match ->
        val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        if (raw.isBlank()) null else normalizeUrl(raw)
    }.toList()
}

private fun normalizeUrl(value: String): String {
    return if (value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
    ) {
        value
    } else {
        "https://$value"
    }
}

@Composable
private fun linkify(body: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    return remember(body, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            urlRegex.findAll(body).forEach { match ->
                if (match.range.first > cursor) {
                    append(body.substring(cursor, match.range.first))
                }
                val raw = match.value
                var end = raw.length
                while (end > 0 && raw[end - 1] in ".,;:!?)]}") end--
                val core = raw.substring(0, end)
                val trailing = raw.substring(end)
                if (core.isNotBlank()) {
                    withLink(
                        LinkAnnotation.Url(
                            url = normalizeUrl(core),
                            styles = TextLinkStyles(style = style),
                        ),
                    ) {
                        append(core)
                    }
                } else {
                    append(raw)
                }
                if (trailing.isNotEmpty()) append(trailing)
                cursor = match.range.last + 1
            }
            if (cursor < body.length) append(body.substring(cursor))
        }
    }
}
