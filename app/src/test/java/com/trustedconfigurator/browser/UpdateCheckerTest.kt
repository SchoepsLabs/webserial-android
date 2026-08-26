package com.trustedconfigurator.browser

import com.trustedconfigurator.browser.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `a higher version is newer`() {
        assertTrue(UpdateChecker.compareVersions("v1.3", "1.2") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0", "1.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.2.1", "1.2") > 0)
    }

    @Test
    fun `equal versions compare equal regardless of the v prefix or trailing zeros`() {
        assertEquals(0, UpdateChecker.compareVersions("v1.2", "1.2"))
        assertEquals(0, UpdateChecker.compareVersions("1.2.0", "1.2"))
        assertEquals(0, UpdateChecker.compareVersions("V1.2", "v1.2"))
    }

    @Test
    fun `an older version is not offered`() {
        assertTrue(UpdateChecker.compareVersions("1.1", "1.2") < 0)
        assertTrue(UpdateChecker.compareVersions("v1.9", "v1.10") < 0)
    }

    @Test
    fun `10 sorts above 9, not below it`() {
        // String comparison would put "1.10" before "1.9" and silently stop
        // offering updates after the ninth release.
        assertTrue(UpdateChecker.compareVersions("1.10", "1.9") > 0)
    }

    @Test
    fun `a pre-release suffix compares as its base version`() {
        assertEquals(0, UpdateChecker.compareVersions("1.2.0-rc1", "1.2"))
        assertTrue(UpdateChecker.compareVersions("1.3-beta", "1.2") > 0)
    }

    @Test
    fun `junk never masquerades as an upgrade`() {
        assertEquals(0, UpdateChecker.compareVersions(null, null))
        assertEquals(0, UpdateChecker.compareVersions("", ""))
        assertTrue(UpdateChecker.compareVersions("garbage", "1.2") < 0)
        assertTrue(UpdateChecker.compareVersions("", "1.2") < 0)
    }

    @Test
    fun `a newer release is parsed out of the GitHub payload`() {
        val json = """
            {"tag_name":"v1.5","name":"v1.5 — WebSerial Browser","body":"Notes here",
             "html_url":"https://github.com/SchoepsLabs/webserial-android/releases/tag/v1.5",
             "draft":false,"prerelease":false}
        """.trimIndent()

        val update = UpdateChecker.parseRelease(json, "1.2")

        assertNotNull(update)
        assertEquals("1.5", update!!.versionName)
        assertEquals("v1.5 — WebSerial Browser", update.title)
        assertTrue(update.pageUrl.endsWith("/tag/v1.5"))
    }

    @Test
    fun `the running version is not offered to itself`() {
        val json = """{"tag_name":"v1.2","html_url":"https://example.test","draft":false,"prerelease":false}"""
        assertNull(UpdateChecker.parseRelease(json, "1.2"))
    }

    @Test
    fun `drafts and pre-releases are ignored`() {
        val draft = """{"tag_name":"v9.0","html_url":"https://example.test","draft":true,"prerelease":false}"""
        val pre = """{"tag_name":"v9.0","html_url":"https://example.test","draft":false,"prerelease":true}"""
        assertNull(UpdateChecker.parseRelease(draft, "1.2"))
        assertNull(UpdateChecker.parseRelease(pre, "1.2"))
    }

    @Test
    fun `a malformed payload is ignored rather than crashing the launch`() {
        assertNull(UpdateChecker.parseRelease(null, "1.2"))
        assertNull(UpdateChecker.parseRelease("", "1.2"))
        assertNull(UpdateChecker.parseRelease("not json", "1.2"))
        assertNull(UpdateChecker.parseRelease("""{"no_tag":true}""", "1.2"))
    }

    @Test
    fun `a release with no name falls back to its tag`() {
        val json = """{"tag_name":"v1.5","html_url":"https://example.test","draft":false,"prerelease":false}"""
        assertEquals("v1.5", UpdateChecker.parseRelease(json, "1.2")!!.title)
    }

    @Test
    fun `the release GitHub actually serves is offered to an older install`() {
        /*
         * The fixture is the real payload from the releases API, trimmed to the
         * fields the parser reads. A hand-written one only proves the parser
         * agrees with itself; this proves it agrees with GitHub — the shape that
         * decides whether anyone ever hears about an update.
         */
        val json = javaClass.classLoader!!.getResourceAsStream("latest-release.json")!!
            .bufferedReader().use { it.readText() }

        val update = UpdateChecker.parseRelease(json, "1.7")

        assertEquals("1.8", update!!.versionName)
        assertTrue(update.pageUrl.startsWith("https://github.com/"))
        assertTrue(update.title.isNotBlank())
    }

    @Test
    fun `the same release is not offered to someone already running it`() {
        val json = javaClass.classLoader!!.getResourceAsStream("latest-release.json")!!
            .bufferedReader().use { it.readText() }

        assertNull(UpdateChecker.parseRelease(json, "1.8"))
        assertNull(UpdateChecker.parseRelease(json, "1.9"))
    }
}
