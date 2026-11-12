package dev.butan.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.butan.ButanApp
import dev.butan.core.BundleCodec
import dev.butan.core.BundleEntry
import dev.butan.core.Butan
import dev.butan.core.NfcFrame
import dev.butan.data.AppState
import dev.butan.data.Contact
import dev.butan.data.ContactResult
import dev.butan.data.ReceiveResult
import dev.butan.nfc.NfcGateway
import dev.butan.nfc.NfcSlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

data class BeamState(
    val title: String = "Touch phones to sync",
    val detail: String = "Both phones exchange all queued envelopes and contact cards automatically while Butan is open.",
    val progress: Float? = null,
)

class ButanViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as ButanApp).store

    val state = store.state

    private val _beam = MutableStateFlow(BeamState())
    val beam = _beam.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var focused = false
    private var syncJob: Job? = null
    private var lastPeerAge: String? = null
    private val recentlyReceived = linkedSetOf<String>()

    fun send(contact: Contact, body: String, onQueued: () -> Unit) {
        viewModelScope.launch {
            runCatching { store.sendMessage(contact, body) }
                .onSuccess {
                    _events.emit("Encrypted and queued for ${contact.name}")
                    onQueued()
                }
                .onFailure { _events.emit("Could not create message: ${it.message}") }
        }
    }

    fun onFocus(nfc: NfcGateway) {
        if (focused && syncJob?.isActive == true) return
        focused = true
        startSyncLoop(nfc)
    }

    fun onBlur(nfc: NfcGateway) {
        focused = false
        syncJob?.cancel()
        syncJob = null
        nfc.stopReading()
        NfcSlot.disarm()
        recentlyReceived.clear()
        lastPeerAge = null
        _beam.value = BeamState()
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch { store.deleteMessage(id) }
    }

    fun updateName(name: String) {
        viewModelScope.launch { store.updateProfileName(name) }
    }

    /**
     * Random role selection breaks symmetry. If this phone becomes the reader it
     * performs a full read+write exchange; otherwise it emulates a card and lets
     * the other phone do the same.
     */
    private fun startSyncLoop(nfc: NfcGateway) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (focused) {
                if (state.value == null) {
                    delay(200)
                    continue
                }
                if (!nfc.enabled) {
                    _beam.value = BeamState(
                        title = "NFC is off",
                        detail = "Turn on NFC to sync with another phone.",
                    )
                    delay(1000)
                    continue
                }

                if (Random.nextBoolean()) {
                    exchangeOnce(nfc, randomPhaseMs())
                } else {
                    serveOnce(nfc, randomPhaseMs())
                }
            }
        }
    }

    /** Reader side: read the other card, then write our bundle in the same session. */
    private suspend fun exchangeOnce(nfc: NfcGateway, discoveryMs: Long) {
        val current = state.value ?: return
        val bundle = buildBundle(current)
        val deferred = CompletableDeferred<NfcGateway.ExchangeResult?>()

        nfc.startExchange(
            outgoingFrame = bundle.frame,
            discoveryTimeoutMs = discoveryMs,
            onResult = { deferred.complete(it) },
            onError = { _, _ -> deferred.complete(null) },
            onReadProgress = { read, total -> publishProgress("Receiving…", read, total) },
            onWriteProgress = { read, total -> publishProgress("Sending…", read, total) },
        )

        val result = withTimeoutOrNull(EXCHANGE_RESULT_TIMEOUT_MS) { deferred.await() }
        nfc.stopReading()

        if (result == null) {
            _beam.value = BeamState()
            return
        }

        val summary = processIncoming(result.kind, result.payload)
        if (result.writeOk) {
            for (id in bundle.sentIds) store.markSent(id)
        }

        if (!summary.isNullOrBlank()) _events.emit(summary)
        _beam.value = BeamState(
            title = "Synced",
            detail = summary ?: if (result.writeOk) {
                "Both phones are up to date."
            } else {
                "Received data, but sending failed. It will be retried."
            },
            progress = 1f,
        )
        delay(COOLDOWN_MS)
        _beam.value = BeamState()
    }

    /** Card side: serve our bundle and accept the other phone's bundle via UPDATE BINARY. */
    private suspend fun serveOnce(nfc: NfcGateway, timeoutMs: Long) {
        val current = state.value ?: return
        val bundle = buildBundle(current)
        val delivered = CompletableDeferred<Unit>()
        val incoming = CompletableDeferred<String?>()

        NfcSlot.arm(
            frame = bundle.frame,
            onDelivered = { delivered.complete(Unit) },
            onProgress = { read, total -> publishProgress("Sending…", read, total) },
            onWriteProgress = { read, total -> publishProgress("Receiving…", read, total) },
            onIncoming = { raw ->
                viewModelScope.launch {
                    incoming.complete(processRawFrame(raw))
                }
            },
        )

        var deliveredOk = false
        var summary: String? = null

        withTimeoutOrNull(timeoutMs + SERVE_EXTRA_MS) {
            delivered.await()
            deliveredOk = true
            summary = incoming.await()
        }
        NfcSlot.disarm()

        val summaryValue = summary
        if (deliveredOk || summaryValue != null) {
            if (!summaryValue.isNullOrBlank()) _events.emit(summaryValue)
            _beam.value = BeamState(
                title = "Synced",
                detail = summaryValue ?: if (deliveredOk) {
                    "Sent to the other phone."
                } else {
                    "The other phone is up to date."
                },
                progress = 1f,
            )
            delay(COOLDOWN_MS)
        }
        _beam.value = BeamState()
    }

    private fun buildBundle(current: AppState): BuiltBundle {
        val entries = mutableListOf<BundleEntry>()
        entries += BundleEntry(
            NfcFrame.KIND_CONTACT,
            Butan.createContactCard(current.identity, current.profileName),
        )

        val sentIds = mutableListOf<String>()
        val queued = current.queue.sortedBy { it.addedAt }
        var index = 0

        while (index < queued.size) {
            val item = queued[index]
            val envelope = Butan.envelopeFromText(item.ciphertext)
            if (envelope == null) {
                index++
                continue
            }

            if (Butan.envelopeFingerprint(envelope) in recentlyReceived) {
                index++
                continue
            }

            val candidate = BundleEntry(NfcFrame.KIND_ENVELOPE, envelope)
            if (BundleCodec.encodedSize(entries + candidate) > BundleCodec.MAX_PAYLOAD_BYTES) {
                break
            }

            entries += candidate
            sentIds += item.id
            index++
        }

        val hasMore = index < queued.size
        val payload = BundleCodec.encode(entries, hasMore) ?: ByteArray(0)
        val frame = NfcFrame.encode(NfcFrame.KIND_BUNDLE, payload)

        return BuiltBundle(frame, sentIds, entries.size)
    }

    private suspend fun processRawFrame(raw: ByteArray): String? {
        val header = NfcFrame.parseHeader(raw) ?: return null
        val payload = raw.copyOfRange(
            NfcFrame.HEADER_SIZE,
            NfcFrame.HEADER_SIZE + header.length,
        )
        return processIncoming(header.kind, payload)
    }

    private suspend fun processIncoming(kind: Int, payload: ByteArray): String? {
        if (kind != NfcFrame.KIND_BUNDLE) {
            return when (kind) {
                NfcFrame.KIND_CONTACT -> processContact(payload)
                NfcFrame.KIND_ENVELOPE -> processEnvelope(payload)
                else -> null
            }
        }

        val bundle = BundleCodec.decode(payload) ?: return null
        var received = 0
        var carried = 0
        var newContacts = 0

        for (entry in bundle.entries) {
            when (entry.kind) {
                NfcFrame.KIND_CONTACT -> {
                    val card = Butan.openContactCard(entry.payload)
                    val peerAge = card?.content?.age
                    if (peerAge != null && peerAge != lastPeerAge) {
                        lastPeerAge = peerAge
                        recentlyReceived.clear()
                    }
                    val result = store.receiveContact(entry.payload)
                    if (result is ContactResult.Saved && result.isNew) newContacts++
                }

                NfcFrame.KIND_ENVELOPE -> {
                    when (store.receiveEnvelope(entry.payload)) {
                        is ReceiveResult.Delivered -> {
                            received++
                            rememberReceived(entry.payload)
                        }

                        ReceiveResult.Queued -> {
                            carried++
                            rememberReceived(entry.payload)
                        }

                        else -> Unit
                    }
                }
            }
        }

        if (received == 0 && carried == 0 && newContacts == 0) return null

        return buildString {
            if (received > 0) append("$received received")
            if (carried > 0) {
                if (isNotEmpty()) append(" • ")
                append("$carried carried")
            }
            if (newContacts > 0) {
                if (isNotEmpty()) append(" • ")
                append("$newContacts new contact(s)")
            }
        }
    }

    private suspend fun processContact(payload: ByteArray): String? {
        val result = store.receiveContact(payload)
        return if (result is ContactResult.Saved && result.isNew) {
            "New contact: ${result.contact.name}"
        } else {
            null
        }
    }

    private suspend fun processEnvelope(payload: ByteArray): String? =
        when (val result = store.receiveEnvelope(payload)) {
            is ReceiveResult.Delivered -> {
                rememberReceived(payload)
                "New message from ${result.message.fromName}"
            }

            ReceiveResult.Queued -> {
                rememberReceived(payload)
                "Envelope not for you — queued for the next phone"
            }

            else -> null
        }

    private fun rememberReceived(envelope: ByteArray) {
        recentlyReceived += Butan.envelopeFingerprint(envelope)
        while (recentlyReceived.size > MAX_REMEMBERED) {
            val first = recentlyReceived.firstOrNull() ?: break
            recentlyReceived.remove(first)
        }
    }

    private fun publishProgress(direction: String, read: Int, total: Int) {
        if (total <= 0) return
        val fraction = (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        _beam.value = BeamState(
            title = direction,
            detail = "Exchanging contact cards and queued envelopes.",
            progress = fraction,
        )
    }

    private fun randomPhaseMs(): Long = PHASE_MIN_MS + Random.nextLong(PHASE_JITTER_MS)

    private data class BuiltBundle(
        val frame: ByteArray,
        val sentIds: List<String>,
        val entryCount: Int,
    )

    private companion object {
        const val PHASE_MIN_MS = 2500L
        const val PHASE_JITTER_MS = 1500L
        const val SERVE_EXTRA_MS = 2500L
        const val EXCHANGE_RESULT_TIMEOUT_MS = 15000L
        const val COOLDOWN_MS = 900L
        const val MAX_REMEMBERED = 256
    }
}
