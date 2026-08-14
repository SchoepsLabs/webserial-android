package com.trustedconfigurator.browser.usb

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class TransferKind { CONTROL_IN, CONTROL_OUT, BULK_IN, BULK_OUT, EVENT, PERMISSION, ERROR }

data class TransferRecord(
    val timestampMillis: Long,
    val kind: TransferKind,
    val origin: String,
    val device: String,
    val detail: String,
    val byteCount: Int,
) {
    fun formattedTime(): String = TIME_FORMAT.format(Date(timestampMillis))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * A bounded ring of recent USB activity for the diagnostic screen.
 *
 * Bounded rather than unbounded because a firmware flash produces on the order
 * of a thousand transfers and an unbounded log would be the app's largest
 * allocation.
 */
object TransferLog {

    private const val CAPACITY = 2000

    private val entries = ArrayDeque<TransferRecord>(CAPACITY)
    private val listeners = mutableSetOf<(TransferRecord) -> Unit>()

    @Synchronized
    fun record(
        kind: TransferKind,
        origin: String,
        device: String,
        detail: String,
        byteCount: Int = 0,
    ) {
        val record = TransferRecord(System.currentTimeMillis(), kind, origin, device, detail, byteCount)
        if (entries.size >= CAPACITY) {
            entries.removeFirst()
        }
        entries.addLast(record)
        listeners.toList().forEach { it(record) }
    }

    @Synchronized
    fun snapshot(): List<TransferRecord> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun addListener(listener: (TransferRecord) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (TransferRecord) -> Unit) {
        listeners.remove(listener)
    }
}
