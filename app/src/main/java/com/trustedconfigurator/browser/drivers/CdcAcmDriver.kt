package com.trustedconfigurator.browser.drivers

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * USB CDC-ACM (virtual COM port).
 *
 * This covers every STM32/AT32/GD32/APM32/X32/RP2040 flight controller, which is
 * effectively the whole Betaflight serial device list apart from the USB-TTL
 * adapters handled by the other drivers.
 */
class CdcAcmDriver(
    device: UsbDevice,
    connection: UsbDeviceConnection,
) : BaseSerialDriver(device, connection) {

    override val name: String = "CDC-ACM"

    private var controlInterfaceNumber: Int = 0
    private var statusEndpoint: UsbEndpoint? = null
    private var lastSignals = ModemSignals()
    private var controlLineState = 0

    override fun open() {
        var controlInterface: UsbInterface? = null
        var dataInterface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            when (iface.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> if (controlInterface == null) controlInterface = iface
                UsbConstants.USB_CLASS_CDC_DATA -> if (dataInterface == null) dataInterface = iface
            }
        }

        // Some boards (and most bootloader-adjacent composite devices) report a
        // vendor-specific class instead of 0x0A but are still ACM underneath, so
        // fall back to the first interface that actually has a bulk pair.
        if (dataInterface == null) {
            dataInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { iface -> hasBulkPair(iface) }
        }

        if (dataInterface == null) {
            throw SerialDriverException("No CDC data interface found on ${device.deviceName}")
        }

        controlInterface?.let {
            controlInterfaceNumber = it.id
            claim(it)
            bindStatusEndpoint(it)
        }
        claim(dataInterface)
        bindBulkEndpoints(dataInterface)
        requireEndpoints()
    }

    private fun hasBulkPair(iface: UsbInterface): Boolean {
        var hasIn = false
        var hasOut = false
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
        }
        return hasIn && hasOut
    }

    private fun bindStatusEndpoint(iface: UsbInterface) {
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                endpoint.direction == UsbConstants.USB_DIR_IN
            ) {
                statusEndpoint = endpoint
                return
            }
        }
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: String) {
        val stopByte = when (stopBits) {
            2 -> 2
            else -> 0 // 1 stop bit; 1.5 (value 1) is not exposed by Web Serial
        }
        val parityByte = when (parity.lowercase()) {
            "odd" -> 1
            "even" -> 2
            else -> 0
        }
        val lineCoding = byteArrayOf(
            (baudRate and 0xFF).toByte(),
            (baudRate shr 8 and 0xFF).toByte(),
            (baudRate shr 16 and 0xFF).toByte(),
            (baudRate shr 24 and 0xFF).toByte(),
            stopByte.toByte(),
            parityByte.toByte(),
            dataBits.toByte(),
        )
        val result = controlOut(
            REQUEST_TYPE_CLASS_OUT,
            SET_LINE_CODING,
            0,
            controlInterfaceNumber,
            lineCoding,
        )
        if (result < 0) {
            // Composite boards that ignore SET_LINE_CODING still stream fine at
            // the FC's fixed rate, so this is reported rather than fatal.
            throw SerialDriverException("SET_LINE_CODING rejected (baud $baudRate)")
        }
    }

    override fun setControlSignals(
        dataTerminalReady: Boolean?,
        requestToSend: Boolean?,
        breakSignal: Boolean?,
    ) {
        if (dataTerminalReady != null || requestToSend != null) {
            if (dataTerminalReady != null) {
                controlLineState = if (dataTerminalReady) {
                    controlLineState or DTR_BIT
                } else {
                    controlLineState and DTR_BIT.inv()
                }
            }
            if (requestToSend != null) {
                controlLineState = if (requestToSend) {
                    controlLineState or RTS_BIT
                } else {
                    controlLineState and RTS_BIT.inv()
                }
            }
            val result = controlOut(
                REQUEST_TYPE_CLASS_OUT,
                SET_CONTROL_LINE_STATE,
                controlLineState,
                controlInterfaceNumber,
            )
            if (result < 0) {
                throw SerialDriverException("SET_CONTROL_LINE_STATE rejected")
            }
        }

        if (breakSignal != null) {
            controlOut(
                REQUEST_TYPE_CLASS_OUT,
                SEND_BREAK,
                if (breakSignal) 0xFFFF else 0x0000,
                controlInterfaceNumber,
            )
        }
    }

    override fun readSignals(): ModemSignals {
        val endpoint = statusEndpoint ?: return lastSignals
        val buffer = ByteArray(16)
        // Drain what has already arrived: SERIAL_STATE is unsolicited, so the
        // freshest notification wins and an empty queue keeps the cached value.
        // A short timeout rather than 0, which some kernels read as "block forever".
        while (true) {
            val count = connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MS)
            if (count < 10) break
            if ((buffer[1].toInt() and 0xFF) != SERIAL_STATE_NOTIFICATION) continue
            val state = (buffer[8].toInt() and 0xFF) or (buffer[9].toInt() and 0xFF shl 8)
            lastSignals = ModemSignals(
                dataCarrierDetect = state and 0x01 != 0,
                dataSetReady = state and 0x02 != 0,
                ringIndicator = state and 0x08 != 0,
                clearToSend = lastSignals.clearToSend,
            )
        }
        return lastSignals
    }

    override fun close() {
        runCatching { setControlSignals(dataTerminalReady = false, requestToSend = false, breakSignal = null) }
        statusEndpoint = null
        super.close()
    }

    private companion object {
        const val REQUEST_TYPE_CLASS_OUT = 0x21
        const val SET_LINE_CODING = 0x20
        const val SET_CONTROL_LINE_STATE = 0x22
        const val SEND_BREAK = 0x23
        const val SERIAL_STATE_NOTIFICATION = 0x20
        const val STATUS_POLL_TIMEOUT_MS = 5
        const val DTR_BIT = 0x01
        const val RTS_BIT = 0x02
    }
}
