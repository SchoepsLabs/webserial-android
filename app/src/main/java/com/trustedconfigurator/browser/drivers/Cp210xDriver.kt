package com.trustedconfigurator.browser.drivers

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/** Silicon Labs CP2102/CP2104/CP2105 USB-UART bridges (VID 0x10C4). */
class Cp210xDriver(
    device: UsbDevice,
    connection: UsbDeviceConnection,
) : BaseSerialDriver(device, connection) {

    override val name: String = "CP210x"

    private var interfaceNumber = 0

    override fun open() {
        if (device.interfaceCount == 0) {
            throw SerialDriverException("CP210x reported no interfaces")
        }
        val iface = device.getInterface(0)
        interfaceNumber = iface.id
        claim(iface)
        bindBulkEndpoints(iface)
        requireEndpoints()

        if (controlOut(REQUEST_TYPE_OUT, IFC_ENABLE, ENABLE, interfaceNumber) < 0) {
            throw SerialDriverException("CP210x IFC_ENABLE failed")
        }
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: String) {
        val baud = byteArrayOf(
            (baudRate and 0xFF).toByte(),
            (baudRate shr 8 and 0xFF).toByte(),
            (baudRate shr 16 and 0xFF).toByte(),
            (baudRate shr 24 and 0xFF).toByte(),
        )
        if (controlOut(REQUEST_TYPE_OUT, SET_BAUDRATE, 0, interfaceNumber, baud) < 0) {
            throw SerialDriverException("CP210x SET_BAUDRATE failed (baud $baudRate)")
        }

        val parityBits = when (parity.lowercase()) {
            "odd" -> 1
            "even" -> 2
            else -> 0
        }
        val stopBitsField = if (stopBits == 2) 2 else 0
        val lineControl = (dataBits shl 8) or (parityBits shl 4) or stopBitsField
        if (controlOut(REQUEST_TYPE_OUT, SET_LINE_CTL, lineControl, interfaceNumber) < 0) {
            throw SerialDriverException("CP210x SET_LINE_CTL failed")
        }
    }

    override fun setControlSignals(
        dataTerminalReady: Boolean?,
        requestToSend: Boolean?,
        breakSignal: Boolean?,
    ) {
        if (dataTerminalReady != null || requestToSend != null) {
            var value = 0
            // The high byte is a write mask, so untouched lines stay as they are.
            if (dataTerminalReady != null) {
                value = value or DTR_MASK
                if (dataTerminalReady) value = value or DTR_VALUE
            }
            if (requestToSend != null) {
                value = value or RTS_MASK
                if (requestToSend) value = value or RTS_VALUE
            }
            if (controlOut(REQUEST_TYPE_OUT, SET_MHS, value, interfaceNumber) < 0) {
                throw SerialDriverException("CP210x SET_MHS failed")
            }
        }
        if (breakSignal != null) {
            controlOut(REQUEST_TYPE_OUT, SET_BREAK, if (breakSignal) 1 else 0, interfaceNumber)
        }
    }

    override fun readSignals(): ModemSignals {
        val buffer = ByteArray(1)
        if (controlIn(REQUEST_TYPE_IN, GET_MDMSTS, 0, interfaceNumber, buffer) != 1) {
            return ModemSignals()
        }
        val status = buffer[0].toInt() and 0xFF
        return ModemSignals(
            clearToSend = status and 0x10 != 0,
            dataSetReady = status and 0x20 != 0,
            ringIndicator = status and 0x40 != 0,
            dataCarrierDetect = status and 0x80 != 0,
        )
    }

    override fun close() {
        runCatching { controlOut(REQUEST_TYPE_OUT, IFC_ENABLE, DISABLE, interfaceNumber) }
        super.close()
    }

    private companion object {
        const val REQUEST_TYPE_OUT = 0x41
        const val REQUEST_TYPE_IN = 0xC1
        const val IFC_ENABLE = 0x00
        const val SET_LINE_CTL = 0x03
        const val SET_BREAK = 0x05
        const val SET_MHS = 0x07
        const val GET_MDMSTS = 0x08
        const val SET_BAUDRATE = 0x1E
        const val ENABLE = 0x0001
        const val DISABLE = 0x0000
        const val DTR_VALUE = 0x0001
        const val RTS_VALUE = 0x0002
        const val DTR_MASK = 0x0100
        const val RTS_MASK = 0x0200
    }
}
