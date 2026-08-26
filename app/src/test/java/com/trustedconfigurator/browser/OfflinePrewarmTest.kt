package com.trustedconfigurator.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that decide whether the offline preload runs at all.
 *
 * The visiting itself needs a WebView, so only the policy is covered here — but
 * the policy is the part that can quietly cost someone mobile data, so these
 * call the shipped decision rather than a copy of it.
 */
class OfflinePrewarmTest {

    private val sites = listOf("https://app.betaflight.com", "https://esc-configurator.com")

    private fun skipReason(
        enabled: Boolean = true,
        origins: List<String> = sites,
        metered: Boolean = false,
        lastRun: Long = 0L,
        now: Long = WEEK * 4,
    ): String? = OfflinePrewarm.skipReason(enabled, origins, metered, lastRun, now)

    @Test
    fun `it runs on an unmetered connection that has not been warmed`() {
        assertNull(skipReason())
    }

    @Test
    fun `it never spends mobile data`() {
        // The whole point is preparing for a field with no signal; doing it over
        // a metered connection would charge someone for the privilege.
        assertEquals("the connection is metered", skipReason(metered = true))
    }

    @Test
    fun `switching it off means it never runs`() {
        assertEquals("switched off", skipReason(enabled = false))
    }

    @Test
    fun `a site cached this week is not fetched again`() {
        val now = WEEK * 4
        assertEquals("done recently", skipReason(lastRun = now - WEEK / 2, now = now))
        assertNull(skipReason(lastRun = now - WEEK * 2, now = now))
    }

    @Test
    fun `nothing to warm means nothing is fetched`() {
        assertEquals("no sites", skipReason(origins = emptyList()))
    }

    private companion object {
        const val WEEK = 7L * 24 * 60 * 60 * 1000
    }
}
