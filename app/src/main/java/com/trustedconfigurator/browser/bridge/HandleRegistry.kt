package com.trustedconfigurator.browser.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Stable per-origin handles for USB devices.
 *
 * Handles are what the polyfill uses to key its `SerialPort` / `USBDevice`
 * objects, so the same device must always come back as the same handle —
 * Betaflight matches removed devices by object identity, and a fresh object on
 * every enumeration would leave its port list stale.
 *
 * Handles are keyed by **role as well as device**, because one CDC flight
 * controller is legitimately both a Web Serial port and a WebUSB device and each
 * needs its own handle. Keying by device alone silently collapses the two, which
 * makes a detach announce only one of them.
 *
 * They are also scoped per origin, so a handle leaked from one site means nothing
 * at another.
 */
class HandleRegistry {

    companion object {
        const val ROLE_SERIAL = "serial"
        const val ROLE_USB = "usb"
    }

    private val counter = AtomicLong(0)

    /** origin -> ("<role>:<android device name>" -> handle) */
    private val byOrigin = ConcurrentHashMap<String, MutableMap<String, String>>()

    private fun key(role: String, deviceName: String) = "$role:$deviceName"

    fun handleFor(origin: String, role: String, deviceName: String): String =
        byOrigin.getOrPut(origin) { ConcurrentHashMap() }
            .getOrPut(key(role, deviceName)) { "${role}_${counter.incrementAndGet()}" }

    /** @return the Android device name behind a handle, or null when unknown to this origin. */
    fun deviceNameFor(origin: String, handle: String): String? =
        byOrigin[origin]?.entries?.firstOrNull { it.value == handle }?.key?.substringAfter(':')

    /** @return the handle previously issued for this role, without creating one. */
    fun existingHandle(origin: String, role: String, deviceName: String): String? =
        byOrigin[origin]?.get(key(role, deviceName))

    /** Drops every role's handle for a device. @return role -> handle for what was removed. */
    fun removeDevice(origin: String, deviceName: String): Map<String, String> {
        val handles = byOrigin[origin] ?: return emptyMap()
        val removed = LinkedHashMap<String, String>()
        listOf(ROLE_SERIAL, ROLE_USB).forEach { role ->
            handles.remove(key(role, deviceName))?.let { removed[role] = it }
        }
        return removed
    }

    fun removeHandle(origin: String, handle: String) {
        byOrigin[origin]?.entries?.firstOrNull { it.value == handle }?.let { entry ->
            byOrigin[origin]?.remove(entry.key)
        }
    }

    fun origins(): Set<String> = byOrigin.keys.toSet()
}
