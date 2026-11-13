@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.butan.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butan.data.AppState
import dev.butan.data.ChatMessage
import dev.butan.data.Contact

/**
 * One row of the chat list. Contacts without messages are included too: the
 * first tab is meant to show every person you can talk to.
 */
private data class Conversation(
    val peerAge: String,
    val peerName: String,
    val contact: Contact?,
    val last: ChatMessage?,
    val unread: Int,
)

@Composable
fun ChatsScreen(
    state: AppState,
    contentPadding: PaddingValues,
    onOpenChat: (String) -> Unit,
    onEditName: () -> Unit,
) {
    val conversations = remember(state.contacts, state.messages) { buildConversations(state) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(conversations, query) {
        val needle = query.trim()
        if (needle.isBlank()) {
            conversations
        } else {
            conversations.filter {
                it.peerName.contains(needle, ignoreCase = true) ||
                    it.last?.body?.contains(needle, ignoreCase = true) == true
            }
        }
    }
    val unreadTotal = conversations.sumOf { it.unread }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ProfileHero(
                state = state,
                chatCount = conversations.size,
                unread = unreadTotal,
                onEditName = onEditName,
            )
        }

        if (conversations.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Поиск по чатам") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    },
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        item {
            SectionHeader(
                title = if (query.isBlank()) "Все чаты" else "Найдено",
                detail = when {
                    filtered.isEmpty() -> null
                    query.isBlank() -> "${filtered.size} · ${unreadTotal} новых"
                    else -> "${filtered.size}"
                },
            )
        }

        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.MailOutline,
                    title = if (query.isBlank()) "Пока никого нет" else "Ничего не найдено",
                    body = if (query.isBlank()) {
                        "Коснитесь двумя телефонами с открытым Butan — контакты обменяются сами. " +
                            "После этого здесь появится чат с каждым человеком."
                    } else {
                        "Попробуйте другое имя или слово из сообщения."
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                )
            }
        } else {
            items(filtered, key = { it.peerAge }) { conversation ->
                ConversationCard(
                    conversation = conversation,
                    onClick = { onOpenChat(conversation.peerAge) },
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ProfileHero(
    state: AppState,
    chatCount: Int,
    unread: Int,
    onEditName: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientAvatar(
                    name = state.profileName,
                    seed = state.identity.ageRecipient,
                    size = 62.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Ваш профиль",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = state.profileName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEditName) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Изменить имя",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Text(
                text = shortKey(state.identity.ageRecipient),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat(value = "$chatCount", label = "чатов")
                HeroStat(value = "$unread", label = "новых")
                HeroStat(
                    value = "${state.queue.size}",
                    label = "к передаче",
                )
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (conversation.unread > 0) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                GradientAvatar(
                    name = conversation.peerName,
                    seed = conversation.peerAge,
                    size = 54.dp,
                )
                if (conversation.unread > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.contact != null) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Контакт подтверждён",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    } else if (conversation.last != null) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Незнакомый ключ",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Text(
                    text = preview(conversation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val last = conversation.last
                if (last != null) {
                    Text(
                        text = formatChatTime(last.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (conversation.unread > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = conversation.unread.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                } else if (conversation.contact == null) {
                    Text(
                        text = "новый",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

private fun preview(conversation: Conversation): String {
    val last = conversation.last ?: return "Нет сообщений — нажмите, чтобы начать"
    val body = last.body.replace('\n', ' ').ifBlank { "(пустое сообщение)" }
    return if (last.outgoing) "Вы: $body" else body
}

private fun buildConversations(state: AppState): List<Conversation> {
    val ages = LinkedHashSet<String>()
    state.contacts.forEach { ages += it.age }
    state.messages.forEach { ages += it.peerAge }

    return ages.map { age ->
        val contact = state.contacts.firstOrNull { it.age == age }
        val messages = state.messages.filter { it.peerAge == age }
        val last = messages.maxByOrNull { it.receivedAt }
        Conversation(
            peerAge = age,
            peerName = contact?.name ?: last?.peerName ?: "Неизвестный",
            contact = contact,
            last = last,
            unread = messages.count { it.unread && !it.outgoing },
        )
    }.sortedWith(
        compareByDescending<Conversation> { it.last?.receivedAt ?: Long.MIN_VALUE }
            .thenBy { it.peerName.lowercase() },
    )
}
