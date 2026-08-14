package com.trustedconfigurator.browser.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import org.json.JSONArray
import org.json.JSONObject

/** Builds the JSON descriptors the polyfill turns into SerialPort / USBDevice objects. */
object DeviceDescriptors {

    fun serialPort(handle: String, device: UsbDevice): JSONObject =
        JSONObject()
            .put("handle", handle)
            .put("usbVendorId", device.vendorId)
            .put("usbProductId", device.productId)

    fun usbDevice(handle: String, device: UsbDevice, serialNumber: String?): JSONObject {
        val version = parseBcdVersion(device.version)
        return JSONObject()
            .put("handle", handle)
            .put("vendorId", device.vendorId)
            .put("productId", device.productId)
            .put("deviceClass", device.deviceClass)
            .put("deviceSubclass", device.deviceSubclass)
            .put("deviceProtocol", device.deviceProtocol)
            .put("deviceVersionMajor", version.major)
            .put("deviceVersionMinor", version.minor)
            .put("deviceVersionSubminor", version.subminor)
            // Android exposes no bcdUSB, and neither configurator reads it beyond
            // logging, so a truthful-enough constant beats a fabricated parse.
            .put("usbVersionMajor", 2)
            .put("usbVersionMinor", 0)
            .put("usbVersionSubminor", 0)
            .put("manufacturerName", device.manufacturerName ?: JSONObject.NULL)
            .put("productName", device.productName ?: JSONObject.NULL)
            .put("serialNumber", serialNumber ?: JSONObject.NULL)
            .put("configurations", configurations(device))
    }

    private fun configurations(device: UsbDevice): JSONArray {
        val array = JSONArray()
        for (i in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(i)
            val interfaces = LinkedHashMap<Int, MutableList<UsbInterface>>()
            for (j in 0 until configuration.interfaceCount) {
                val iface = configuration.getInterface(j)
                // Android lists each alternate setting as its own UsbInterface;
                // WebUSB nests them under one interface, so they are regrouped.
                interfaces.getOrPut(iface.id) { mutableListOf() }.add(iface)
            }
            array.put(
                JSONObject()
                    .put("configurationValue", configuration.id)
                    .put("configurationName", configuration.name ?: JSONObject.NULL)
                    .put(
                        "interfaces",
                        JSONArray().also { list ->
                            interfaces.forEach { (id, alternates) ->
                                list.put(usbInterface(id, alternates))
                            }
                        },
                    ),
            )
        }
        return array
    }

    private fun usbInterface(interfaceNumber: Int, alternates: List<UsbInterface>): JSONObject =
        JSONObject()
            .put("interfaceNumber", interfaceNumber)
            .put(
                "alternates",
                JSONArray().also { list ->
                    alternates.sortedBy { it.alternateSetting }.forEach { list.put(alternate(it)) }
                },
            )

    private fun alternate(iface: UsbInterface): JSONObject =
        JSONObject()
            .put("alternateSetting", iface.alternateSetting)
            .put("interfaceClass", iface.interfaceClass)
            .put("interfaceSubclass", iface.interfaceSubclass)
            .put("interfaceProtocol", iface.interfaceProtocol)
            .put("interfaceName", iface.name ?: JSONObject.NULL)
            .put(
                "endpoints",
                JSONArray().also { list ->
                    for (i in 0 until iface.endpointCount) {
                        val endpoint = iface.getEndpoint(i)
                        list.put(
                            JSONObject()
                                .put("endpointNumber", endpoint.endpointNumber)
                                .put(
                                    "direction",
                                    if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                                )
                                .put("type", endpointType(endpoint.type))
                                .put("packetSize", endpoint.maxPacketSize),
                        )
                    }
                },
            )

    private fun endpointType(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        else -> "control"
    }

    data class BcdVersion(val major: Int, val minor: Int, val subminor: Int)

    /**
     * Android reports bcdDevice as a display string such as "1.00", which WebUSB
     * splits into three numbers. A value that will not parse yields 0.0.0 rather
     * than failing the whole descriptor.
     */
    fun parseBcdVersion(version: String?): BcdVersion {
        if (version.isNullOrBlank()) return BcdVersion(0, 0, 0)
        val parts = version.trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val fraction = parts.getOrNull(1)?.padEnd(2, '0') ?: "00"
        val minor = fraction.getOrNull(0)?.digitToIntOrNull() ?: 0
        val subminor = fraction.getOrNull(1)?.digitToIntOrNull() ?: 0
        return BcdVersion(major, minor, subminor)
    }
}
