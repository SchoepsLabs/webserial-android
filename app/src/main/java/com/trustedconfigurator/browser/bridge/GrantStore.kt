package com.trustedconfigurator.browser.bridge

/** Storage seam so the grant logic is testable without SharedPreferences. */
interface GrantPersistence {
    fun load(): Map<String, Set<String>>
    fun save(grants: Map<String, Set<String>>)
}

/** A grant store that keeps everything in memory. Used by tests. */
class InMemoryGrantPersistence(initial: Map<String, Set<String>> = emptyMap()) : GrantPersistence {
    private var state: Map<String, Set<String>> = initial.mapValues { it.value.toSet() }
    override fun load(): Map<String, Set<String>> = state
    override fun save(grants: Map<String, Set<String>>) {
        state = grants.mapValues { it.value.toSet() }
    }
}

/**
 * Which origin may see which device.
 *
 * Web Serial's `getPorts()` and WebUSB's `getDevices()` are specified to return
 * only devices the *calling origin* was previously granted, so grants are keyed
 * by origin. Without this, app.betaflight.com and esc-configurator.com would
 * enumerate each other's hardware even though both are allow-listed.
 *
 * A grant key intentionally omits the Android device path, which changes on
 * every re-enumeration; it identifies the physical device by VID/PID and serial
 * number so that a grant survives the DFU mode switch.
 */
class GrantStore(private val persistence: GrantPersistence) {

    private val grants: MutableMap<String, MutableSet<String>> =
        persistence.load().mapValues { it.value.toMutableSet() }.toMutableMap()

    companion object {
        const val UNKNOWN_SERIAL = "-"

        fun keyFor(vendorId: Int, productId: Int, serialNumber: String?): String {
            val serial = serialNumber?.takeIf { it.isNotBlank() } ?: UNKNOWN_SERIAL
            return "%04x:%04x:%s".format(vendorId, productId, serial)
        }

        /**
         * Android refuses to reveal a device's serial number until USB permission
         * has been granted, so the same board yields `vid:pid:-` before the
         * prompt and `vid:pid:<serial>` after it. Treating an unknown serial on
         * either side as a match on VID/PID is what keeps a grant from evaporating
         * the moment permission arrives — which is exactly when a board coming
         * back in DFU mode would otherwise vanish from `getDevices()` mid-flash.
         */
        fun keysMatch(granted: String, query: String): Boolean {
            if (granted == query) return true
            val g = granted.split(':')
            val q = query.split(':')
            if (g.size < 3 || q.size < 3) return false
            if (g[0] != q[0] || g[1] != q[1]) return false
            return g[2] == UNKNOWN_SERIAL || q[2] == UNKNOWN_SERIAL
        }

        private fun serialOf(key: String): String = key.split(':').getOrElse(2) { UNKNOWN_SERIAL }
    }

    @Synchronized
    fun grant(origin: String, key: String) {
        val keys = grants.getOrPut(origin) { mutableSetOf() }
        if (serialOf(key) != UNKNOWN_SERIAL) {
            // A key with a real serial supersedes the placeholder recorded for the
            // same board before permission was granted, so they do not accumulate.
            keys.removeAll { serialOf(it) == UNKNOWN_SERIAL && keysMatch(it, key) }
        } else if (keys.any { keysMatch(it, key) }) {
            return
        }
        keys.add(key)
        persistence.save(grants)
    }

    @Synchronized
    fun revoke(origin: String, key: String) {
        grants[origin]?.removeAll { keysMatch(it, key) }
        persistence.save(grants)
    }

    @Synchronized
    fun isGranted(origin: String, key: String): Boolean =
        grants[origin]?.any { keysMatch(it, key) } == true

    @Synchronized
    fun grantsFor(origin: String): Set<String> = grants[origin]?.toSet() ?: emptySet()

    @Synchronized
    fun snapshot(): Map<String, Set<String>> = grants.mapValues { it.value.toSet() }

    @Synchronized
    fun revokeAll() {
        grants.clear()
        persistence.save(grants)
    }
}
