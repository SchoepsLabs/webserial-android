package com.trustedconfigurator.browser.drivers

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** Modem status lines reported back through Web Serial's `getSignals()`. */
data class ModemSignals(
    val dataCarrierDetect: Boolean = false,
    val clearToSend: Boolean = false,
    val ringIndicator: Boolean = false,
    val dataSetReady: Boolean = false,
)

class SerialDriverException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A USB-to-serial bridge chip.
 *
 * The flight controllers Betaflight targets (STM32, AT32, GD32, APM32, X32,
 * RP2040) all expose a CDC-ACM virtual COM port; the CP210x/CH34x/FTDI drivers
 * exist for the USB-TTL adapters used to reach ESCs and older boards.
 */
interface SerialDriver {

    val name: String

    /** Whether DTR/RTS can be driven on this chip. Reported to the diagnostic screen. */
    val supportsControlSignals: Boolean

    fun open()

    fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: String)

    fun setControlSignals(dataTerminalReady: Boolean?, requestToSend: Boolean?, breakSignal: Boolean?)

    fun readSignals(): ModemSignals

    /** @return bytes read into [buffer], or 0 on timeout. Never negative. */
    fun read(buffer: ByteArray, timeoutMillis: Int): Int

    fun write(data: ByteArray, timeoutMillis: Int): Int

    fun close()

    /** Interfaces this driver claimed, for the diagnostic screen. */
    fun claimedInterfaces(): List<Int>
}

/**
 * Shared bulk-endpoint plumbing. Subclasses supply the control-request dialect
 * and pick which interfaces to claim.
 */
abstract class BaseSerialDriver(
    protected val device: UsbDevice,
    protected val connection: UsbDeviceConnection,
) : SerialDriver {

    protected var readEndpoint: UsbEndpoint? = null
    protected var writeEndpoint: UsbEndpoint? = null
    protected val claimed = ArrayList<UsbInterface>()

    override val supportsControlSignals: Boolean = true

    override fun claimedInterfaces(): List<Int> = claimed.map { it.id }

    protected fun claim(iface: UsbInterface) {
        if (!connection.claimInterface(iface, true)) {
            throw SerialDriverException("Could not claim interface ${iface.id} for $name")
        }
        claimed.add(iface)
    }

    protected fun bindBulkEndpoints(iface: UsbInterface) {
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN && readEndpoint == null) {
                readEndpoint = endpoint
            } else if (endpoint.direction == UsbConstants.USB_DIR_OUT && writeEndpoint == null) {
                writeEndpoint = endpoint
            }
        }
    }

    protected fun requireEndpoints() {
        if (readEndpoint == null || writeEndpoint == null) {
            throw SerialDriverException("$name exposes no usable bulk endpoint pair")
        }
    }

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
        val endpoint = readEndpoint ?: throw SerialDriverException("Port is not open")
        val count = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMillis)
        // A negative result is a timeout as often as it is a failure, and the
        // reader loop must not treat an idle link as a disconnect.
        return if (count > 0) count else 0
    }

    override fun write(data: ByteArray, timeoutMillis: Int): Int {
        val endpoint = writeEndpoint ?: throw SerialDriverException("Port is not open")
        var offset = 0
        while (offset < data.size) {
            // Chunked at 16 KB, not at maxPacketSize: bulkTransfer splits into
            // packets itself, so chunking at 64 bytes turned one MSP frame into
            // dozens of kernel round trips for no reason. 16 KB is the bound
            // older Android implementations were safe with.
            val length = minOf(MAX_WRITE_CHUNK_BYTES, data.size - offset)
            val chunk = if (offset == 0 && length == data.size) {
                data
            } else {
                data.copyOfRange(offset, offset + length)
            }
            val written = connection.bulkTransfer(endpoint, chunk, length, timeoutMillis)
            if (written < 0) {
                throw SerialDriverException("Bulk write failed after $offset of ${data.size} bytes")
            }
            offset += written
            if (written == 0) break
        }
        return offset
    }

    override fun close() {
        claimed.forEach {
            runCatching { connection.releaseInterface(it) }
        }
        claimed.clear()
        readEndpoint = null
        writeEndpoint = null
    }

    protected fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray? = null): Int =
        connection.controlTransfer(requestType, request, value, index, data, data?.size ?: 0, CONTROL_TIMEOUT_MS)

    protected fun controlIn(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int =
        connection.controlTransfer(requestType, request, value, index, data, data.size, CONTROL_TIMEOUT_MS)

    companion object {
        const val CONTROL_TIMEOUT_MS = 2000

        /** Upper bound per bulkTransfer; older Android was unreliable beyond this. */
        const val MAX_WRITE_CHUNK_BYTES = 16 * 1024
    }
}

object SerialDriverFactory {

    private const val VID_FTDI = 0x0403
    private const val VID_SILABS = 0x10C4
    private const val VID_WCH = 0x1A86

    /**
     * Chip selection is by vendor ID first, then by descriptor shape. CDC-ACM is
     * the fallback because it is both the most common case and the only one that
     * can be identified from the descriptors alone.
     */
    fun create(device: UsbDevice, connection: UsbDeviceConnection): SerialDriver =
        when (device.vendorId) {
            VID_FTDI -> FtdiDriver(device, connection)
            VID_SILABS -> Cp210xDriver(device, connection)
            VID_WCH -> Ch34xDriver(device, connection)
            else -> CdcAcmDriver(device, connection)
        }

    fun driverNameFor(device: UsbDevice): String = when (device.vendorId) {
        VID_FTDI -> "FTDI"
        VID_SILABS -> "CP210x"
        VID_WCH -> "CH34x"
        else -> "CDC-ACM"
    }
}
