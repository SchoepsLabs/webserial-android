package com.trustedconfigurator.browser.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * Decides which polyfill a device belongs to, from its descriptors.
 *
 * Both configurators listen for serial *and* USB connect events, so a DFU
 * bootloader announced as a serial port would show up as a bogus entry in
 * Betaflight's port list, and a CDC device announced to WebUSB would be offered
 * as a flashing target. Classifying by interface class keeps the two lists clean
 * without hard-coding a VID/PID table that Betaflight updates over the network.
 */
object DeviceCapabilities {

    private const val CLASS_APPLICATION_SPECIFIC = 0xFE
    private const val SUBCLASS_DFU = 0x01
    private const val VID_FTDI = 0x0403
    private const val VID_SILABS = 0x10C4
    private const val VID_WCH = 0x1A86

    /** True when the device can back a Web Serial port. */
    fun isSerial(device: UsbDevice): Boolean {
        if (device.vendorId in intArrayOf(VID_FTDI, VID_SILABS, VID_WCH)) return true
        for (i in 0 until device.interfaceCount) {
            when (device.getInterface(i).interfaceClass) {
                UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA -> return true
            }
        }
        // A vendor-specific device with a lone bulk pair and no DFU interface is
        // almost always a serial bridge; treating it as one keeps unusual boards
        // usable instead of invisible.
        return !isDfu(device) && hasSingleBulkPair(device)
    }

    /** True when the device exposes a USB DFU interface. */
    fun isDfu(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == CLASS_APPLICATION_SPECIFIC && iface.interfaceSubclass == SUBCLASS_DFU) {
                return true
            }
        }
        return false
    }

    /**
     * Classes that own a bulk endpoint pair but are definitely not serial ports.
     * Without this a USB mass-storage device — a card reader on a hub, say — is
     * offered as a flight controller in the port picker.
     */
    private val NON_SERIAL_CLASSES = intArrayOf(
        UsbConstants.USB_CLASS_AUDIO,
        UsbConstants.USB_CLASS_HID,
        UsbConstants.USB_CLASS_STILL_IMAGE,
        UsbConstants.USB_CLASS_PRINTER,
        UsbConstants.USB_CLASS_MASS_STORAGE,
        UsbConstants.USB_CLASS_HUB,
        UsbConstants.USB_CLASS_VIDEO,
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER,
    )

    private fun hasSingleBulkPair(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass in NON_SERIAL_CLASSES) continue
            var hasIn = false
            var hasOut = false
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
            }
            if (hasIn && hasOut) return true
        }
        return false
    }
}
