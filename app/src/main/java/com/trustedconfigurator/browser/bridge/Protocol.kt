package com.trustedconfigurator.browser.bridge

import org.json.JSONArray
import org.json.JSONObject

/** A decoded call from the page's polyfill. */
data class BridgeRequest(
    val id: Long,
    val op: String,
    val args: JSONObject,
)

/**
 * Maps a native failure onto a DOMException name the configurators already
 * handle. Anything unrecognised becomes NetworkError, which both apps treat as
 * a transient transport failure rather than a permanent capability gap.
 */
enum class BridgeErrorName(val jsName: String) {
    NOT_FOUND("NotFoundError"),
    SECURITY("SecurityError"),
    INVALID_STATE("InvalidStateError"),
    NOT_SUPPORTED("NotSupportedError"),
    NETWORK("NetworkError"),
    ABORT("AbortError"),
}

/** Framing for the JSON messages exchanged over the WebMessageListener channel. */
object Protocol {

    const val JS_OBJECT_NAME = "AndroidConfiguratorBridge"

    object Op {
        const val SERIAL_GET_PORTS = "serial.getPorts"
        const val SERIAL_REQUEST_PORT = "serial.requestPort"
        const val SERIAL_OPEN = "serial.open"
        const val SERIAL_CLOSE = "serial.close"
        const val SERIAL_WRITE = "serial.write"
        const val SERIAL_FLUSH = "serial.flush"
        const val SERIAL_SET_SIGNALS = "serial.setSignals"
        const val SERIAL_GET_SIGNALS = "serial.getSignals"
        const val SERIAL_FORGET = "serial.forget"

        const val USB_GET_DEVICES = "usb.getDevices"
        const val USB_REQUEST_DEVICE = "usb.requestDevice"
        const val USB_OPEN = "usb.open"
        const val USB_CLOSE = "usb.close"
        const val USB_SELECT_CONFIGURATION = "usb.selectConfiguration"
        const val USB_CLAIM_INTERFACE = "usb.claimInterface"
        const val USB_RELEASE_INTERFACE = "usb.releaseInterface"
        const val USB_SELECT_ALTERNATE_INTERFACE = "usb.selectAlternateInterface"
        const val USB_CONTROL_TRANSFER_IN = "usb.controlTransferIn"
        const val USB_CONTROL_TRANSFER_OUT = "usb.controlTransferOut"
        const val USB_TRANSFER_IN = "usb.transferIn"
        const val USB_TRANSFER_OUT = "usb.transferOut"
        const val USB_CLEAR_HALT = "usb.clearHalt"
        const val USB_RESET = "usb.reset"
        const val USB_FORGET = "usb.forget"

        // File System Access, backed by the Storage Access Framework.
        const val FILE_PICK_SAVE = "file.pickSave"
        const val FILE_WRITE = "file.write"
        const val FILE_END_SAVE = "file.endSave"
        const val FILE_PICK_OPEN = "file.pickOpen"
        const val FILE_READ = "file.read"
        const val FILE_END_OPEN = "file.endOpen"
    }

    object Event {
        const val SERIAL_DATA = "serial.data"
        const val SERIAL_CONNECT = "serial.connect"
        const val SERIAL_DISCONNECT = "serial.disconnect"
        const val USB_CONNECT = "usb.connect"
        const val USB_DISCONNECT = "usb.disconnect"
    }

    /** @return the parsed request, or null when the payload is not a well-formed call. */
    fun parseRequest(raw: String?): BridgeRequest? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val id = json.optLong("id", -1L)
            val op = json.optString("op", "")
            if (id < 0 || op.isEmpty()) return null
            BridgeRequest(id, op, json.optJSONObject("args") ?: JSONObject())
        } catch (e: Exception) {
            null
        }
    }

    fun success(id: Long, result: Any?): String =
        JSONObject()
            .put("id", id)
            .put("ok", true)
            .put("result", result ?: JSONObject.NULL)
            .toString()

    fun failure(id: Long, name: BridgeErrorName, message: String): String =
        JSONObject()
            .put("id", id)
            .put("ok", false)
            .put(
                "error",
                JSONObject()
                    .put("name", name.jsName)
                    .put("message", message),
            )
            .toString()

    fun event(name: String, fields: Map<String, Any?>): String {
        val json = JSONObject().put("event", name)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        return json.toString()
    }

    /** Reads a `[{usbVendorId, usbProductId}]` / `[{vendorId, productId}]` filter list. */
    fun parseFilters(array: JSONArray?): List<DeviceFilter> {
        if (array == null) return emptyList()
        val filters = ArrayList<DeviceFilter>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val vendorId = when {
                entry.has("usbVendorId") -> entry.optInt("usbVendorId", -1)
                entry.has("vendorId") -> entry.optInt("vendorId", -1)
                else -> -1
            }
            val productId = when {
                entry.has("usbProductId") -> entry.optInt("usbProductId", -1)
                entry.has("productId") -> entry.optInt("productId", -1)
                else -> -1
            }
            if (vendorId < 0 && productId < 0) continue
            filters.add(
                DeviceFilter(
                    vendorId = vendorId.takeIf { it >= 0 },
                    productId = productId.takeIf { it >= 0 },
                ),
            )
        }
        return filters
    }
}

/** A VID/PID filter; a null field matches anything. */
data class DeviceFilter(val vendorId: Int?, val productId: Int?) {
    fun matches(vendorId: Int, productId: Int): Boolean =
        (this.vendorId == null || this.vendorId == vendorId) &&
            (this.productId == null || this.productId == productId)
}

/**
 * Filters are a *picker* convenience, never a security boundary: Betaflight
 * fetches its VID/PID list remotely at runtime, so an empty list must mean
 * "offer everything the user could pick" rather than "deny".
 */
fun List<DeviceFilter>.matchesOrEmpty(vendorId: Int, productId: Int): Boolean =
    isEmpty() || any { it.matches(vendorId, productId) }
