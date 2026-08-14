package com.trustedconfigurator.browser.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** Result of a USB transfer, mirroring WebUSB's `USBTransferStatus`. */
data class TransferResult(val status: String, val data: ByteArray, val bytesWritten: Int) {
    companion object {
        const val OK = "ok"
        const val STALL = "stall"
        const val BABBLE = "babble"
    }

    override fun equals(other: Any?): Boolean =
        other is TransferResult &&
            status == other.status &&
            bytesWritten == other.bytesWritten &&
            data.contentEquals(other.data)

    override fun hashCode(): Int = (status.hashCode() * 31 + bytesWritten) * 31 + data.contentHashCode()
}

/**
 * One open WebUSB device.
 *
 * This is the transport under Betaflight's STM32 DFU flashing, which reads all
 * of its descriptors through raw GET_DESCRIPTOR control transfers rather than
 * through a descriptor tree, so control transfers are the hot path.
 */
class UsbSession(
    val handle: String,
    val origin: String,
    var device: UsbDevice,
    private val hub: UsbHub,
    private var connection: UsbDeviceConnection,
) {

    private val claimedInterfaces = LinkedHashMap<Int, UsbInterface>()
    var configurationValue: Int? = null
        private set

    private val label: String get() = device.productName ?: device.deviceName

    fun claimedInterfaceNumbers(): List<Int> = claimedInterfaces.keys.toList()

    fun selectConfiguration(value: Int) {
        // Android picks a configuration when the device is opened and only
        // exposes setConfiguration from API 21; on a single-configuration device
        // (every DFU bootloader here) this is effectively a no-op that still has
        // to report success so the DFU layer proceeds.
        val configuration = (0 until device.configurationCount)
            .map { device.getConfiguration(it) }
            .firstOrNull { it.id == value }
        if (configuration != null) {
            runCatching { connection.setConfiguration(configuration) }
        }
        configurationValue = value
        TransferLog.record(TransferKind.EVENT, origin, label, "selectConfiguration($value)")
    }

    fun claimInterface(interfaceNumber: Int) {
        if (claimedInterfaces.containsKey(interfaceNumber)) return
        val iface = findInterface(interfaceNumber)
            ?: throw UsbSessionException("NotFoundError", "Interface $interfaceNumber not found on $label")
        if (!connection.claimInterface(iface, true)) {
            throw UsbSessionException("NetworkError", "Could not claim interface $interfaceNumber")
        }
        claimedInterfaces[interfaceNumber] = iface
        TransferLog.record(TransferKind.EVENT, origin, label, "claimInterface($interfaceNumber)")
    }

    fun releaseInterface(interfaceNumber: Int) {
        val iface = claimedInterfaces.remove(interfaceNumber) ?: return
        runCatching { connection.releaseInterface(iface) }
        TransferLog.record(TransferKind.EVENT, origin, label, "releaseInterface($interfaceNumber)")
    }

    fun selectAlternateInterface(interfaceNumber: Int, alternateSetting: Int) {
        val iface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber && it.alternateSetting == alternateSetting }
            ?: throw UsbSessionException(
                "NotFoundError",
                "Interface $interfaceNumber alternate $alternateSetting not found",
            )
        if (!connection.claimInterface(iface, true)) {
            throw UsbSessionException("NetworkError", "Could not select alternate setting $alternateSetting")
        }
        claimedInterfaces[interfaceNumber] = iface
        TransferLog.record(
            TransferKind.EVENT,
            origin,
            label,
            "selectAlternateInterface($interfaceNumber, $alternateSetting)",
        )
    }

    fun controlTransferIn(setup: ControlSetup, length: Int): TransferResult {
        val buffer = ByteArray(length.coerceAtLeast(0))
        val requestType = setup.requestTypeByte(UsbConstants.USB_DIR_IN)
        val transferred = connection.controlTransfer(
            requestType,
            setup.request,
            setup.value,
            setup.index,
            buffer,
            buffer.size,
            CONTROL_TIMEOUT_MS,
        )
        TransferLog.record(
            TransferKind.CONTROL_IN,
            origin,
            label,
            "bmRequestType=0x%02X bRequest=0x%02X wValue=0x%04X wIndex=0x%04X".format(
                requestType, setup.request, setup.value, setup.index,
            ),
            maxOf(transferred, 0),
        )
        // A stall is reported, never thrown: the DFU descriptor probe treats an
        // unsupported optional read (a missing LANGID table, say) as recoverable
        // and only a thrown error would break it.
        return if (transferred < 0) {
            TransferResult(TransferResult.STALL, ByteArray(0), 0)
        } else {
            TransferResult(TransferResult.OK, buffer.copyOf(transferred), transferred)
        }
    }

    fun controlTransferOut(setup: ControlSetup, data: ByteArray): TransferResult {
        val requestType = setup.requestTypeByte(UsbConstants.USB_DIR_OUT)
        val transferred = connection.controlTransfer(
            requestType,
            setup.request,
            setup.value,
            setup.index,
            if (data.isEmpty()) null else data,
            data.size,
            CONTROL_TIMEOUT_MS,
        )
        TransferLog.record(
            TransferKind.CONTROL_OUT,
            origin,
            label,
            "bmRequestType=0x%02X bRequest=0x%02X wValue=0x%04X wIndex=0x%04X".format(
                requestType, setup.request, setup.value, setup.index,
            ),
            maxOf(transferred, 0),
        )
        return if (transferred < 0) {
            TransferResult(TransferResult.STALL, ByteArray(0), 0)
        } else {
            TransferResult(TransferResult.OK, ByteArray(0), transferred)
        }
    }

    fun transferIn(endpointNumber: Int, length: Int): TransferResult {
        val endpoint = findEndpoint(endpointNumber, UsbConstants.USB_DIR_IN)
            ?: throw UsbSessionException("NotFoundError", "IN endpoint $endpointNumber is not claimed")
        val buffer = ByteArray(length.coerceAtLeast(0))
        val transferred = connection.bulkTransfer(endpoint, buffer, buffer.size, BULK_TIMEOUT_MS)
        TransferLog.record(TransferKind.BULK_IN, origin, label, "transferIn(ep $endpointNumber)", maxOf(transferred, 0))
        return if (transferred < 0) {
            TransferResult(TransferResult.STALL, ByteArray(0), 0)
        } else {
            TransferResult(TransferResult.OK, buffer.copyOf(transferred), transferred)
        }
    }

    fun transferOut(endpointNumber: Int, data: ByteArray): TransferResult {
        val endpoint = findEndpoint(endpointNumber, UsbConstants.USB_DIR_OUT)
            ?: throw UsbSessionException("NotFoundError", "OUT endpoint $endpointNumber is not claimed")
        val transferred = connection.bulkTransfer(endpoint, data, data.size, BULK_TIMEOUT_MS)
        TransferLog.record(
            TransferKind.BULK_OUT,
            origin,
            label,
            "transferOut(ep $endpointNumber)",
            maxOf(transferred, 0),
        )
        return if (transferred < 0) {
            TransferResult(TransferResult.STALL, ByteArray(0), 0)
        } else {
            TransferResult(TransferResult.OK, ByteArray(0), transferred)
        }
    }

    fun clearHalt(direction: String, endpointNumber: Int) {
        val directionBit = if (direction == "in") UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT
        // Android has no clearHalt, so this is the standard CLEAR_FEATURE
        // (ENDPOINT_HALT) request issued by hand.
        connection.controlTransfer(
            0x02,
            0x01,
            0x0000,
            endpointNumber or directionBit,
            null,
            0,
            CONTROL_TIMEOUT_MS,
        )
        TransferLog.record(TransferKind.CONTROL_OUT, origin, label, "clearHalt($direction, $endpointNumber)")
    }

    /**
     * Best-effort stand-in for a USB port reset, which Android does not expose.
     * Interfaces are released and the connection is reopened; callers treat a
     * failure as non-fatal, so this never throws.
     */
    fun reset() {
        val interfaceNumbers = claimedInterfaces.keys.toList()
        claimedInterfaces.values.forEach { runCatching { connection.releaseInterface(it) } }
        claimedInterfaces.clear()
        runCatching { connection.close() }

        val refreshed = hub.deviceByName(device.deviceName) ?: device
        val reopened = hub.openConnection(refreshed)
        if (reopened != null) {
            device = refreshed
            connection = reopened
            interfaceNumbers.forEach { number -> runCatching { claimInterface(number) } }
            TransferLog.record(TransferKind.EVENT, origin, label, "reset(): connection reopened")
        } else {
            TransferLog.record(
                TransferKind.EVENT,
                origin,
                label,
                "reset(): device did not reopen (expected if it is re-enumerating)",
            )
        }
    }

    fun close() {
        claimedInterfaces.values.forEach { runCatching { connection.releaseInterface(it) } }
        claimedInterfaces.clear()
        runCatching { connection.close() }
        TransferLog.record(TransferKind.EVENT, origin, label, "USB device closed")
    }

    private fun findInterface(interfaceNumber: Int): UsbInterface? =
        (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber }

    private fun findEndpoint(endpointNumber: Int, direction: Int): UsbEndpoint? {
        val candidates = claimedInterfaces.values.ifEmpty {
            (0 until device.interfaceCount).map { device.getInterface(it) }
        }
        for (iface in candidates) {
            for (i in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(i)
                if (endpoint.endpointNumber == endpointNumber && endpoint.direction == direction) {
                    return endpoint
                }
            }
        }
        return null
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 5000
        const val BULK_TIMEOUT_MS = 5000
    }
}

class UsbSessionException(val errorName: String, message: String) : Exception(message)

/** A WebUSB `USBControlTransferParameters` setup packet. */
data class ControlSetup(
    val requestType: String,
    val recipient: String,
    val request: Int,
    val value: Int,
    val index: Int,
) {
    fun requestTypeByte(directionBit: Int): Int {
        val type = when (requestType.lowercase()) {
            "class" -> 0x20
            "vendor" -> 0x40
            else -> 0x00 // standard
        }
        val target = when (recipient.lowercase()) {
            "interface" -> 0x01
            "endpoint" -> 0x02
            "other" -> 0x03
            else -> 0x00 // device
        }
        return directionBit or type or target
    }
}
