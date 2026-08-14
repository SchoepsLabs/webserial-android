package com.trustedconfigurator.browser.drivers

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * FTDI FT232R and friends (VID 0x0403).
 *
 * The FTDI read path is the awkward one: the chip prepends two modem-status
 * bytes to *every* USB packet, so [read] has to strip them per packet rather
 * than once per transfer, or the serial stream is corrupted every 64 bytes.
 */
class FtdiDriver(
    device: UsbDevice,
    connection: UsbDeviceConnection,
) : BaseSerialDriver(device, connection) {

    override val name: String = "FTDI"

    private var interfaceNumber = 0
    private var controlLineState = 0
    private var lastSignals = ModemSignals()

    override fun open() {
        if (device.interfaceCount == 0) {
            throw SerialDriverException("FTDI device reported no interfaces")
        }
        val iface = device.getInterface(0)
        interfaceNumber = iface.id
        claim(iface)
        bindBulkEndpoints(iface)
        requireEndpoints()

        if (controlOut(REQUEST_TYPE_OUT, SIO_RESET, SIO_RESET_SIO, interfaceNumber + 1) < 0) {
            throw SerialDriverException("FTDI reset failed")
        }
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: String) {
        val (value, index) = FtdiBaud.encode(baudRate)
        // wIndex carries only the sub-divisor high bit on a single-port part.
        // OR-ing the interface number in here would set that bit spuriously and
        // silently shift the baud rate.
        if (controlOut(REQUEST_TYPE_OUT, SIO_SET_BAUD_RATE, value, index) < 0) {
            throw SerialDriverException("FTDI SET_BAUD_RATE failed (baud $baudRate)")
        }

        var config = dataBits and 0x0F
        config = config or when (parity.lowercase()) {
            "odd" -> 0x100
            "even" -> 0x200
            else -> 0x000
        }
        if (stopBits == 2) config = config or 0x1000
        if (controlOut(REQUEST_TYPE_OUT, SIO_SET_DATA, config, interfaceNumber + 1) < 0) {
            throw SerialDriverException("FTDI SET_DATA failed")
        }
    }

    override fun setControlSignals(
        dataTerminalReady: Boolean?,
        requestToSend: Boolean?,
        breakSignal: Boolean?,
    ) {
        if (dataTerminalReady != null) {
            controlLineState = if (dataTerminalReady) {
                controlLineState or DTR_ENABLE
            } else {
                controlLineState and DTR_ENABLE.inv()
            }
            val value = DTR_MASK or (if (dataTerminalReady) 0x0001 else 0x0000)
            if (controlOut(REQUEST_TYPE_OUT, SIO_MODEM_CTRL, value, interfaceNumber + 1) < 0) {
                throw SerialDriverException("FTDI DTR write failed")
            }
        }
        if (requestToSend != null) {
            controlLineState = if (requestToSend) {
                controlLineState or RTS_ENABLE
            } else {
                controlLineState and RTS_ENABLE.inv()
            }
            val value = RTS_MASK or (if (requestToSend) 0x0002 else 0x0000)
            if (controlOut(REQUEST_TYPE_OUT, SIO_MODEM_CTRL, value, interfaceNumber + 1) < 0) {
                throw SerialDriverException("FTDI RTS write failed")
            }
        }
        if (breakSignal != null) {
            // Break lives in the SET_DATA word, so it is re-issued with 8N1.
            val config = 0x0008 or (if (breakSignal) 0x4000 else 0x0000)
            controlOut(REQUEST_TYPE_OUT, SIO_SET_DATA, config, interfaceNumber + 1)
        }
    }

    override fun readSignals(): ModemSignals = lastSignals

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
        val endpoint = readEndpoint ?: throw SerialDriverException("Port is not open")
        val packetSize = endpoint.maxPacketSize.coerceAtLeast(MODEM_STATUS_HEADER_LENGTH + 1)
        val raw = ByteArray(buffer.size + (buffer.size / (packetSize - MODEM_STATUS_HEADER_LENGTH) + 1) * MODEM_STATUS_HEADER_LENGTH)
        val count = connection.bulkTransfer(endpoint, raw, minOf(raw.size, packetSize * 4), timeoutMillis)
        if (count <= 0) return 0

        var written = 0
        var offset = 0
        while (offset < count) {
            val block = minOf(packetSize, count - offset)
            if (block < MODEM_STATUS_HEADER_LENGTH) break
            updateSignals(raw[offset], raw[offset + 1])
            val payload = block - MODEM_STATUS_HEADER_LENGTH
            if (payload > 0) {
                val room = minOf(payload, buffer.size - written)
                if (room <= 0) break
                System.arraycopy(raw, offset + MODEM_STATUS_HEADER_LENGTH, buffer, written, room)
                written += room
            }
            offset += block
        }
        return written
    }

    private fun updateSignals(status0: Byte, status1: Byte) {
        val modem = status0.toInt() and 0xFF
        lastSignals = ModemSignals(
            clearToSend = modem and 0x10 != 0,
            dataSetReady = modem and 0x20 != 0,
            ringIndicator = modem and 0x40 != 0,
            dataCarrierDetect = modem and 0x80 != 0,
        )
    }

    private companion object {
        const val REQUEST_TYPE_OUT = 0x40
        const val SIO_RESET = 0x00
        const val SIO_MODEM_CTRL = 0x01
        const val SIO_SET_BAUD_RATE = 0x03
        const val SIO_SET_DATA = 0x04
        const val SIO_RESET_SIO = 0x00
        const val MODEM_STATUS_HEADER_LENGTH = 2
        const val DTR_ENABLE = 0x01
        const val RTS_ENABLE = 0x02
        const val DTR_MASK = 0x0100
        const val RTS_MASK = 0x0200
    }
}
