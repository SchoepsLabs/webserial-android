package com.trustedconfigurator.browser

import com.trustedconfigurator.browser.bridge.HandleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleRegistryTest {

    private val betaflight = "https://app.betaflight.com"
    private val esc = "https://esc-configurator.com"
    private val devicePath = "/dev/bus/usb/001/004"

    @Test
    fun `the same device and role always yields the same handle`() {
        val registry = HandleRegistry()
        // Betaflight keys a WeakMap on the port object, so a second enumeration
        // returning a new handle would give it a new object and a stale list.
        assertEquals(
            registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath),
            registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath),
        )
    }

    @Test
    fun `one CDC board gets a distinct handle per role`() {
        val registry = HandleRegistry()
        val serial = registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath)
        val usb = registry.handleFor(betaflight, HandleRegistry.ROLE_USB, devicePath)

        // Collapsing these means a detach can only announce one of the two.
        assertNotEquals(serial, usb)
        assertTrue(serial.startsWith("serial_"))
        assertTrue(usb.startsWith("usb_"))
    }

    @Test
    fun `handles are scoped per origin`() {
        val registry = HandleRegistry()
        val forBetaflight = registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath)

        assertNotEquals(forBetaflight, registry.handleFor(esc, HandleRegistry.ROLE_SERIAL, devicePath))
        // A handle leaked from one site resolves to nothing at the other.
        assertNull(registry.deviceNameFor(esc, forBetaflight))
    }

    @Test
    fun `a handle resolves back to its device path`() {
        val registry = HandleRegistry()
        val handle = registry.handleFor(betaflight, HandleRegistry.ROLE_USB, devicePath)
        assertEquals(devicePath, registry.deviceNameFor(betaflight, handle))
        assertNull(registry.deviceNameFor(betaflight, "usb_9999"))
    }

    @Test
    fun `removeDevice returns every role so a detach can announce both`() {
        val registry = HandleRegistry()
        val serial = registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath)
        val usb = registry.handleFor(betaflight, HandleRegistry.ROLE_USB, devicePath)

        val removed = registry.removeDevice(betaflight, devicePath)

        assertEquals(serial, removed[HandleRegistry.ROLE_SERIAL])
        assertEquals(usb, removed[HandleRegistry.ROLE_USB])
        assertNull(registry.deviceNameFor(betaflight, serial))
        assertNull(registry.deviceNameFor(betaflight, usb))
    }

    @Test
    fun `removeDevice reports only the roles that were actually issued`() {
        val registry = HandleRegistry()
        registry.handleFor(betaflight, HandleRegistry.ROLE_USB, devicePath)

        val removed = registry.removeDevice(betaflight, devicePath)

        assertEquals(setOf(HandleRegistry.ROLE_USB), removed.keys)
    }

    @Test
    fun `removeDevice on an unknown device is a no-op`() {
        val registry = HandleRegistry()
        assertTrue(registry.removeDevice(betaflight, devicePath).isEmpty())
    }

    @Test
    fun `removeHandle drops only the named handle`() {
        val registry = HandleRegistry()
        val serial = registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath)
        val usb = registry.handleFor(betaflight, HandleRegistry.ROLE_USB, devicePath)

        registry.removeHandle(betaflight, serial)

        assertNull(registry.deviceNameFor(betaflight, serial))
        assertEquals(devicePath, registry.deviceNameFor(betaflight, usb))
    }

    @Test
    fun `a device re-enumerating under a new path gets a new handle`() {
        val registry = HandleRegistry()
        val before = registry.handleFor(betaflight, HandleRegistry.ROLE_USB, devicePath)
        // A board switching into DFU mode comes back on a different bus path,
        // and Chrome likewise hands out a fresh USBDevice for it.
        val after = registry.handleFor(betaflight, HandleRegistry.ROLE_USB, "/dev/bus/usb/001/005")

        assertNotEquals(before, after)
    }

    @Test
    fun `origins lists every origin that holds a handle`() {
        val registry = HandleRegistry()
        registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath)
        registry.handleFor(esc, HandleRegistry.ROLE_SERIAL, devicePath)

        assertEquals(setOf(betaflight, esc), registry.origins())
    }

    @Test
    fun `existingHandle does not mint a handle`() {
        val registry = HandleRegistry()
        assertNull(registry.existingHandle(betaflight, HandleRegistry.ROLE_SERIAL, devicePath))

        val handle = registry.handleFor(betaflight, HandleRegistry.ROLE_SERIAL, devicePath)
        assertEquals(handle, registry.existingHandle(betaflight, HandleRegistry.ROLE_SERIAL, devicePath))
    }
}
