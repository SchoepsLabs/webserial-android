package com.trustedconfigurator.browser

import com.trustedconfigurator.browser.bridge.DeviceFilter
import com.trustedconfigurator.browser.usb.ControlSetup
import com.trustedconfigurator.browser.usb.DeviceIdentity
import com.trustedconfigurator.browser.usb.DfuTransition
import com.trustedconfigurator.browser.usb.HandoffReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flight-controller-drops-into-DFU handoff.
 *
 * Android issues USB permission per device instance, so a board that
 * re-enumerates as a bootloader is a new instance with no grant — which is what
 * would otherwise put a permission dialog in the middle of a flash.
 */
class DfuTransitionTest {

    private val betaflight = "https://app.betaflight.com"
    private val esc = "https://esc-configurator.com"

    /** An STM32 flight controller in application mode and then in DFU mode. */
    private val stm32App = DeviceIdentity(0x0483, 0x5740, "356E36713234")
    private val stm32Dfu = DeviceIdentity(0x0483, 0xDF11, "356E36713234")

    @Test
    fun `recognises the built-in bootloader identifiers`() {
        val transition = DfuTransition()
        assertTrue(transition.isBootloader(0x0483, 0xDF11)) // STM32
        assertTrue(transition.isBootloader(0x28E9, 0x0189)) // GD32
        assertTrue(transition.isBootloader(0x2E3C, 0xDF11)) // AT32F435
        assertTrue(transition.isBootloader(0x314B, 0x0106)) // APM32
        assertTrue(transition.isBootloader(0x2E8A, 0x000F)) // RP2040
        assertTrue(transition.isBootloader(0x3997, 0xDF11)) // X32

        assertFalse(transition.isBootloader(0x0483, 0x5740)) // the same board in app mode
        assertFalse(transition.isBootloader(0x1A86, 0x7523)) // a CH340 adapter
    }

    @Test
    fun `carries the grant over when a board comes back with the same serial number`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, stm32App, nowMillis = 1_000)

        val handoffs = transition.onDeviceAttached(stm32Dfu, nowMillis = 2_500)

        assertEquals(1, handoffs.size)
        assertEquals(betaflight, handoffs[0].origin)
        assertEquals(HandoffReason.SAME_SERIAL, handoffs[0].reason)
        assertEquals(stm32App, handoffs[0].previous)
    }

    @Test
    fun `falls back to the vendor when the bootloader reports no serial number`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, stm32App, nowMillis = 0)

        val handoffs = transition.onDeviceAttached(
            DeviceIdentity(0x0483, 0xDF11, null),
            nowMillis = 1_000,
        )

        assertEquals(1, handoffs.size)
        assertEquals(HandoffReason.SAME_VENDOR_BOOTLOADER, handoffs[0].reason)
    }

    @Test
    fun `accepts a vendor change when exactly one board is outstanding`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, DeviceIdentity(0x1A86, 0x7523, null), nowMillis = 0)

        val handoffs = transition.onDeviceAttached(DeviceIdentity(0x2E8A, 0x000F, null), nowMillis = 500)

        assertEquals(1, handoffs.size)
        assertEquals(HandoffReason.SOLE_CANDIDATE_BOOTLOADER, handoffs[0].reason)
    }

    @Test
    fun `refuses to guess when two different boards are outstanding`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, DeviceIdentity(0x1A86, 0x7523, null), nowMillis = 0)
        transition.onGrantedDeviceDetached(esc, DeviceIdentity(0x10C4, 0xEA60, null), nowMillis = 0)

        // Neither vendor matches and it is genuinely ambiguous, so the user gets
        // the normal permission prompt instead of a wrong automatic grant.
        assertTrue(transition.onDeviceAttached(DeviceIdentity(0x2E8A, 0x000F, null), nowMillis = 500).isEmpty())
    }

    @Test
    fun `ignores a bootloader that appears long after the board went away`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, stm32App, nowMillis = 0)

        val handoffs = transition.onDeviceAttached(stm32Dfu, nowMillis = DfuTransition.DEFAULT_WINDOW_MILLIS + 1)

        assertTrue(handoffs.isEmpty())
        assertEquals(0, transition.pendingCount())
    }

    @Test
    fun `ignores a device that is not a known bootloader`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, stm32App, nowMillis = 0)

        // An unrelated USB stick appearing must not inherit the grant.
        assertTrue(transition.onDeviceAttached(DeviceIdentity(0x0781, 0x5567, "usbstick"), nowMillis = 100).isEmpty())
        assertEquals(1, transition.pendingCount())
    }

    @Test
    fun `a handoff is consumed so a second board does not inherit it`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, stm32App, nowMillis = 0)

        assertEquals(1, transition.onDeviceAttached(stm32Dfu, nowMillis = 100).size)
        assertTrue(transition.onDeviceAttached(stm32Dfu, nowMillis = 200).isEmpty())
    }

    @Test
    fun `each origin that had the board gets its own handoff`() {
        val transition = DfuTransition()
        transition.onGrantedDeviceDetached(betaflight, stm32App, nowMillis = 0)
        transition.onGrantedDeviceDetached(esc, stm32App, nowMillis = 0)

        val handoffs = transition.onDeviceAttached(stm32Dfu, nowMillis = 100)

        assertEquals(setOf(betaflight, esc), handoffs.map { it.origin }.toSet())
    }

    @Test
    fun `a custom bootloader list replaces the defaults`() {
        val transition = DfuTransition(bootloaderFilters = listOf(DeviceFilter(0xAAAA, 0xBBBB)))
        assertTrue(transition.isBootloader(0xAAAA, 0xBBBB))
        assertFalse(transition.isBootloader(0x0483, 0xDF11))
    }
}

class ControlSetupTest {

    @Test
    fun `builds the standard GET_DESCRIPTOR request type Betaflight uses`() {
        val setup = ControlSetup("standard", "device", request = 0x06, value = 0x0100, index = 0)
        assertEquals(0x80, setup.requestTypeByte(0x80))
        assertEquals(0x00, setup.requestTypeByte(0x00))
    }

    @Test
    fun `builds a DFU class request addressed to an interface`() {
        val setup = ControlSetup("class", "interface", request = 0x03, value = 0, index = 0)
        assertEquals(0xA1, setup.requestTypeByte(0x80)) // IN  | class | interface
        assertEquals(0x21, setup.requestTypeByte(0x00)) // OUT | class | interface
    }

    @Test
    fun `builds vendor and endpoint recipients`() {
        assertEquals(0x42, ControlSetup("vendor", "endpoint", 0, 0, 0).requestTypeByte(0x00))
        assertEquals(0xC3, ControlSetup("vendor", "other", 0, 0, 0).requestTypeByte(0x80))
    }

    @Test
    fun `treats an unknown spelling as standard device, never as vendor`() {
        assertEquals(0x00, ControlSetup("nonsense", "nonsense", 0, 0, 0).requestTypeByte(0x00))
    }

    @Test
    fun `is case insensitive, as the WebUSB enums are lower case by spec`() {
        assertEquals(0x21, ControlSetup("CLASS", "INTERFACE", 0, 0, 0).requestTypeByte(0x00))
    }
}
