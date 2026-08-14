package com.trustedconfigurator.browser.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.trustedconfigurator.browser.bridge.GrantStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The single owner of Android USB state: the device list, permission grants, and
 * attach/detach notifications.
 *
 * Everything above this layer works in terms of already-permitted devices, so
 * there is one place where the "does this origin get to touch this hardware"
 * question is answered.
 */
class UsbHub(
    context: Context,
    val grants: GrantStore,
    val dfuTransition: DfuTransition = DfuTransition(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val pendingPermissions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Called on the main thread when a device appears or disappears. */
    var onDeviceAttached: ((UsbDevice) -> Unit)? = null
    var onDeviceDetached: ((UsbDevice) -> Unit)? = null

    /** Origins that inherited a grant because a board re-enumerated into DFU. */
    var onDfuHandoff: ((UsbDevice, List<Handoff>) -> Unit)? = null

    private val lastKnownIdentities = ConcurrentHashMap<String, DeviceIdentity>()

    /**
     * Resolved serial numbers, keyed by the Android device path.
     *
     * Reading a serial number is two binder round-trips to the USB service, and
     * it is on the enumeration path: every getPorts()/getDevices() resolves one
     * per device, and Betaflight polls getDevices() twice a second while waiting
     * for a board to come back in DFU mode. A serial cannot change for a given
     * device instance, so the first successful read is the last one.
     */
    private val serialNumbers = ConcurrentHashMap<String, String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = deviceFrom(intent) ?: return
            when (action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    TransferLog.record(
                        TransferKind.PERMISSION,
                        origin = "-",
                        device = describe(device),
                        detail = if (granted) "Android USB permission granted" else "Android USB permission denied",
                    )
                    pendingPermissions.remove(device.deviceName)?.complete(granted)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetached(device)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        devices().forEach { lastKnownIdentities[it.deviceName] = identityOf(it) }
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    // --------------------------------------------------------------- devices

    fun devices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun deviceByName(deviceName: String?): UsbDevice? =
        deviceName?.let { usbManager.deviceList[it] }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun openConnection(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)

    /**
     * Reading the serial number needs USB permission from API 29 on, and throws
     * rather than returning null when it is missing.
     */
    fun serialNumberOf(device: UsbDevice): String? {
        serialNumbers[device.deviceName]?.let { return it }
        val resolved = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || usbManager.hasPermission(device)) {
                device.serialNumber
            } else {
                null
            }
        } catch (e: SecurityException) {
            null
        }
        // Only successes are cached: before permission this returns null, and
        // that answer stops being true the moment the user grants access.
        if (resolved != null) {
            serialNumbers[device.deviceName] = resolved
        }
        return resolved
    }

    fun identityOf(device: UsbDevice): DeviceIdentity =
        DeviceIdentity(device.vendorId, device.productId, serialNumberOf(device))

    fun grantKeyOf(device: UsbDevice): String =
        GrantStore.keyFor(device.vendorId, device.productId, serialNumberOf(device))

    fun describe(device: UsbDevice): String {
        val name = device.productName ?: "USB device"
        return "%s (%04X:%04X)".format(name, device.vendorId, device.productId)
    }

    // ----------------------------------------------------------- permissions

    /**
     * @return true once the user has allowed this app to talk to the device.
     * Resolves immediately when permission is already held, which is the normal
     * case for a device covered by the manifest's device_filter.
     */
    suspend fun ensurePermission(device: UsbDevice, timeoutMillis: Long = PERMISSION_TIMEOUT_MS): Boolean {
        if (usbManager.hasPermission(device)) return true

        val deferred = pendingPermissions.getOrPut(device.deviceName) { CompletableDeferred() }
        if (!deferred.isCompleted && pendingPermissions[device.deviceName] === deferred) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The system writes EXTRA_DEVICE into the intent, so it must be mutable.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val pendingIntent = PendingIntent.getBroadcast(appContext, device.deviceId, intent, flags)
            TransferLog.record(
                TransferKind.PERMISSION,
                origin = "-",
                device = describe(device),
                detail = "Requesting Android USB permission",
            )
            usbManager.requestPermission(device, pendingIntent)
        }

        val granted = withTimeoutOrNull(timeoutMillis) { deferred.await() } ?: false
        pendingPermissions.remove(device.deviceName)
        return granted
    }

    // ------------------------------------------------------ attach / detach

    private fun handleAttached(device: UsbDevice) {
        val identity = identityOf(device)
        lastKnownIdentities[device.deviceName] = identity

        val handoffs = dfuTransition.onDeviceAttached(identity, System.currentTimeMillis())
        if (handoffs.isEmpty()) {
            TransferLog.record(
                TransferKind.EVENT,
                origin = "-",
                device = describe(device),
                detail = if (dfuTransition.isBootloader(device.vendorId, device.productId)) {
                    "Bootloader device attached"
                } else {
                    "Device attached"
                },
            )
            onDeviceAttached?.invoke(device)
            return
        }

        handoffs.forEach { handoff ->
            grants.grant(handoff.origin, grantKeyOf(device))
            TransferLog.record(
                TransferKind.EVENT,
                origin = handoff.origin,
                device = describe(device),
                detail = "DFU re-enumeration detected (${handoff.reason}); grant carried over from " +
                    "%04X:%04X".format(handoff.previous.vendorId, handoff.previous.productId),
            )
        }
        onDfuHandoff?.invoke(device, handoffs)

        /*
         * Betaflight's waitForDfuDevice() polls navigator.usb.getDevices(), which
         * only reports devices this app already has Android permission for. A
         * bootloader is a brand new UsbDevice instance, so without asking here the
         * poll would time out while an unanswered dialog sat behind the page.
         * Usually instant, because the manifest device_filter lets the user make
         * this app the default handler for these boards.
         */
        scope.launch {
            val granted = ensurePermission(device)
            if (granted) {
                // Re-grant now that the serial number is readable, so the key
                // matches the one getDevices() will compute.
                handoffs.forEach { grants.grant(it.origin, grantKeyOf(device)) }
            }
            onDeviceAttached?.invoke(device)
        }
    }

    private fun handleDetached(device: UsbDevice) {
        // The serial number is unreadable once the device is gone, so the
        // identity captured while it was present is what gets matched against.
        val identity = lastKnownIdentities.remove(device.deviceName)
            ?: DeviceIdentity(device.vendorId, device.productId, null)
        serialNumbers.remove(device.deviceName)
        val key = GrantStore.keyFor(identity.vendorId, identity.productId, identity.serialNumber)

        val now = System.currentTimeMillis()
        grants.snapshot().forEach { (origin, keys) ->
            if (keys.contains(key)) {
                dfuTransition.onGrantedDeviceDetached(origin, identity, now)
            }
        }

        TransferLog.record(
            TransferKind.EVENT,
            origin = "-",
            device = describe(device),
            detail = "Device detached",
        )
        onDeviceDetached?.invoke(device)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.trustedconfigurator.browser.USB_PERMISSION"
        const val PERMISSION_TIMEOUT_MS = 60_000L

        @Suppress("DEPRECATION")
        fun deviceFrom(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }
}
