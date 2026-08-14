package com.trustedconfigurator.browser.drivers

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * WCH CH340/CH341 USB-UART bridges (VID 0x1A86).
 *
 * The chip has no public datasheet for its vendor requests. The register
 * sequence and the baud-rate maths here follow mik3y's usb-serial-for-android
 * (MIT), which is the de-facto reference on Android and matches the Linux
 * ch341 driver.
 *
 * Register writes put the register address in wValue and the data in wIndex.
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

        // Vendor initialisation. The reads are part of the handshake — the chip
        // expects them even though the values are not acted on.
        readRegisters(0x5F, 0x0000)
        vendorWrite(REQ_SERIAL_INIT, 0x0000, 0x0000)
        setBaudRate(DEFAULT_BAUD_RATE)
        readRegisters(0x95, REG_LCR)
        writeRegister(REG_LCR, LCR_ENABLE_RX or LCR_ENABLE_TX or LCR_CS8)
        readRegisters(0x95, REG_STATUS)
        vendorWrite(REQ_SERIAL_INIT, 0x501F, 0xD90A)
        setBaudRate(DEFAULT_BAUD_RATE)
        applyControlLines()
    }

    private fun vendorWrite(request: Int, value: Int, index: Int) {
        if (controlOut(REQUEST_TYPE_OUT, request, value, index) < 0) {
            throw SerialDriverException("CH34x vendor write 0x%02X failed".format(request))
        }
    }

    private fun writeRegister(register: Int, data: Int) = vendorWrite(REQ_WRITE_REG, register, data)

    private fun readRegisters(request: Int, register: Int): ByteArray {
        val buffer = ByteArray(2)
        controlIn(REQUEST_TYPE_IN, request, register, 0, buffer)
        return buffer
    }

    private fun setBaudRate(baudRate: Int) {
        val divisor = Ch34xBaud.encode(baudRate)
        writeRegister(REG_DIVISOR, divisor.divisorRegister)
        writeRegister(REG_FACTOR, divisor.factorRegister)
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: String) {
        setBaudRate(baudRate)

        var lcr = LCR_ENABLE_RX or LCR_ENABLE_TX
        lcr = lcr or when (dataBits) {
            5 -> LCR_CS5
            6 -> LCR_CS6
            7 -> LCR_CS7
            8 -> LCR_CS8
            else -> throw SerialDriverException("CH34x: invalid data bits $dataBits")
        }
        when (parity.lowercase()) {
            "none" -> Unit
            "odd" -> lcr = lcr or LCR_ENABLE_PAR
            "even" -> lcr = lcr or LCR_ENABLE_PAR or LCR_PAR_EVEN
            else -> throw SerialDriverException("CH34x: unsupported parity $parity")
        }
        if (stopBits == 2) lcr = lcr or LCR_STOP_BITS_2

        writeRegister(REG_LCR, lcr)
    }

    override fun setControlSignals(
        dataTerminalReady: Boolean?,
        requestToSend: Boolean?,
        breakSignal: Boolean?,
    ) {
        if (dataTerminalReady != null) {
            controlLineState = if (dataTerminalReady) {
                controlLineState or BIT_DTR
            } else {
                controlLineState and BIT_DTR.inv()
            }
        }
        if (requestToSend != null) {
            controlLineState = if (requestToSend) {
                controlLineState or BIT_RTS
            } else {
                controlLineState and BIT_RTS.inv()
            }
        }
        if (dataTerminalReady != null || requestToSend != null) {
            applyControlLines()
        }
        // Break needs a full LCR rewrite on this chip; report it rather than
        // silently doing nothing.
        if (breakSignal == true) {
            throw SerialDriverException("CH34x does not support the break signal")
        }
    }

    private fun applyControlLines() {
        // The chip drives these active-low.
        vendorWrite(REQ_MODEM_CTRL, controlLineState.inv() and 0xFF, 0)
    }

    override fun readSignals(): ModemSignals {
        val status = readRegisters(0x95, REG_STATUS)
        val bits = status[0].toInt() and 0xFF
        // Also active-low.
        return ModemSignals(
            clearToSend = bits and 0x01 == 0,
            dataSetReady = bits and 0x02 == 0,
            ringIndicator = bits and 0x04 == 0,
            dataCarrierDetect = bits and 0x08 == 0,
        )
    }

    private companion object {
        const val REQUEST_TYPE_OUT = 0x40
        const val REQUEST_TYPE_IN = 0xC0

        const val REQ_WRITE_REG = 0x9A
        const val REQ_SERIAL_INIT = 0xA1
        const val REQ_MODEM_CTRL = 0xA4

        const val REG_DIVISOR = 0x1312
        const val REG_FACTOR = 0x0F2C
        const val REG_LCR = 0x2518
        const val REG_STATUS = 0x0706

        const val DEFAULT_BAUD_RATE = 9600

        const val LCR_ENABLE_RX = 0x80
        const val LCR_ENABLE_TX = 0x40
        const val LCR_ENABLE_PAR = 0x08
        const val LCR_PAR_EVEN = 0x10
        const val LCR_STOP_BITS_2 = 0x04
        const val LCR_CS8 = 0x03
        const val LCR_CS7 = 0x02
        const val LCR_CS6 = 0x01
        const val LCR_CS5 = 0x00

        const val BIT_RTS = 0x20
        const val BIT_DTR = 0x40
    }
}
