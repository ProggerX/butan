package dev.butan.data

import dev.butan.core.Identity
import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val age: String,
    val signKey: String,
    val name: String,
    val addedAt: Long,
    /** Base64 of the signed contact card exactly as it was received. */
    val card: String? = null,
)

/**
 * One message inside a conversation. Outgoing messages are kept here as well
 * as in the transfer queue, so a chat always shows its full history.
 */
@Serializable
data class ChatMessage(
    val id: String,
    val peerAge: String,
    val peerName: String,
    val outgoing: Boolean,
    val body: String,
    /** Seconds since epoch, matching the `ts` field of the envelope. */
    val timestamp: Long,
    /** Milliseconds since epoch, used for stable ordering and relative labels. */
    val receivedAt: Long,
    val verified: Boolean,
    val unread: Boolean = false,
    val queueId: String? = null,
)

@Serializable
enum class TransferKind {
    /** Our own encrypted message waiting for a hand-off. */
    OUTGOING,

    /** An envelope addressed to somebody else that this phone carries. */
    RELAY,

    /** A signed contact card that we want to give to the next phone. */
    CONTACT,
}

/**
 * An item shown in the "Передача" tab. Items are never removed after a
 * successful hand-off; [passedAt] and [passCount] record the history instead.
 */
@Serializable
data class QueueItem(
    val id: String,
    val kind: TransferKind = TransferKind.RELAY,
    val ciphertext: String = "",
    /** Base64 of the signed contact card, only for [TransferKind.CONTACT]. */
    val card: String? = null,
    val addedAt: Long,
    val mine: Boolean = false,
    val label: String,
    val peerAge: String? = null,
    val peerName: String? = null,
    val messageId: String? = null,
    val passedAt: Long? = null,
    val passCount: Int = 0,
)

/** Legacy inbox row, kept so that state from 0.4 can be migrated. */
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
data class PersistedState(
    val version: Int = 3,
    val profileName: String = "Anonymous",
    val contacts: List<Contact> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val inbox: List<InboxMessage> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val seen: List<String> = emptyList(),
)

data class AppState(
    val identity: Identity,
    val profileName: String,
    val contacts: List<Contact>,
    val messages: List<ChatMessage>,
    val queue: List<QueueItem>,
    val seen: List<String>,
)

sealed interface ReceiveResult {
    data class Delivered(val message: ChatMessage) : ReceiveResult
    data object Queued : ReceiveResult
    data object Duplicate : ReceiveResult
    data object Invalid : ReceiveResult
}

sealed interface ContactResult {
    data class Saved(val contact: Contact, val isNew: Boolean) : ContactResult
    data object Invalid : ContactResult
}
