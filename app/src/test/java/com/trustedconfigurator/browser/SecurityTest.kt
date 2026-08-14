package com.trustedconfigurator.browser

import com.trustedconfigurator.browser.bridge.GrantStore
import com.trustedconfigurator.browser.bridge.InMemoryGrantPersistence
import com.trustedconfigurator.browser.bridge.OriginPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginPolicyTest {

    @Test
    fun `normalize strips path, query and fragment but keeps the authority`() {
        assertEquals(
            "https://app.betaflight.com",
            OriginPolicy.normalize("https://app.betaflight.com/tabs/setup?x=1#y"),
        )
        assertEquals("https://esc-configurator.com", OriginPolicy.normalize("https://ESC-Configurator.com/"))
    }

    @Test
    fun `normalize rejects a downgraded or non-https scheme`() {
        assertNull(OriginPolicy.normalize("http://app.betaflight.com"))
        assertNull(OriginPolicy.normalize("file:///android_asset/index.html"))
        assertNull(OriginPolicy.normalize("javascript:alert(1)"))
        assertNull(OriginPolicy.normalize("data:text/html,<script>"))
    }

    @Test
    fun `normalize rejects credentials smuggled into the authority`() {
        // https://app.betaflight.com@evil.test/ actually loads evil.test.
        assertNull(OriginPolicy.normalize("https://app.betaflight.com@evil.test/"))
        assertNull(OriginPolicy.normalize("https://user:pass@app.betaflight.com/"))
    }

    @Test
    fun `normalize rejects input that cannot be trusted`() {
        assertNull(OriginPolicy.normalize(null))
        assertNull(OriginPolicy.normalize(""))
        assertNull(OriginPolicy.normalize("app.betaflight.com"))
        assertNull(OriginPolicy.normalize("https://"))
    }

    @Test
    fun `look-alike hosts normalize to themselves, never to the real origin`() {
        assertEquals("https://app.betaflight.com.evil.test", OriginPolicy.normalize("https://app.betaflight.com.evil.test"))
        assertEquals("https://notapp.betaflight.com", OriginPolicy.normalize("https://notapp.betaflight.com"))
    }

    @Test
    fun `toUrl accepts what a person would actually type`() {
        assertEquals("https://am32.ca", OriginPolicy.toUrl("am32.ca"))
        assertEquals("https://am32.ca", OriginPolicy.toUrl("  am32.ca  "))
        assertEquals("https://am32.ca/x", OriginPolicy.toUrl("https://am32.ca/x"))
        // http is upgraded rather than refused, so typing it still works.
        assertEquals("https://am32.ca", OriginPolicy.toUrl("http://am32.ca"))
    }

    @Test
    fun `toUrl refuses schemes this browser will not load`() {
        assertNull(OriginPolicy.toUrl("javascript:alert(1)"))
        assertNull(OriginPolicy.toUrl("file:///etc/passwd"))
        assertNull(OriginPolicy.toUrl("intent://evil"))
        assertNull(OriginPolicy.toUrl(""))
        assertNull(OriginPolicy.toUrl(null))
    }
}

class GrantStoreTest {

    private val betaflight = "https://app.betaflight.com"
    private val esc = "https://esc-configurator.com"

    @Test
    fun `a grant is scoped to the origin that asked for it`() {
        val store = GrantStore(InMemoryGrantPersistence())
        val key = GrantStore.keyFor(0x0483, 0x5740, "356E36713234")

        store.grant(betaflight, key)

        assertTrue(store.isGranted(betaflight, key))
        // Both sites are allow-listed, but each must only see its own hardware.
        assertFalse(store.isGranted(esc, key))
    }

    @Test
    fun `a key identifies the physical board, not the Android device path`() {
        // The path changes on every re-enumeration; VID/PID plus serial does not,
        // which is what lets a grant survive a switch into DFU mode.
        assertEquals(
            GrantStore.keyFor(0x0483, 0x5740, "356E36713234"),
            GrantStore.keyFor(0x0483, 0x5740, "356E36713234"),
        )
        assertFalse(
            GrantStore.keyFor(0x0483, 0x5740, "A") == GrantStore.keyFor(0x0483, 0x5740, "B"),
        )
        assertFalse(
            GrantStore.keyFor(0x0483, 0x5740, null) == GrantStore.keyFor(0x0483, 0xDF11, null),
        )
    }

