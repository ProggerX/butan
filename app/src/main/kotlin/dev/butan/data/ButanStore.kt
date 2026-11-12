package dev.butan.data

import android.content.Context
import dev.butan.core.Butan
import dev.butan.core.ContactCard
import dev.butan.core.Identity
import dev.butan.core.OpenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.UUID

class ButanStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val directory = context.applicationContext.filesDir
    private val stateFile = File(directory, "butan-state.json")
    private val identityFile = File(directory, "butan-identity.json")

    private val mutex = Mutex()
    private val _state = MutableStateFlow<AppState?>(null)
    val state: StateFlow<AppState?> = _state.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { loadLocked() }
        }
    }

    /**
     * Encrypts a message, appends it to the conversation history and puts a
     * copy into the transfer queue. Both copies are first-class state: handing
     * the envelope to another phone does not remove it from either place.
     */
    suspend fun sendMessage(
        recipientAge: String,
        recipientName: String,
        body: String,
    ): ChatMessage = mutex.withLock {
        val current = checkLoaded()
        val now = System.currentTimeMillis()
        val envelope = Butan.createEnvelope(
            identity = current.identity,
            recipient = recipientAge,
            fromName = current.profileName,
            body = body,
            nowSeconds = now / 1000,
        )
        val messageId = UUID.randomUUID().toString()
        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.OUTGOING,
            ciphertext = Butan.envelopeToText(envelope),
            addedAt = now,
            mine = true,
            label = "Для $recipientName",
            peerAge = recipientAge,
            peerName = recipientName,
            messageId = messageId,
        )
        val message = ChatMessage(
            id = messageId,
            peerAge = recipientAge,
            peerName = recipientName,
            outgoing = true,
            body = body,
            timestamp = now / 1000,
            receivedAt = now,
            verified = true,
            unread = false,
            queueId = item.id,
        )
        val next = current.copy(
            messages = current.messages + message,
            queue = current.queue + item,
            seen = addSeen(current.seen, Butan.envelopeFingerprint(envelope)),
        )
        applyLocked(next)
        message
    }

    suspend fun receiveEnvelope(envelope: ByteArray): ReceiveResult = mutex.withLock {
        val current = checkLoaded()
        val hash = Butan.envelopeFingerprint(envelope)
        if (hash in current.seen) return@withLock ReceiveResult.Duplicate

        when (val opened = Butan.openEnvelope(current.identity, envelope)) {
            is OpenResult.ForMe -> {
                val content = opened.message.content
                val knownContact = current.contacts.firstOrNull { it.age == content.from }
                if (knownContact != null && knownContact.signKey != content.signKey) {
                    return@withLock ReceiveResult.Invalid
                }

                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    peerAge = content.from,
                    peerName = knownContact?.name ?: content.fromName,
                    outgoing = false,
                    body = content.body,
                    timestamp = content.ts,
                    receivedAt = System.currentTimeMillis(),
                    verified = knownContact != null,
                    unread = true,
                )
                val next = current.copy(
                    messages = current.messages + message,
                    seen = addSeen(current.seen, hash),
                )
                applyLocked(next)
                ReceiveResult.Delivered(message)
            }

            OpenResult.NotForMe -> {
                val item = QueueItem(
                    id = UUID.randomUUID().toString(),
                    kind = TransferKind.RELAY,
                    ciphertext = Butan.envelopeToText(envelope),
                    addedAt = System.currentTimeMillis(),
                    mine = false,
                    label = "Транзит · ${hash.take(6)}",
                )
                val next = current.copy(
                    queue = current.queue + item,
                    seen = addSeen(current.seen, hash),
                )
                applyLocked(next)
                ReceiveResult.Queued
            }

            OpenResult.Invalid -> ReceiveResult.Invalid
        }
    }

    suspend fun receiveContact(bytes: ByteArray): ContactResult = mutex.withLock {
        val current = checkLoaded()
        val card: ContactCard = Butan.openContactCard(bytes) ?: return@withLock ContactResult.Invalid
        // A card can travel back to its owner (for example, when a friend relays it).
        // Never turn your own card into a chat with yourself.
        if (card.content.age == current.identity.ageRecipient) {
            return@withLock ContactResult.Invalid
        }
        val existing = current.contacts.firstOrNull { it.age == card.content.age }
        if (existing != null && existing.signKey != card.content.signKey) {
            return@withLock ContactResult.Invalid
        }

        val contact = Contact(
            age = card.content.age,
            signKey = card.content.signKey,
            name = card.content.name,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            card = base64(bytes),
        )
        val contacts = if (existing == null) {
            current.contacts + contact
        } else {
            current.contacts.map { if (it.age == contact.age) contact else it }
        }
        applyLocked(current.copy(contacts = contacts.sortedBy { it.name.lowercase() }))
        ContactResult.Saved(contact, isNew = existing == null)
    }

    /**
     * Marks items as handed to another phone without deleting them. They stay
     * in [AppState.queue] and in the chat history, and can be re-armed with
     * [retryTransfer].
     */
    suspend fun markTransferred(ids: Collection<String>) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        val current = checkLoaded()
        val now = System.currentTimeMillis()
        val nextQueue = current.queue.map { item ->
            if (item.id in ids) {
                item.copy(passedAt = now, passCount = item.passCount + 1)
            } else {
                item
            }
        }
        applyLocked(current.copy(queue = nextQueue))
    }

    suspend fun retryTransfer(id: String) = mutex.withLock {
        val current = checkLoaded()
        applyLocked(
            current.copy(
                queue = current.queue.map {
                    if (it.id == id) it.copy(passedAt = null) else it
                },
            ),
        )
    }

    suspend fun deleteMessage(id: String) = mutex.withLock {
        val current = checkLoaded()
        val message = current.messages.firstOrNull { it.id == id }
        val queueIds = buildSet {
            if (message?.queueId != null) add(message.queueId)
            current.queue.filter { it.messageId == id }.forEach { add(it.id) }
        }
        applyLocked(
            current.copy(
                messages = current.messages.filterNot { it.id == id },
                queue = current.queue.filterNot { it.id in queueIds },
            ),
        )
    }

    suspend fun markConversationRead(peerAge: String) = mutex.withLock {
        val current = checkLoaded()
        if (current.messages.none { it.peerAge == peerAge && it.unread }) return@withLock
        applyLocked(
            current.copy(
                messages = current.messages.map {
                    if (it.peerAge == peerAge) it.copy(unread = false) else it
                },
            ),
        )
    }

    /**
     * Queues a signed contact card for the next hand-off. Own cards are
     * generated from the current identity; cards of other people are forwarded
     * byte-for-byte, because only their owner can sign them.
     */
    suspend fun queueContactShare(contact: Contact): QueueItem? = mutex.withLock {
        val current = checkLoaded()
        val cardBytes = when {
            contact.age == current.identity.ageRecipient ->
                Butan.createContactCard(current.identity, current.profileName)

            contact.card != null -> {
                val bytes = base64Decode(contact.card)
                if (bytes == null || Butan.openContactCard(bytes) == null) null else bytes
            }

            else -> null
        } ?: return@withLock null

        val existing = current.queue.firstOrNull {
            it.kind == TransferKind.CONTACT &&
                it.peerAge == contact.age &&
                it.passedAt == null
        }
        if (existing != null) {
            // Keep a pending self-card up to date with the current name.
            if (contact.age == current.identity.ageRecipient) {
                val refreshed = existing.copy(
                    card = base64(cardBytes),
                    label = "Мой контакт",
                    addedAt = existing.addedAt,
                )
                applyLocked(
                    current.copy(
                        queue = current.queue.map { if (it.id == existing.id) refreshed else it },
                    ),
                )
                return@withLock refreshed
            }
            return@withLock existing
        }

        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.CONTACT,
            card = base64(cardBytes),
            addedAt = System.currentTimeMillis(),
            mine = true,
            label = if (contact.age == current.identity.ageRecipient) {
                "Мой контакт"
            } else {
                "Контакт: ${contact.name}"
            },
            peerAge = contact.age,
            peerName = contact.name,
        )
        applyLocked(current.copy(queue = current.queue + item))
        item
    }

    suspend fun queueOwnContact(): QueueItem? = mutex.withLock {
        val current = checkLoaded()
        val self = Contact(
            age = current.identity.ageRecipient,
            signKey = "",
            name = current.profileName,
            addedAt = System.currentTimeMillis(),
        )
        // Reuse the same code path without taking the mutex twice.
        val cardBytes = Butan.createContactCard(current.identity, current.profileName)
        val existing = current.queue.firstOrNull {
            it.kind == TransferKind.CONTACT &&
                it.peerAge == self.age &&
                it.passedAt == null
        }
        if (existing != null) {
            val refreshed = existing.copy(card = base64(cardBytes), label = "Мой контакт")
            applyLocked(
                current.copy(
                    queue = current.queue.map { if (it.id == existing.id) refreshed else it },
                ),
            )
            return@withLock refreshed
        }
        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.CONTACT,
            card = base64(cardBytes),
            addedAt = System.currentTimeMillis(),
            mine = true,
            label = "Мой контакт",
            peerAge = self.age,
            peerName = self.name,
        )
        applyLocked(current.copy(queue = current.queue + item))
        item
    }

    suspend fun updateProfileName(name: String) = mutex.withLock {
        val current = checkLoaded()
        val clean = name.trim().take(Butan.MAX_NAME_CHARS).ifBlank { "Anonymous" }
        val selfAge = current.identity.ageRecipient
        val cardBytes = Butan.createContactCard(current.identity, clean)
        val queue = current.queue.map { item ->
            if (item.kind == TransferKind.CONTACT &&
                item.peerAge == selfAge &&
                item.passedAt == null
            ) {
                item.copy(card = base64(cardBytes), label = "Мой контакт")
            } else {
                item
            }
        }
        applyLocked(current.copy(profileName = clean, queue = queue))
    }

    private fun loadLocked() {
        val identity = loadIdentity() ?: Identity.generate().also(::saveIdentity)
        val persisted = if (stateFile.exists()) {
            runCatching {
                json.decodeFromString(PersistedState.serializer(), stateFile.readText())
            }.getOrNull()
        } else {
            null
        } ?: PersistedState()

        val messages = if (persisted.messages.isNotEmpty() || persisted.version >= 3) {
            persisted.messages
        } else {
            persisted.inbox.map { legacy ->
                ChatMessage(
                    id = legacy.id,
                    peerAge = legacy.fromAge,
                    peerName = legacy.fromName,
                    outgoing = false,
                    body = legacy.body,
                    timestamp = legacy.timestamp,
                    receivedAt = legacy.receivedAt,
                    verified = legacy.verified,
                    unread = false,
                )
            }
        }

        val queue = persisted.queue.map { item ->
            if (persisted.version < 3 && item.kind == TransferKind.RELAY) {
                item.copy(kind = if (item.mine) TransferKind.OUTGOING else TransferKind.RELAY)
            } else {
                item
            }
        }

        _state.value = AppState(
            identity = identity,
            profileName = persisted.profileName,
            contacts = persisted.contacts,
            messages = messages,
            queue = queue,
            seen = persisted.seen,
        )
    }

    private fun loadIdentity(): Identity? {
        if (!identityFile.exists()) return null
        return runCatching {
            val record = json.decodeFromString(IdentityRecord.serializer(), identityFile.readText())
            val seed = Base64.getDecoder().decode(record.seed)
            Identity.fromSeed(seed)
        }.getOrNull()
    }

    private fun saveIdentity(identity: Identity) {
        val record = IdentityRecord(
            seed = Base64.getEncoder().withoutPadding().encodeToString(identity.seed),
        )
        writeAtomic(
            identityFile,
            json.encodeToString(IdentityRecord.serializer(), record).toByteArray(Charsets.UTF_8),
        )
    }

    private fun applyLocked(next: AppState) {
        _state.value = next
        persist(next)
    }

    private fun persist(state: AppState) {
        val persisted = PersistedState(
            profileName = state.profileName,
            contacts = state.contacts,
            messages = state.messages,
            queue = state.queue,
            seen = state.seen,
        )
        writeAtomic(
            stateFile,
            json.encodeToString(PersistedState.serializer(), persisted).toByteArray(Charsets.UTF_8),
        )
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.writeBytes(bytes)
            temporary.delete()
        }
    }

    private fun checkLoaded(): AppState = _state.value ?: error("store is still loading")

    private fun addSeen(seen: List<String>, hash: String): List<String> {
        if (hash in seen) return seen
        return (seen + hash).takeLast(MAX_SEEN)
    }

    private fun base64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun base64Decode(text: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    @Serializable
    private data class IdentityRecord(val seed: String)

    private companion object {
        const val MAX_SEEN = 512
    }
}
