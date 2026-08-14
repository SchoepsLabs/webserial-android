package com.trustedconfigurator.browser.bridge

import android.hardware.usb.UsbDevice
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.trustedconfigurator.browser.files.FileBridge
import com.trustedconfigurator.browser.files.FileBridgeException
import com.trustedconfigurator.browser.usb.ControlSetup
import com.trustedconfigurator.browser.usb.DeviceCapabilities
import com.trustedconfigurator.browser.usb.SerialSession
import com.trustedconfigurator.browser.usb.TransferKind
import com.trustedconfigurator.browser.usb.TransferLog
import com.trustedconfigurator.browser.usb.UsbHub
import com.trustedconfigurator.browser.usb.UsbSession
import com.trustedconfigurator.browser.usb.UsbSessionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Asks the user which device a site may use. Implemented by the hosting activity. */
interface DevicePicker {
    suspend fun choose(origin: String, prompt: String, devices: List<UsbDevice>): UsbDevice?
}

/**
 * Dispatches the polyfill's JSON calls onto the Android USB Host API.
 *
 * Registered through [WebViewCompat.addWebMessageListener] with an explicit
 * origin allow-list, so the JS object this talks to is never injected into an
 * untrusted page in the first place. [OriginPolicy.isAllowed] is re-checked on
 * every message anyway — a second, independent gate rather than a reliance on
 * WebView's rule matching being correct.
 */