    @Test
    fun `revoke removes only the named grant`() {
        val store = GrantStore(InMemoryGrantPersistence())
        val first = GrantStore.keyFor(0x0483, 0x5740, "A")
        val second = GrantStore.keyFor(0x1A86, 0x7523, null)
        store.grant(betaflight, first)
        store.grant(betaflight, second)

        store.revoke(betaflight, first)

        assertFalse(store.isGranted(betaflight, first))
        assertTrue(store.isGranted(betaflight, second))
    }

    @Test
    fun `grants survive a restart through persistence`() {
        val persistence = InMemoryGrantPersistence()
        val key = GrantStore.keyFor(0x0483, 0x5740, "A")
        GrantStore(persistence).grant(betaflight, key)

        assertTrue(GrantStore(persistence).isGranted(betaflight, key))
    }

    @Test
    fun `a grant made before permission still matches once the serial number appears`() {
        val store = GrantStore(InMemoryGrantPersistence())
        // Android hides the serial until USB permission is granted, so a grant
        // recorded at attach time carries the placeholder.
        store.grant(betaflight, GrantStore.keyFor(0x0483, 0xDF11, null))

        assertTrue(store.isGranted(betaflight, GrantStore.keyFor(0x0483, 0xDF11, "356E36713234")))
        assertFalse(store.isGranted(betaflight, GrantStore.keyFor(0x0483, 0x5740, "356E36713234")))
    }

    @Test
    fun `a serial-bearing grant still matches a lookup made without permission`() {
        val store = GrantStore(InMemoryGrantPersistence())
        store.grant(betaflight, GrantStore.keyFor(0x0483, 0xDF11, "356E36713234"))

        assertTrue(store.isGranted(betaflight, GrantStore.keyFor(0x0483, 0xDF11, null)))
    }

    @Test
    fun `a resolved serial supersedes the placeholder instead of accumulating`() {
        val store = GrantStore(InMemoryGrantPersistence())
        store.grant(betaflight, GrantStore.keyFor(0x0483, 0xDF11, null))
        store.grant(betaflight, GrantStore.keyFor(0x0483, 0xDF11, "356E36713234"))

        assertEquals(setOf(GrantStore.keyFor(0x0483, 0xDF11, "356E36713234")), store.grantsFor(betaflight))
    }

    @Test
    fun `two boards with different serials keep separate grants`() {
        val store = GrantStore(InMemoryGrantPersistence())
        store.grant(betaflight, GrantStore.keyFor(0x0483, 0x5740, "AAA"))
        store.grant(betaflight, GrantStore.keyFor(0x0483, 0x5740, "BBB"))

        assertEquals(2, store.grantsFor(betaflight).size)
        store.revoke(betaflight, GrantStore.keyFor(0x0483, 0x5740, "AAA"))
        assertFalse(store.isGranted(betaflight, GrantStore.keyFor(0x0483, 0x5740, "AAA")))
        assertTrue(store.isGranted(betaflight, GrantStore.keyFor(0x0483, 0x5740, "BBB")))
    }

    @Test
    fun `matching never crosses a VID or PID boundary`() {
        assertFalse(
            GrantStore.keysMatch(
                GrantStore.keyFor(0x0483, 0xDF11, null),
                GrantStore.keyFor(0x1A86, 0xDF11, null),
            ),
        )
        assertFalse(
            GrantStore.keysMatch(
                GrantStore.keyFor(0x0483, 0xDF11, null),
                GrantStore.keyFor(0x0483, 0x5740, null),
            ),
        )
        assertFalse(
            GrantStore.keysMatch(
                GrantStore.keyFor(0x0483, 0xDF11, "AAA"),
                GrantStore.keyFor(0x0483, 0xDF11, "BBB"),
            ),
        )
    }

    @Test
    fun `revokeAll clears every origin`() {
        val persistence = InMemoryGrantPersistence()
        val store = GrantStore(persistence)
        store.grant(betaflight, GrantStore.keyFor(1, 2, null))
        store.grant(esc, GrantStore.keyFor(3, 4, null))

        store.revokeAll()

        assertTrue(store.grantsFor(betaflight).isEmpty())
        assertTrue(store.grantsFor(esc).isEmpty())
        assertTrue(GrantStore(persistence).snapshot().values.all { it.isEmpty() })
    }
}
