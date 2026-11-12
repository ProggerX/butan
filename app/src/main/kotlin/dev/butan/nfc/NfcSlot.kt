package dev.butan.nfc

/** The single ISO-DEP frame currently being offered by the HCE sender. */
object NfcSlot {
    class Armed(
        val frame: ByteArray,
        val onDelivered: (() -> Unit)?,
        val onProgress: ((read: Int, total: Int) -> Unit)? = null,
        val onWriteProgress: ((read: Int, total: Int) -> Unit)? = null,
        val onIncoming: ((frame: ByteArray) -> Unit)? = null,
    )

    @Volatile
    private var armed: Armed? = null

    fun arm(
        frame: ByteArray,
        onDelivered: (() -> Unit)? = null,
        onProgress: ((read: Int, total: Int) -> Unit)? = null,
        onWriteProgress: ((read: Int, total: Int) -> Unit)? = null,
        onIncoming: ((frame: ByteArray) -> Unit)? = null,
    ) {
        armed = Armed(frame, onDelivered, onProgress, onWriteProgress, onIncoming)
    }

    fun disarm() {
        armed = null
    }

    fun current(): Armed? = armed
}
