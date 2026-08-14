package com.trustedconfigurator.browser.drivers

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * WCH CH340/CH341 USB-UART bridges (VID 0x1A86).
 *
 * The chip has no public datasheet for its vendor requests; the register writes
 * below are the widely used sequence and match what the Linux ch341 driver does.
 */
class Ch34xDriver(
    device: UsbDevice,
    connection: UsbDeviceConnection,
) : BaseSerialDriver(device, connection) {

    override val name: String = "CH34x"

    private var controlLineState = 0

    override fun open() {
        if (device.interfaceCount == 0) {
            throw SerialDriverException("CH34x reported no interfaces")
        }
        val iface = device.getInterface(0)
        claim(iface)
        bindBulkEndpoints(iface)
        requireEndpoints()

        // Vendor initialisation handshake.
        checkState()
        controlOut(REQUEST_TYPE_OUT, CH341_REQ_SERIAL_INIT, 0x0000, 0x0000)
        writeRegisters(0x1312, 0xD982)
        writeRegisters(0x0F2C, 0x0007)
        setControlSignals(dataTerminalReady = false, requestToSend = false, breakSignal = null)
    }

    private fun checkState() {
        val buffer = ByteArray(2)
        controlIn(REQUEST_TYPE_IN, CH341_REQ_READ_VERSION, 0, 0, buffer)
    }

    private fun writeRegisters(value: Int, index: Int) {
        if (controlOut(REQUEST_TYPE_OUT, CH341_REQ_WRITE_REG, value, index) < 0) {
            throw SerialDriverException("CH34x register write failed")
        }
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: String) {
        val divisor = computeDivisor(baudRate)
            ?: throw SerialDriverException("CH34x cannot produce baud rate $baudRate")

        var lcr = CH341_LCR_ENABLE_RX or CH341_LCR_ENABLE_TX
        lcr = lcr or when (dataBits) {
            5 -> CH341_LCR_CS5
            6 -> CH341_LCR_CS6
            7 -> CH341_LCR_CS7
            else -> CH341_LCR_CS8
        }
        when (parity.lowercase()) {
            "odd" -> lcr = lcr or CH341_LCR_ENABLE_PAR
            "even" -> lcr = lcr or CH341_LCR_ENABLE_PAR or CH341_LCR_PAR_EVEN
        }
        if (stopBits == 2) lcr = lcr or CH341_LCR_STOP_BITS_2

        writeRegisters(CH341_REG_DIVISOR_PRESCALER, divisor)
        writeRegisters(CH341_REG_LCR, lcr shl 8 or lcr)
    }

    /**
     * The CH341 clock is 12 MHz divided by a prescaler and an 8-bit factor.
     * @return the packed prescaler/factor word, or null when nothing lands close enough.
     */
    private fun computeDivisor(baudRate: Int): Int? {
        if (baudRate <= 0) return null
        val prescalers = intArrayOf(1, 2, 3, 4)
        val dividers = intArrayOf(1024, 128, 16, 2)
        for (i in prescalers.indices) {
            val factor = 12_000_000 / (dividers[i] * baudRate)
            if (factor in 2..255) {
                val encoded = 256 - factor
                return (encoded shl 8) or (prescalers[i] - 1) or CH341_PRESCALER_FLAG
            }
        }
        return null
    }

    override fun setControlSignals(
        dataTerminalReady: Boolean?,
        requestToSend: Boolean?,
        breakSignal: Boolean?,
    ) {
        if (dataTerminalReady != null) {
            controlLineState = if (dataTerminalReady) {
                controlLineState or CH341_BIT_DTR
            } else {
                controlLineState and CH341_BIT_DTR.inv()
            }
        }
        if (requestToSend != null) {
            controlLineState = if (requestToSend) {
                controlLineState or CH341_BIT_RTS
            } else {
                controlLineState and CH341_BIT_RTS.inv()
            }
        }
        if (dataTerminalReady != null || requestToSend != null) {
            // The chip takes the lines active-low.
            val value = controlLineState.inv() and 0xFF
            if (controlOut(REQUEST_TYPE_OUT, CH341_REQ_MODEM_CTRL, value, 0) < 0) {
                throw SerialDriverException("CH34x modem control write failed")
            }
        }
        // The CH34x has no break request that can be issued without a full LCR
        // rewrite; Web Serial callers get a clean "unsupported" instead.
        if (breakSignal == true) {
            throw SerialDriverException("CH34x does not support the break signal")
        }
    }

    override fun readSignals(): ModemSignals {
        val buffer = ByteArray(2)
        if (controlIn(REQUEST_TYPE_IN, CH341_REQ_READ_REG, 0x0706, 0, buffer) != 2) {
            return ModemSignals()
        }
        val status = buffer[0].toInt() and 0xFF
        return ModemSignals(
            clearToSend = status and 0x01 == 0,
            dataSetReady = status and 0x02 == 0,
            ringIndicator = status and 0x04 == 0,
            dataCarrierDetect = status and 0x08 == 0,
        )
    }

    private companion object {
        const val REQUEST_TYPE_OUT = 0x40
        const val REQUEST_TYPE_IN = 0xC0
        const val CH341_REQ_READ_VERSION = 0x5F
        const val CH341_REQ_WRITE_REG = 0x9A
        const val CH341_REQ_READ_REG = 0x95
        const val CH341_REQ_SERIAL_INIT = 0xA1
        const val CH341_REQ_MODEM_CTRL = 0xA4
        const val CH341_REG_DIVISOR_PRESCALER = 0x1312
        const val CH341_REG_LCR = 0x2518
        const val CH341_PRESCALER_FLAG = 0x0080
        const val CH341_LCR_ENABLE_RX = 0x80
        const val CH341_LCR_ENABLE_TX = 0x40
        const val CH341_LCR_ENABLE_PAR = 0x08
        const val CH341_LCR_PAR_EVEN = 0x10
        const val CH341_LCR_STOP_BITS_2 = 0x04
        const val CH341_LCR_CS8 = 0x03
        const val CH341_LCR_CS7 = 0x02
        const val CH341_LCR_CS6 = 0x01
        const val CH341_LCR_CS5 = 0x00
        const val CH341_BIT_RTS = 0x20
        const val CH341_BIT_DTR = 0x40
    }
}
