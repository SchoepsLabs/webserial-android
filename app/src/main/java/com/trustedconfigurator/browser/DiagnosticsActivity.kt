package com.trustedconfigurator.browser

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.trustedconfigurator.browser.bridge.GrantStore
import com.trustedconfigurator.browser.bridge.SharedPreferencesGrantPersistence
import com.trustedconfigurator.browser.bridge.SharedPreferencesSitePersistence
import com.trustedconfigurator.browser.bridge.SitePolicy
import com.trustedconfigurator.browser.databinding.ActivityDiagnosticsBinding
import com.trustedconfigurator.browser.usb.DeviceCapabilities
import com.trustedconfigurator.browser.usb.DfuTransition
import com.trustedconfigurator.browser.usb.TransferLog
import com.trustedconfigurator.browser.usb.TransferRecord

/**
 * Native diagnostics: what is attached, how it is put together, who may touch
 * it, and every transfer that has crossed the bridge.
 *
 * Deliberately native rather than a page in the WebView, so it still works when
 * the bridge itself is the thing that is broken.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var grants: GrantStore
    private lateinit var policy: SitePolicy
    private val dfuTransition = DfuTransition()

    private val logListener: (TransferRecord) -> Unit = { runOnUiThread { renderLog() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        grants = GrantStore(SharedPreferencesGrantPersistence(this))
        policy = SitePolicy(SharedPreferencesSitePersistence(this))

        binding.buttonRefresh.setOnClickListener { render() }
        binding.buttonClearLog.setOnClickListener {
            TransferLog.clear()
            renderLog()
        }
        binding.buttonRevoke.setOnClickListener {
            grants.revokeAll()
            render()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        TransferLog.addListener(logListener)
    }

    override fun onStop() {
        TransferLog.removeListener(logListener)
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        renderDevices()
        renderGrants()
        renderLog()
    }

    private fun renderDevices() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values.toList()
        binding.textDevices.text = if (devices.isEmpty()) {
            getString(R.string.no_devices)
        } else {
            devices.joinToString("\n\n") { describe(it, usbManager) }
        }
    }

    private fun describe(device: UsbDevice, usbManager: UsbManager): String = buildString {
        appendLine("%s".format(device.productName ?: getString(R.string.unnamed_device)))
        appendLine("  path        ${device.deviceName}")
        appendLine("  VID:PID     %04X:%04X".format(device.vendorId, device.productId))
        appendLine("  vendor      ${device.manufacturerName ?: "-"}")
        appendLine("  class       ${device.deviceClass}/${device.deviceSubclass}/${device.deviceProtocol}")
        appendLine("  bcdDevice   ${device.version}")

        val permitted = usbManager.hasPermission(device)
        appendLine("  permission  ${if (permitted) "granted" else "not granted"}")
        // getSerialNumber throws without permission from API 29 on.
        appendLine("  serial      ${runCatching { if (permitted) device.serialNumber else null }.getOrNull() ?: "-"}")

        val roles = buildList {
            if (DeviceCapabilities.isSerial(device)) add("Web Serial")
            if (DeviceCapabilities.isDfu(device)) add("WebUSB DFU")
            if (dfuTransition.isBootloader(device.vendorId, device.productId)) add("known bootloader")
        }
        appendLine("  exposed as  ${if (roles.isEmpty()) "not exposed" else roles.joinToString(", ")}")

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            appendLine(
                "  interface %d alt %d  class %02X/%02X/%02X  %s".format(
                    iface.id,
                    iface.alternateSetting,
                    iface.interfaceClass,
                    iface.interfaceSubclass,
                    iface.interfaceProtocol,
                    iface.name ?: "",
                ),
            )
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                appendLine(
                    "    ep 0x%02X  %-3s %-11s max %d".format(
                        endpoint.address,
                        if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                        endpointType(endpoint.type),
                        endpoint.maxPacketSize,
                    ),
                )
            }
        }
    }

    private fun endpointType(type: Int) = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        else -> "control"
    }

    private fun renderGrants() {
        // Every site that may use USB, plus any origin still holding a device
        // grant from before its access was revoked.
        val origins = (policy.usbOrigins() + grants.snapshot().keys).distinct().sorted()
        binding.textGrants.text = origins.joinToString("\n\n") { origin ->
            val access = if (policy.isUsbAllowed(origin)) "USB allowed" else "USB revoked"
            val keys = grants.grantsFor(origin)
            if (keys.isEmpty()) {
                "$origin\n  $access, no devices authorised"
            } else {
                "$origin\n  $access\n" + keys.joinToString("\n") { "  $it" }
            }
        }
    }

    private fun renderLog() {
        val records = TransferLog.snapshot().asReversed()
        val lines = records.map { record ->
            val bytes = if (record.byteCount > 0) " ${record.byteCount}B" else ""
            "${record.formattedTime()}  ${record.kind}$bytes\n  ${record.device} · ${record.origin}\n  ${record.detail}"
        }
        binding.listLog.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        binding.textLogCount.text = getString(R.string.log_count, records.size)
    }
}
