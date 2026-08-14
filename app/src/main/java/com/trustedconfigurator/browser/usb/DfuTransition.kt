package com.trustedconfigurator.browser.usb

import com.trustedconfigurator.browser.bridge.DeviceFilter

/** Identity of a device as seen across a re-enumeration. */
data class DeviceIdentity(
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String?,
)

/** Why a re-attached device was linked back to an origin that had a grant. */
enum class HandoffReason {
    /** Same VID and serial number: unambiguous, this is the same physical board. */
    SAME_SERIAL,

    /** Same vendor, bootloader PID, within the window: an STM32 dropping to DFU. */
    SAME_VENDOR_BOOTLOADER,

    /** A bootloader device appeared right after the only granted device vanished. */
    SOLE_CANDIDATE_BOOTLOADER,
}

data class Handoff(
    val origin: String,
    val previous: DeviceIdentity,
    val reason: HandoffReason,
)

/**
 * Detects a flight controller dropping off the bus and coming back in DFU mode.
 *
 * Android hands out USB permission per `UsbDevice` instance, and a board that
 * re-enumerates as a bootloader is a brand new instance with no grant. Left
 * alone that produces a system permission dialog in the middle of a flash, and
 * `navigator.usb.getDevices()` — which is exactly how Betaflight's
 * `waitForDfuDevice()` polls for the bootloader — returns nothing until the
 * user answers it.
 *
 * So a detach of a granted device opens a short window during which a matching
 * bootloader attach is treated as the same board, and the grant is carried over
 * to the origin that already had it. The window is deliberately short and the
 * decision is recorded for the diagnostic screen.
 *
 * This class is pure logic and holds no Android types.
 */
class DfuTransition(
    private val bootloaderFilters: List<DeviceFilter> = DEFAULT_BOOTLOADER_FILTERS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 30_000L

        /**
         * Betaflight's built-in DFU list (src/js/protocols/devices.js). It is only
         * used to recognise a bootloader; the app also refreshes this list from the
         * network, so it must never be treated as an access-control list.
         */
        val DEFAULT_BOOTLOADER_FILTERS: List<DeviceFilter> = listOf(
            DeviceFilter(0x0483, 0xDF11), // STM32 / many FCs
            DeviceFilter(0x28E9, 0x0189), // GD32 DFU bootloader
            DeviceFilter(0x2E3C, 0xDF11), // AT32F435 DFU bootloader
            DeviceFilter(0x314B, 0x0106), // APM32 DFU bootloader
            DeviceFilter(0x2E8A, 0x000F), // Raspberry Pi Pico bootloader
            DeviceFilter(0x3997, 0xDF11), // X32 DFU bootloader
        )
    }

    private data class Pending(
        val origin: String,
        val identity: DeviceIdentity,
        val detachedAtMillis: Long,
    )

    private val pending = ArrayList<Pending>()

    fun isBootloader(vendorId: Int, productId: Int): Boolean =
        bootloaderFilters.any { it.matches(vendorId, productId) }

    /** Records that a device an origin was granted has left the bus. */
    @Synchronized
    fun onGrantedDeviceDetached(origin: String, identity: DeviceIdentity, nowMillis: Long) {
        expire(nowMillis)
        pending.add(Pending(origin, identity, nowMillis))
    }

    /**
     * @return every origin that should inherit a grant for this newly attached
     * device, together with why. Empty when this is not a recognised handoff.
     */
    @Synchronized
    fun onDeviceAttached(identity: DeviceIdentity, nowMillis: Long): List<Handoff> {
        expire(nowMillis)
        if (!isBootloader(identity.vendorId, identity.productId)) {
            return emptyList()
        }

        val candidates = pending.filter { nowMillis - it.detachedAtMillis <= windowMillis }
        if (candidates.isEmpty()) return emptyList()

        val bySerial = candidates.filter {
            val serial = identity.serialNumber
            serial != null && serial.isNotBlank() && it.identity.serialNumber == serial
        }
        if (bySerial.isNotEmpty()) {
            return consume(bySerial) { Handoff(it.origin, it.identity, HandoffReason.SAME_SERIAL) }
        }

        val byVendor = candidates.filter { it.identity.vendorId == identity.vendorId }
        if (byVendor.isNotEmpty()) {
            return consume(byVendor) {
                Handoff(it.origin, it.identity, HandoffReason.SAME_VENDOR_BOOTLOADER)
            }
        }

        // A board can change vendor ID between application and bootloader mode
        // (an FTDI-bridged FC, for one), so a single outstanding candidate is
        // still accepted. More than one and it is genuinely ambiguous: fall back
        // to the normal permission prompt rather than guessing.
        if (candidates.size == 1) {
            return consume(candidates) {
                Handoff(it.origin, it.identity, HandoffReason.SOLE_CANDIDATE_BOOTLOADER)
            }
        }
        return emptyList()
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun clear() = pending.clear()

    private fun consume(matched: List<Pending>, build: (Pending) -> Handoff): List<Handoff> {
        pending.removeAll(matched.toSet())
        // One origin may hold several stale entries for the same board; collapse them.
        return matched.map(build).distinctBy { it.origin }
    }

    private fun expire(nowMillis: Long) {
        pending.removeAll { nowMillis - it.detachedAtMillis > windowMillis }
    }
}
