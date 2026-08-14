package com.trustedconfigurator.browser.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.trustedconfigurator.browser.usb.TransferKind
import com.trustedconfigurator.browser.usb.TransferLog
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Lets the bridge ask the hosting activity to run a Storage Access Framework picker. */
interface FilePicker {
    suspend fun createDocument(suggestedName: String, mimeType: String): Uri?
    suspend fun openDocument(mimeTypes: Array<String>): Uri?
}

class FileBridgeException(message: String) : Exception(message)

/**
 * File saving and loading, backed by the Storage Access Framework.
 *
 * This is what makes "save the blackbox dump" and "export a preset" work. A
 * WebView exposes a File System Access API whose save half is inert —
 * showSaveFilePicker resolves never and throws nothing — and it drops `blob:`
 * downloads, so without this layer every save button silently does nothing.
 *
 * Writes are streamed: the blackbox log a page hands over can be tens of
 * megabytes, and buffering it in one message would be the app's peak allocation.
 */
class FileBridge(context: Context, private val picker: FilePicker) {

    private val resolver = context.applicationContext.contentResolver
    private val counter = AtomicLong(0)

    private val saveTargets = ConcurrentHashMap<String, Uri>()
    private val writers = ConcurrentHashMap<String, OutputStream>()
    private val bytesWritten = ConcurrentHashMap<String, Long>()
    private val readers = ConcurrentHashMap<String, ReadSession>()

    /** An open read, with its stream and position kept between chunk requests. */
    private class ReadSession(val uri: Uri) {
        var stream: InputStream? = null
        var position: Long = 0

        fun close() {
            runCatching { stream?.close() }
            stream = null
        }
    }

    /** Opens a save dialog. @return token and final name, or null if cancelled. */
    suspend fun beginSave(suggestedName: String, mimeType: String): SaveTarget? {
        // Logged before the dialog opens, not just on success: Betaflight's save
        // handlers have no catch, so a failure anywhere in here is invisible in
        // the page. The transfer log is then the only way to tell "the user
        // cancelled" from "the result never came back".
        TransferLog.record(TransferKind.EVENT, "-", suggestedName, "Save dialog requested ($mimeType)")
        val uri = picker.createDocument(suggestedName, mimeType)
        if (uri == null) {
            TransferLog.record(TransferKind.EVENT, "-", suggestedName, "Save dialog returned nothing (cancelled)")
            return null
        }
        val token = "w${counter.incrementAndGet()}"
        saveTargets[token] = uri
        val name = displayNameOf(uri) ?: suggestedName
        TransferLog.record(TransferKind.EVENT, "-", name, "Saving to $uri")
        return SaveTarget(token, name)
    }

    /**
     * Appends to the file behind [token], opening the stream on first use.
     *
     * Lazy so that a page which calls `createWritable()` a second time on the
     * same handle — which Betaflight does, once for a plain write and again for
     * a streaming one — gets a fresh truncating stream rather than an error.
     */
    fun write(token: String, bytes: ByteArray) {
        val stream = writers.getOrPut(token) {
            val uri = saveTargets[token] ?: throw FileBridgeException("Unknown file token")
            (resolver.openOutputStream(uri, "wt") ?: throw FileBridgeException("Could not open the file for writing"))
                .buffered()
        }
        stream.write(bytes)
        bytesWritten[token] = (bytesWritten[token] ?: 0L) + bytes.size
    }

    fun endSave(token: String) {
        var written = 0L
        writers.remove(token)?.let { stream ->
            runCatching { stream.flush() }
            runCatching { stream.close() }
            written = bytesWritten.remove(token) ?: 0L
        }
        TransferLog.record(
            TransferKind.EVENT,
            "-",
            saveTargets[token]?.let { displayNameOf(it) } ?: token,
            "Save complete",
            written.toInt(),
        )
        // The uri is retained so a later write re-opens the same document.
    }

    /** Opens a file picker and registers the result for reading. */
    suspend fun beginOpen(mimeTypes: Array<String>): OpenTarget? {
        val uri = picker.openDocument(if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes) ?: return null
        val token = "r${counter.incrementAndGet()}"
        readers[token] = ReadSession(uri)
        val name = displayNameOf(uri) ?: "file"
        TransferLog.record(TransferKind.EVENT, "-", name, "Opened $uri for reading")
        return OpenTarget(token, name, sizeOf(uri))
    }

    /**
     * Reads one chunk.
     *
     * The stream is held open between calls and its position tracked, because
     * pages read a file front to back in chunks. Re-opening and skipping from
     * zero each time is O(n²) on the file size — reading a 50 MB blackbox log in
     * 512 KB chunks would skip ~2.5 GB to deliver 50 MB. Only a backwards seek,
     * which no caller currently does, pays for a re-open.
     */
    @Synchronized
    fun read(token: String, offset: Long, length: Int): ByteArray {
        val session = readers[token] ?: throw FileBridgeException("Unknown or already closed file token")

        if (session.stream == null || offset < session.position) {
            session.close()
            session.stream = resolver.openInputStream(session.uri)
                ?: throw FileBridgeException("Could not open the file for reading")
            session.position = 0
        }
        val input = session.stream ?: throw FileBridgeException("Could not open the file for reading")
        if (offset > session.position) {
            session.position += skipFully(input, offset - session.position)
        }

        val buffer = ByteArray(length.coerceAtLeast(0))
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count <= 0) break
            read += count
        }
        session.position += read
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    @Synchronized
    fun endOpen(token: String) {
        readers.remove(token)?.close()
    }

    @Synchronized
    fun closeAll() {
        writers.keys.toList().forEach(::endSave)
        saveTargets.clear()
        readers.values.forEach { it.close() }
        readers.clear()
    }

    /** @return how many bytes were actually skipped, which may be short at EOF. */
    private fun skipFully(input: InputStream, count: Long): Long {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // skip() is allowed to return 0 before EOF; fall back to reading.
                if (input.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
        return count - remaining
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun sizeOf(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    data class SaveTarget(val token: String, val name: String)
    data class OpenTarget(val token: String, val name: String, val size: Long)
}