class ConfiguratorBridge(
    private val hub: UsbHub,
    private val picker: DevicePicker,
    private val policy: SitePolicy,
    private val files: FileBridge,
    private val scope: CoroutineScope,
) : WebViewCompat.WebMessageListener {

    private val handles = HandleRegistry()

    /** "origin|handle" -> session */
    private val serialSessions = ConcurrentHashMap<String, SerialSession>()
    private val usbSessions = ConcurrentHashMap<String, UsbSession>()

    private val replyProxies = ConcurrentHashMap<String, MutableSet<JavaScriptReplyProxy>>()

    // ------------------------------------------------------------- listener

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val origin = OriginPolicy.normalize(sourceOrigin.toString())
        if (origin == null || !policy.isUsbAllowed(origin)) {
            // The injections are already origin-scoped; this is the independent
            // second check, and it also catches a site whose access was revoked
            // after its document was loaded.
            TransferLog.record(
                TransferKind.ERROR,
                origin = sourceOrigin.toString(),
                device = "-",
                detail = "Rejected bridge message from an origin without USB access",
            )
            return
        }

        replyProxies.getOrPut(origin) { java.util.Collections.synchronizedSet(mutableSetOf()) }.add(replyProxy)

        val request = Protocol.parseRequest(message.data)
        if (request == null) {
            TransferLog.record(TransferKind.ERROR, origin, "-", "Malformed bridge message")
            return
        }

        scope.launch(Dispatchers.IO) {
            val response = try {
                val result = dispatch(origin, request)
                Protocol.success(request.id, result)
            } catch (e: UsbSessionException) {
                Protocol.failure(request.id, errorNameOf(e.errorName), e.message ?: "USB operation failed")
            } catch (e: FileBridgeException) {
                Protocol.failure(request.id, BridgeErrorName.NETWORK, e.message ?: "File operation failed")
            } catch (e: BridgeException) {
                Protocol.failure(request.id, e.errorName, e.message ?: "USB operation failed")
            } catch (e: Exception) {
                TransferLog.record(TransferKind.ERROR, origin, "-", "${request.op}: ${e.message}")
                Protocol.failure(request.id, BridgeErrorName.NETWORK, e.message ?: "USB operation failed")
            }
            withContext(Dispatchers.Main) {
                runCatching { replyProxy.postMessage(response) }
            }
        }
    }

    private fun errorNameOf(jsName: String): BridgeErrorName =
        BridgeErrorName.values().firstOrNull { it.jsName == jsName } ?: BridgeErrorName.NETWORK

    // ------------------------------------------------------------- dispatch

    private suspend fun dispatch(origin: String, request: BridgeRequest): Any? = when (request.op) {
        Protocol.Op.SERIAL_GET_PORTS -> serialGetPorts(origin)
        Protocol.Op.SERIAL_REQUEST_PORT -> serialRequestPort(origin, request.args)
        Protocol.Op.SERIAL_OPEN -> serialOpen(origin, request.args)
        Protocol.Op.SERIAL_CLOSE -> serialClose(origin, request.args)
        Protocol.Op.SERIAL_WRITE -> serialWrite(origin, request.args)
        Protocol.Op.SERIAL_FLUSH -> JSONObject()
        Protocol.Op.SERIAL_SET_SIGNALS -> serialSetSignals(origin, request.args)
        Protocol.Op.SERIAL_GET_SIGNALS -> serialGetSignals(origin, request.args)
        Protocol.Op.SERIAL_FORGET -> forget(origin, request.args, serial = true)

        Protocol.Op.USB_GET_DEVICES -> usbGetDevices(origin)
        Protocol.Op.USB_REQUEST_DEVICE -> usbRequestDevice(origin, request.args)
        Protocol.Op.USB_OPEN -> usbOpen(origin, request.args)
        Protocol.Op.USB_CLOSE -> usbClose(origin, request.args)
        Protocol.Op.USB_SELECT_CONFIGURATION -> usbSession(origin, request.args).let {
            it.selectConfiguration(request.args.getInt("configurationValue"))
            JSONObject()
        }
        Protocol.Op.USB_CLAIM_INTERFACE -> usbSession(origin, request.args).let {
            it.claimInterface(request.args.getInt("interfaceNumber"))
            JSONObject()
        }
        Protocol.Op.USB_RELEASE_INTERFACE -> usbSession(origin, request.args).let {
            it.releaseInterface(request.args.getInt("interfaceNumber"))
            JSONObject()
        }
        Protocol.Op.USB_SELECT_ALTERNATE_INTERFACE -> usbSession(origin, request.args).let {
            it.selectAlternateInterface(
                request.args.getInt("interfaceNumber"),
                request.args.getInt("alternateSetting"),
            )
            JSONObject()
        }
        Protocol.Op.USB_CONTROL_TRANSFER_IN -> usbControlTransferIn(origin, request.args)
        Protocol.Op.USB_CONTROL_TRANSFER_OUT -> usbControlTransferOut(origin, request.args)
        Protocol.Op.USB_TRANSFER_IN -> usbSession(origin, request.args).let { session ->
            val result = session.transferIn(
                request.args.getInt("endpointNumber"),
                request.args.optInt("length", 0),
            )
            JSONObject().put("status", result.status).put("data", Base64Codec.encode(result.data))
        }
        Protocol.Op.USB_TRANSFER_OUT -> usbSession(origin, request.args).let { session ->
            val result = session.transferOut(
                request.args.getInt("endpointNumber"),
                Base64Codec.decode(request.args.optString("data", "")),
            )
            JSONObject().put("status", result.status).put("bytesWritten", result.bytesWritten)
        }
        Protocol.Op.USB_CLEAR_HALT -> usbSession(origin, request.args).let {
            it.clearHalt(request.args.optString("direction", "in"), request.args.getInt("endpointNumber"))
            JSONObject()
        }
        Protocol.Op.USB_RESET -> usbSession(origin, request.args).let {
            it.reset()
            JSONObject()
        }
        Protocol.Op.USB_FORGET -> forget(origin, request.args, serial = false)

        Protocol.Op.FILE_PICK_SAVE -> files.beginSave(
            request.args.optString("suggestedName", "download"),
            request.args.optString("mimeType", "application/octet-stream"),
        )?.let { JSONObject().put("token", it.token).put("name", it.name) }

        Protocol.Op.FILE_WRITE -> {
            files.write(request.args.getString("token"), Base64Codec.decode(request.args.optString("data", "")))
            JSONObject()
        }

        Protocol.Op.FILE_END_SAVE -> {
            files.endSave(request.args.getString("token"))
            JSONObject()
        }

        Protocol.Op.FILE_PICK_OPEN -> {
            val types = request.args.optJSONArray("mimeTypes")
            val mimeTypes = (0 until (types?.length() ?: 0)).mapNotNull { types?.optString(it) }
                .filter { it.isNotBlank() }
                .toTypedArray()
            files.beginOpen(mimeTypes)?.let {
                JSONObject().put("token", it.token).put("name", it.name).put("size", it.size)
            }
        }

        Protocol.Op.FILE_READ -> {
            val bytes = files.read(
                request.args.getString("token"),
                request.args.optLong("offset", 0L),
                request.args.optInt("length", 0),
            )
            JSONObject().put("data", Base64Codec.encode(bytes)).put("bytesRead", bytes.size)
        }

        Protocol.Op.FILE_END_OPEN -> {
            files.endOpen(request.args.getString("token"))
            JSONObject()
        }

        else -> throw BridgeException(BridgeErrorName.NOT_SUPPORTED, "Unknown operation ${request.op}")
    }

    // --------------------------------------------------------- serial ops

    private fun serialGetPorts(origin: String): JSONObject {
        val ports = JSONArray()
        authorizedDevices(origin)
            .filter { DeviceCapabilities.isSerial(it) }
            .forEach { device ->
                ports.put(DeviceDescriptors.serialPort(handleFor(origin, device, HandleRegistry.ROLE_SERIAL), device))
            }
        return JSONObject().put("ports", ports)
    }

    private suspend fun serialRequestPort(origin: String, args: JSONObject): JSONObject? {
        val filters = Protocol.parseFilters(args.optJSONArray("filters"))
        val candidates = hub.devices()
            .filter { DeviceCapabilities.isSerial(it) }
            .filter { filters.matchesOrEmpty(it.vendorId, it.productId) }

        if (candidates.isEmpty()) {
            throw BridgeException(BridgeErrorName.NOT_FOUND, "No matching serial device is attached")
        }

        val chosen = picker.choose(origin, "Select a serial device", candidates)
            ?: throw BridgeException(BridgeErrorName.NOT_FOUND, "No device selected")

        if (!hub.ensurePermission(chosen)) {
            throw BridgeException(BridgeErrorName.SECURITY, "USB permission was not granted")
        }

        hub.grants.grant(origin, hub.grantKeyOf(chosen))
        TransferLog.record(TransferKind.PERMISSION, origin, hub.describe(chosen), "Serial port authorised for origin")
        return DeviceDescriptors.serialPort(handleFor(origin, chosen, HandleRegistry.ROLE_SERIAL), chosen)
    }

    private suspend fun serialOpen(origin: String, args: JSONObject): JSONObject {
        val handle = args.getString("handle")
        val key = sessionKey(origin, handle)
        if (serialSessions.containsKey(key)) {
            throw BridgeException(BridgeErrorName.INVALID_STATE, "The port is already open")
        }

        val device = deviceForHandle(origin, handle)
        if (!hub.ensurePermission(device)) {
            throw BridgeException(BridgeErrorName.SECURITY, "USB permission was not granted")
        }
        val connection = hub.openConnection(device)
            ?: throw BridgeException(BridgeErrorName.NETWORK, "Could not open ${hub.describe(device)}")

        val session = SerialSession(handle, origin, device, connection)
        try {
            session.open(
                baudRate = args.optInt("baudRate", 115200),
                dataBits = args.optInt("dataBits", 8),
                stopBits = args.optInt("stopBits", 1),
                parity = args.optString("parity", "none"),
            ) { chunk ->
                postEvent(
                    origin,
                    Protocol.event(
                        Protocol.Event.SERIAL_DATA,
                        mapOf("handle" to handle, "data" to Base64Codec.encode(chunk)),
                    ),
                )
            }
        } catch (e: Exception) {
            runCatching { session.close() }
            throw BridgeException(BridgeErrorName.NETWORK, e.message ?: "Could not open the serial port")
        }

        serialSessions[key] = session
        return JSONObject().put("driver", session.driverName)
    }

    private fun serialClose(origin: String, args: JSONObject): JSONObject {
        serialSessions.remove(sessionKey(origin, args.getString("handle")))?.close()
        return JSONObject()
    }

    private fun serialWrite(origin: String, args: JSONObject): JSONObject {
        val session = serialSession(origin, args)
        val written = session.write(Base64Codec.decode(args.optString("data", "")))
        return JSONObject().put("bytesWritten", written)
    }

    private fun serialSetSignals(origin: String, args: JSONObject): JSONObject {
        val session = serialSession(origin, args)
        session.setSignals(
            dataTerminalReady = if (args.has("dataTerminalReady")) args.getBoolean("dataTerminalReady") else null,
            requestToSend = if (args.has("requestToSend")) args.getBoolean("requestToSend") else null,
            breakSignal = if (args.has("brk")) args.getBoolean("brk") else null,
        )
        return JSONObject()
    }

    private fun serialGetSignals(origin: String, args: JSONObject): JSONObject {
        val signals = serialSession(origin, args).getSignals()
        return JSONObject()
            .put("dataCarrierDetect", signals.dataCarrierDetect)
            .put("clearToSend", signals.clearToSend)
            .put("ringIndicator", signals.ringIndicator)
            .put("dataSetReady", signals.dataSetReady)
    }

    // ------------------------------------------------------------ usb ops

    private fun usbGetDevices(origin: String): JSONObject {
        val devices = JSONArray()
        authorizedDevices(origin).forEach { device ->
            devices.put(
                DeviceDescriptors.usbDevice(handleFor(origin, device, HandleRegistry.ROLE_USB), device, hub.serialNumberOf(device)),
            )
        }
        return JSONObject().put("devices", devices)
    }

    private suspend fun usbRequestDevice(origin: String, args: JSONObject): JSONObject? {
        val filters = Protocol.parseFilters(args.optJSONArray("filters"))
        val candidates = hub.devices().filter { filters.matchesOrEmpty(it.vendorId, it.productId) }
        if (candidates.isEmpty()) {
            throw BridgeException(BridgeErrorName.NOT_FOUND, "No matching USB device is attached")
        }

        val chosen = picker.choose(origin, "Select a USB device", candidates)
            ?: throw BridgeException(BridgeErrorName.NOT_FOUND, "No device selected")

        if (!hub.ensurePermission(chosen)) {
            throw BridgeException(BridgeErrorName.SECURITY, "USB permission was not granted")
        }

        hub.grants.grant(origin, hub.grantKeyOf(chosen))
        TransferLog.record(TransferKind.PERMISSION, origin, hub.describe(chosen), "USB device authorised for origin")
        return DeviceDescriptors.usbDevice(handleFor(origin, chosen, HandleRegistry.ROLE_USB), chosen, hub.serialNumberOf(chosen))
    }

    private suspend fun usbOpen(origin: String, args: JSONObject): JSONObject {
        val handle = args.getString("handle")
        val key = sessionKey(origin, handle)
        usbSessions[key]?.let { return JSONObject() }

        val device = deviceForHandle(origin, handle)
        if (!hub.ensurePermission(device)) {
            throw BridgeException(BridgeErrorName.SECURITY, "USB permission was not granted")
        }
        val connection = hub.openConnection(device)
            ?: throw BridgeException(BridgeErrorName.NETWORK, "Could not open ${hub.describe(device)}")

        usbSessions[key] = UsbSession(handle, origin, device, hub, connection)
        return JSONObject()
    }

    private fun usbClose(origin: String, args: JSONObject): JSONObject {
        usbSessions.remove(sessionKey(origin, args.getString("handle")))?.close()
        return JSONObject()
    }

    private fun usbControlTransferIn(origin: String, args: JSONObject): JSONObject {
        val result = usbSession(origin, args).controlTransferIn(
            setupFrom(args.getJSONObject("setup")),
            args.optInt("length", 0),
        )
        return JSONObject()
            .put("status", result.status)
            .put("data", Base64Codec.encode(result.data))
    }

    private fun usbControlTransferOut(origin: String, args: JSONObject): JSONObject {
        val result = usbSession(origin, args).controlTransferOut(
            setupFrom(args.getJSONObject("setup")),
            Base64Codec.decode(args.optString("data", "")),
        )
        return JSONObject()
            .put("status", result.status)
            .put("bytesWritten", result.bytesWritten)
    }

    private fun setupFrom(json: JSONObject) = ControlSetup(
        requestType = json.optString("requestType", "standard"),
        recipient = json.optString("recipient", "device"),
        request = json.optInt("request", 0),
        value = json.optInt("value", 0),
        index = json.optInt("index", 0),
    )

    // -------------------------------------------------------------- shared

    private fun forget(origin: String, args: JSONObject, serial: Boolean): JSONObject {
        val handle = args.getString("handle")
        val key = sessionKey(origin, handle)
        if (serial) serialSessions.remove(key)?.close() else usbSessions.remove(key)?.close()

        handles.deviceNameFor(origin, handle)?.let { deviceName ->
            hub.deviceByName(deviceName)?.let { hub.grants.revoke(origin, hub.grantKeyOf(it)) }
        }
        handles.removeHandle(origin, handle)
        return JSONObject()
    }

    /**
     * Devices this origin was previously granted.
     *
     * Deliberately *not* filtered on Android USB permission. `getPorts()` and
     * `getDevices()` are the "previously authorised" enumerations that populate
     * Betaflight's port list, and Android permission does not survive a reboot the
     * way the grant does — filtering here would make a remembered board vanish
     * from the list with no way back except another `requestPort()`, which is the
     * prompt the persisted grant exists to avoid. Permission is enforced at open
     * time instead, where a prompt is expected.
     */
    private fun authorizedDevices(origin: String): List<UsbDevice> =
        hub.devices().filter { hub.grants.isGranted(origin, hub.grantKeyOf(it)) }

    private fun handleFor(origin: String, device: UsbDevice, role: String): String =
        handles.handleFor(origin, role, device.deviceName)

    private fun deviceForHandle(origin: String, handle: String): UsbDevice {
        val deviceName = handles.deviceNameFor(origin, handle)
            ?: throw BridgeException(BridgeErrorName.NOT_FOUND, "Unknown device handle")
        val device = hub.deviceByName(deviceName)
            ?: throw BridgeException(BridgeErrorName.NOT_FOUND, "The device is no longer attached")
        if (!hub.grants.isGranted(origin, hub.grantKeyOf(device))) {
            throw BridgeException(BridgeErrorName.SECURITY, "This origin is not authorised for that device")
        }
        return device
    }

    private fun serialSession(origin: String, args: JSONObject): SerialSession =
        serialSessions[sessionKey(origin, args.getString("handle"))]
            ?: throw BridgeException(BridgeErrorName.INVALID_STATE, "The port is not open")

    private fun usbSession(origin: String, args: JSONObject): UsbSession =
        usbSessions[sessionKey(origin, args.getString("handle"))]
            ?: throw BridgeException(BridgeErrorName.INVALID_STATE, "The device is not open")

    private fun sessionKey(origin: String, handle: String) = "$origin|$handle"

    // -------------------------------------------------------------- events

    /** Announces an attach to every origin already authorised for the device. */
    fun notifyDeviceAttached(device: UsbDevice) {
        forEachAuthorisedOrigin(device) { origin ->
            if (DeviceCapabilities.isSerial(device)) {
                postEvent(
                    origin,
                    Protocol.event(
                        Protocol.Event.SERIAL_CONNECT,
                        mapOf(
                            "port" to DeviceDescriptors.serialPort(
                                handleFor(origin, device, HandleRegistry.ROLE_SERIAL),
                                device,
                            ),
                        ),
                    ),
                )
            }
            postEvent(
                origin,
                Protocol.event(
                    Protocol.Event.USB_CONNECT,
                    mapOf(
                        "device" to DeviceDescriptors.usbDevice(
                            handleFor(origin, device, HandleRegistry.ROLE_USB),
                            device,
                            hub.serialNumberOf(device),
                        ),
                    ),
                ),
            )
        }
    }

    fun notifyDeviceDetached(device: UsbDevice) {
        // Grants are keyed by VID/PID/serial and the serial number cannot be read
        // once the device is gone, so detach is announced to every origin holding
        // a handle for it. A board that was handed out under both roles gets both
        // events; the polyfill ignores a handle it never saw.
        handles.origins().forEach { origin ->
            val removed = handles.removeDevice(origin, device.deviceName)

            removed[HandleRegistry.ROLE_SERIAL]?.let { handle ->
                serialSessions.remove(sessionKey(origin, handle))?.close()
                postEvent(
                    origin,
                    Protocol.event(
                        Protocol.Event.SERIAL_DISCONNECT,
                        mapOf("port" to JSONObject().put("handle", handle)),
                    ),
                )
            }
            removed[HandleRegistry.ROLE_USB]?.let { handle ->
                usbSessions.remove(sessionKey(origin, handle))?.close()
                postEvent(
                    origin,
                    Protocol.event(
                        Protocol.Event.USB_DISCONNECT,
                        mapOf("device" to JSONObject().put("handle", handle)),
                    ),
                )
            }
        }
    }

    private inline fun forEachAuthorisedOrigin(device: UsbDevice, action: (String) -> Unit) {
        val key = hub.grantKeyOf(device)
        // Only origins that still hold USB access hear about devices; revoking a
        // site silences its events immediately.
        policy.usbOrigins().filter { hub.grants.isGranted(it, key) }.forEach(action)
    }

    private fun postEvent(origin: String, payload: String) {
        val proxies = replyProxies[origin] ?: return
        scope.launch(Dispatchers.Main) {
            synchronized(proxies) { proxies.toList() }.forEach { proxy ->
                // A proxy belonging to a page that has since navigated away throws;
                // dropping it keeps the set from growing across reloads.
                if (runCatching { proxy.postMessage(payload) }.isFailure) {
                    proxies.remove(proxy)
                }
            }
        }
    }

    fun closeAll() {
        serialSessions.values.forEach { runCatching { it.close() } }
        serialSessions.clear()
        usbSessions.values.forEach { runCatching { it.close() } }
        usbSessions.clear()
    }

    fun openSessionSummary(): List<String> =
        serialSessions.map { (key, session) -> "$key → serial (${session.driverName})" } +
            usbSessions.map { (key, session) -> "$key → usb (interfaces ${session.claimedInterfaceNumbers()})" }
}

class BridgeException(val errorName: BridgeErrorName, message: String) : Exception(message)
