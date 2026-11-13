@file:OptIn(ExperimentalMaterial3Api::class)

package dev.butan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butan.data.AppState
import dev.butan.data.Contact
import dev.butan.data.QueueItem
import dev.butan.data.TransferKind

@Composable
fun TransferScreen(
    state: AppState,
    beam: BeamState,
    contentPadding: PaddingValues,
    onShareOwn: () -> Unit,
    onShareContact: (Contact) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showContacts by rememberSaveable { mutableStateOf(false) }
    val pending = remember(state.queue) { state.queue.sortedBy { it.addedAt } }
    val transfers = remember(state.queue) { state.queue.sumOf { it.passCount } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TransferHero(
                pending = pending.size,
                transfers = transfers,
                contacts = state.contacts.size,
            )
        }
        item { NfcStatusCard(beam) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onShareOwn,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Мой контакт")
                }
                OutlinedButton(
                    onClick = { showContacts = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Другой контакт")
                }
            }
        }

        item {
            SectionHeader(
                title = "В очереди",
                detail = when {
                    pending.isEmpty() -> null
                    pending.size == 1 -> "1 элемент · уходит при касании"
                    else -> "${pending.size} элемента · уходят при касании"
                },
            )
        }

        if (pending.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Send,
                    title = "Очередь пуста",
                    body = "Напишите сообщение в чате или добавьте контакт. Всё появится здесь и уйдёт " +
                        "при следующем касании телефонами.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                )
            }
        } else {
            items(pending, key = { it.id }) { item ->
                TransferCard(
                    item = item,
                    state = state,
                    onDelete = { onDelete(item.id) },
                )
            }
        }

        item {
            InfoCard(
                title = "Передача ничего не удаляет",
                body = "После обмена элемент остаётся в очереди и уйдёт снова при следующем касании. " +
                    "Он также остаётся в чате. Убрать его из очереди можно через меню ⋮ на карточке.",
                icon = Icons.Default.CheckCircle,
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showContacts) {
        ContactPickerDialog(
            state = state,
            onDismiss = { showContacts = false },
            onPick = { contact ->
                showContacts = false
                onShareContact(contact)
            },
        )
    }
}

@Composable
private fun TransferHero(pending: Int, transfers: Int, contacts: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Передайте дальше",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = "Сообщения, транзит и контакты — всё в одной очереди.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferStat("$pending", "в очереди")
                TransferStat("$transfers", "передач")
                TransferStat("$contacts", "контактов")
            }
        }
    }
}

@Composable
private fun TransferStat(value: String, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NfcStatusCard(beam: BeamState) {
    val progress = beam.progress
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = beam.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = beam.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    item: QueueItem,
    state: AppState,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(item.id) { mutableStateOf(false) }
    val accent = when (item.kind) {
        TransferKind.OUTGOING -> MaterialTheme.colorScheme.primaryContainer
        TransferKind.RELAY -> MaterialTheme.colorScheme.tertiaryContainer
        TransferKind.CONTACT -> MaterialTheme.colorScheme.secondaryContainer
    }
    val icon = when (item.kind) {
        TransferKind.OUTGOING -> Icons.Default.Send
        TransferKind.RELAY -> Icons.Default.Share
        TransferKind.CONTACT -> Icons.Default.Person
    }
    val title = when {
        item.kind == TransferKind.CONTACT && item.peerName != null -> item.peerName
        else -> item.label
    }
    val preview = when (item.kind) {
        TransferKind.OUTGOING -> state.messages.firstOrNull { it.id == item.messageId }?.body
            ?: "Зашифрованное сообщение"
        TransferKind.RELAY -> "Транзитный конверт для другого получателя"
        TransferKind.CONTACT -> "Подписанная карточка · ${shortKey(item.peerAge ?: "")}"
    }
    val delivered = item.passCount > 0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (delivered) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (delivered) {
                    StatusPill(
                        "Передано ×${item.passCount}",
                        Icons.Default.CheckCircle,
                        MaterialTheme.colorScheme.primary,
                    )
                } else {
                    StatusPill("Ждёт", Icons.Default.Phone, MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Действия с элементом",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Удалить из очереди", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = kindLabel(item.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (delivered) {
                        buildString {
                            append("Передано ")
                            append(formatRelative(item.passedAt ?: 0L))
                            if (item.passCount > 1) append(" · ${item.passCount} раза")
                        }
                    } else {
                        formatRelative(item.addedAt)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Убрать из очереди?") },
            text = {
                Text(
                    when (item.kind) {
                        TransferKind.OUTGOING ->
                            "Сообщение останется в чате, но больше не будет уходить при касании. " +
                                "Вернуть его в очередь можно из меню сообщения."
                        TransferKind.RELAY ->
                            "Транзитный конверт будет удалён из очереди и не уйдёт следующему человеку."
                        TransferKind.CONTACT ->
                            "Карточка больше не будет передаваться. Сам контакт останется в списке чатов."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Убрать", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun StatusPill(text: String, icon: ImageVector, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.14f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

private fun kindLabel(kind: TransferKind): String = when (kind) {
    TransferKind.OUTGOING -> "Ваше сообщение"
    TransferKind.RELAY -> "Транзит"
    TransferKind.CONTACT -> "Контакт"
}

@Composable
private fun ContactPickerDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onPick: (Contact) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, contentDescription = null) },
        title = { Text("Поделиться контактом") },
        text = {
            if (state.contacts.isEmpty()) {
                Text("Контактов пока нет. Обменяйтесь ими по NFC, и они появятся здесь.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.contacts, key = { it.age }) { contact ->
                        val blocked = contact.card == null &&
                            contact.age != state.identity.ageRecipient
                        ContactPickRow(
                            contact = contact,
                            blocked = blocked,
                            onClick = { onPick(contact) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun ContactPickRow(
    contact: Contact,
    blocked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !blocked, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(name = contact.name, seed = contact.age, size = 40.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (blocked) "Нет исходной подписи" else shortKey(contact.age),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (blocked) Icons.Default.Warning else Icons.Default.Share,
            contentDescription = null,
            tint = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}
