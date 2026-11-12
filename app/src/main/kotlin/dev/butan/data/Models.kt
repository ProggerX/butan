package dev.butan.data

import dev.butan.core.Identity
import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val age: String,
    val signKey: String,
    val name: String,
    val addedAt: Long,
)

@Serializable
data class InboxMessage(
    val id: String,
    val fromAge: String,
    val fromName: String,
    val signKey: String,
    val body: String,
    val timestamp: Long,
    val receivedAt: Long,
    val verified: Boolean,
)

@Serializable
data class QueueItem(
    val id: String,
    val ciphertext: String,
    val addedAt: Long,
    val mine: Boolean,
    val label: String,
)

@Serializable
data class PersistedState(
    val version: Int = 1,
    val profileName: String = "Anonymous",
    val contacts: List<Contact> = emptyList(),
    val inbox: List<InboxMessage> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val seen: List<String> = emptyList(),
)

data class AppState(
    val identity: Identity,
    val profileName: String,
    val contacts: List<Contact>,
    val inbox: List<InboxMessage>,
    val queue: List<QueueItem>,
    val seen: List<String>,
)

sealed interface ReceiveResult {
    data class Delivered(val message: InboxMessage) : ReceiveResult
    data object Queued : ReceiveResult
    data object Duplicate : ReceiveResult
    data object Invalid : ReceiveResult
}

sealed interface ContactResult {
    data class Saved(val contact: Contact, val isNew: Boolean) : ContactResult
    data object Invalid : ContactResult
}
