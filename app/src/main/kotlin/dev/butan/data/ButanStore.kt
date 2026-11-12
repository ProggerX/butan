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

    suspend fun sendMessage(contact: Contact, body: String): QueueItem = mutex.withLock {
        val current = checkLoaded()
        val now = System.currentTimeMillis()
        val envelope = Butan.createEnvelope(
            identity = current.identity,
            recipient = contact.age,
            fromName = current.profileName,
            body = body,
            nowSeconds = now / 1000,
        )
        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            ciphertext = Butan.envelopeToText(envelope),
            addedAt = now,
            mine = true,
            label = "For ${contact.name}",
        )
        val next = current.copy(
            queue = current.queue + item,
            seen = addSeen(current.seen, Butan.envelopeFingerprint(envelope)),
        )
        applyLocked(next)
        item
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

                val message = InboxMessage(
                    id = UUID.randomUUID().toString(),
                    fromAge = content.from,
                    fromName = knownContact?.name ?: content.fromName,
                    signKey = content.signKey,
                    body = content.body,
                    timestamp = content.ts,
                    receivedAt = System.currentTimeMillis(),
                    verified = knownContact != null,
                )
                val next = current.copy(
                    inbox = (current.inbox + message).sortedByDescending { it.timestamp },
                    seen = addSeen(current.seen, hash),
                )
                applyLocked(next)
                ReceiveResult.Delivered(message)
            }

            OpenResult.NotForMe -> {
                val item = QueueItem(
                    id = UUID.randomUUID().toString(),
                    ciphertext = Butan.envelopeToText(envelope),
                    addedAt = System.currentTimeMillis(),
                    mine = false,
                    label = "Relay ${hash.take(8)}",
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
        val existing = current.contacts.firstOrNull { it.age == card.content.age }
        if (existing != null && existing.signKey != card.content.signKey) {
            return@withLock ContactResult.Invalid
        }

        val contact = Contact(
            age = card.content.age,
            signKey = card.content.signKey,
            name = card.content.name,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
        )
        val contacts = if (existing == null) {
            current.contacts + contact
        } else {
            current.contacts.map { if (it.age == contact.age) contact else it }
        }
        applyLocked(current.copy(contacts = contacts.sortedBy { it.name.lowercase() }))
        ContactResult.Saved(contact, isNew = existing == null)
    }

    suspend fun markSent(id: String) = mutex.withLock {
        val current = checkLoaded()
        val item = current.queue.firstOrNull { it.id == id } ?: return@withLock
        val hash = Butan.envelopeFromText(item.ciphertext)?.let(Butan::envelopeFingerprint)
        val next = current.copy(
            queue = current.queue.filterNot { it.id == id },
            seen = if (hash != null) addSeen(current.seen, hash) else current.seen,
        )
        applyLocked(next)
    }

    suspend fun deleteMessage(id: String) = mutex.withLock {
        val current = checkLoaded()
        applyLocked(current.copy(inbox = current.inbox.filterNot { it.id == id }))
    }

    suspend fun updateProfileName(name: String) = mutex.withLock {
        val current = checkLoaded()
        val clean = name.trim().take(Butan.MAX_NAME_CHARS).ifBlank { "Anonymous" }
        applyLocked(current.copy(profileName = clean))
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

        _state.value = AppState(
            identity = identity,
            profileName = persisted.profileName,
            contacts = persisted.contacts,
            inbox = persisted.inbox,
            queue = persisted.queue,
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
            inbox = state.inbox,
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

    @Serializable
    private data class IdentityRecord(val seed: String)

    private companion object {
        const val MAX_SEEN = 512
    }
}
