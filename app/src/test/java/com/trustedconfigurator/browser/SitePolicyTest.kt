package com.trustedconfigurator.browser

import com.trustedconfigurator.browser.bridge.InMemorySitePersistence
import com.trustedconfigurator.browser.bridge.Site
import com.trustedconfigurator.browser.bridge.SitePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePolicyTest {

    private val betaflight = "https://app.betaflight.com"
    private val custom = "https://my-configurator.example"

    @Test
    fun `the built-in configurators can use USB out of the box`() {
        val policy = SitePolicy(InMemorySitePersistence())
        listOf(
            betaflight,
            "https://esc-configurator.com",
            "https://am32.ca",
            "https://expresslrs.github.io",
        ).forEach { origin ->
            assertTrue("$origin should be able to use USB", policy.isUsbAllowed(origin))
        }
    }

    @Test
    fun `the blackbox log viewer is not shipped with USB access`() {
        // It lives inside the configurator now, and a log viewer never needs a
        // serial port; shipping it would widen the bridge's reach for nothing.
        val policy = SitePolicy(InMemorySitePersistence())
        assertFalse(policy.isUsbAllowed("https://blackbox.betaflight.com"))
        assertFalse(policy.isKnown("https://blackbox.betaflight.com"))
    }

    @Test
    fun `an unknown site cannot use USB`() {
        val policy = SitePolicy(InMemorySitePersistence())
        assertFalse(policy.isUsbAllowed("https://evil.test"))
        assertFalse(policy.isKnown("https://evil.test"))
    }

    @Test
    fun `a site added by the user starts without USB`() {
        val policy = SitePolicy(InMemorySitePersistence())
        val site = policy.addSite(custom)

        assertEquals(custom, site!!.origin)
        assertTrue(policy.isKnown(custom))
        // Adding is navigation only; USB stays off until an explicit opt-in.
        assertFalse(policy.isUsbAllowed(custom))
    }

    @Test
    fun `enabling USB for a user site takes effect and is reversible`() {
        val policy = SitePolicy(InMemorySitePersistence())
        policy.addSite(custom)

        policy.setUsbEnabled(custom, true)
        assertTrue(policy.isUsbAllowed(custom))
        assertTrue(policy.usbOrigins().contains(custom))

        policy.setUsbEnabled(custom, false)
        assertFalse(policy.isUsbAllowed(custom))
        assertFalse(policy.usbOrigins().contains(custom))
    }

    @Test
    fun `USB access survives a restart`() {
        val persistence = InMemorySitePersistence()
        SitePolicy(persistence).apply {
            addSite(custom)
            setUsbEnabled(custom, true)
        }

        assertTrue(SitePolicy(persistence).isUsbAllowed(custom))
    }

    @Test
    fun `revoking a built-in survives a restart`() {
        val persistence = InMemorySitePersistence()
        SitePolicy(persistence).setUsbEnabled(betaflight, false)

        val reloaded = SitePolicy(persistence)
        assertFalse(reloaded.isUsbAllowed(betaflight))
        // Still listed, just without access.
        assertTrue(reloaded.isKnown(betaflight))
    }

    @Test
    fun `an unchanged built-in is not written to storage`() {
        val persistence = InMemorySitePersistence()
        val policy = SitePolicy(persistence)
        policy.addSite(custom)

        // Only the user site is persisted, so changing the shipped built-in list
        // in a later version is not overridden by stale storage.
        assertEquals(listOf(custom), persistence.load().map { it.origin })
    }

    @Test
    fun `a built-in cannot be removed, only revoked`() {
        val policy = SitePolicy(InMemorySitePersistence())
        assertFalse(policy.removeSite(betaflight))
        assertTrue(policy.isKnown(betaflight))

        policy.addSite(custom)
        assertTrue(policy.removeSite(custom))
        assertFalse(policy.isKnown(custom))
    }

    @Test
    fun `addresses are normalised before they become policy`() {
        val policy = SitePolicy(InMemorySitePersistence())
        policy.addSite("https://My-Configurator.Example/some/path?x=1", usbEnabled = true)

        assertTrue(policy.isUsbAllowed("https://my-configurator.example/other"))
        assertEquals(1, policy.sites().count { !it.builtIn })
    }

    @Test
    fun `an address that is not a plain https origin is refused`() {
        val policy = SitePolicy(InMemorySitePersistence())
        assertNull(policy.addSite("http://insecure.test"))
        assertNull(policy.addSite("javascript:alert(1)"))
        assertNull(policy.addSite("https://real.test@evil.test"))
        assertEquals(0, policy.sites().count { !it.builtIn })
    }

    @Test
    fun `usbOrigins is exactly the set handed to the WebView`() {
        val policy = SitePolicy(InMemorySitePersistence())
        policy.setUsbEnabled(betaflight, false)
        policy.addSite(custom, usbEnabled = true)

        val origins = policy.usbOrigins()
        assertFalse(origins.contains(betaflight))
        assertTrue(origins.contains(custom))
        assertTrue(origins.contains("https://am32.ca"))
    }

    @Test
    fun `stored entries never resurrect a site as built-in`() {
        // A crafted or stale store must not be able to claim built-in status.
        val persistence = InMemorySitePersistence(
            listOf(Site("https://evil.test", "Totally Legit", builtIn = true, usbEnabled = true)),
        )
        val policy = SitePolicy(persistence)

        assertFalse(policy.sites().first { it.origin == "https://evil.test" }.builtIn)
    }

    @Test
    fun `adding a site that already exists never takes its USB access away`() {
        /*
         * Reported from a phone: Betaflight showed its own "this browser has no
         * Web Serial" screen, and the stored policy held one entry —
         * app.betaflight.com with usb false. Typing a built-in's address into
         * the add-a-site box called addSite() with the parameter's false
         * default, which overwrote the built-in's flag. Adding is not revoking.
         */
        val policy = SitePolicy(InMemorySitePersistence())
        assertTrue(policy.isUsbAllowed(betaflight))

        policy.addSite(betaflight)

        assertTrue("adding a built-in switched its USB off", policy.isUsbAllowed(betaflight))
    }

    @Test
    fun `adding a user site again keeps the USB access it was granted`() {
        val policy = SitePolicy(InMemorySitePersistence())
        policy.addSite(custom)
        policy.setUsbEnabled(custom, true)

        policy.addSite(custom)

        assertTrue("re-adding a site switched its USB off", policy.isUsbAllowed(custom))
    }

    @Test
    fun `the toggle is still the way to switch USB off`() {
        // The fix must not make access impossible to revoke.
        val policy = SitePolicy(InMemorySitePersistence())
        policy.setUsbEnabled(betaflight, false)
        assertFalse(policy.isUsbAllowed(betaflight))

        policy.setUsbEnabled(betaflight, true)
        assertTrue(policy.isUsbAllowed(betaflight))
    }
}
