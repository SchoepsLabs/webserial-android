package com.trustedconfigurator.browser.bridge

/**
 * A site the browser knows about.
 *
 * @param usbEnabled whether this origin may reach the native USB bridge. Built-in
 * configurators ship enabled; anything the user adds starts disabled and only
 * becomes enabled through an explicit, warned opt-in.
 */
data class Site(
    val origin: String,
    val title: String,
    val builtIn: Boolean,
    val usbEnabled: Boolean,
)

/** Storage seam so the policy logic is testable without SharedPreferences. */
interface SitePersistence {
    /** @return user-added sites, and any USB overrides for built-in ones. */
    fun load(): List<Site>
    fun save(sites: List<Site>)
}

class InMemorySitePersistence(initial: List<Site> = emptyList()) : SitePersistence {
    private var state = initial
    override fun load(): List<Site> = state
    override fun save(sites: List<Site>) {
        state = sites
    }
}

/**
 * Which origins the browser will load, and which of those may touch USB.
 *
 * The two questions are deliberately separate. Navigation is open — this is a
 * browser, you can type any address. USB is not: an origin reaches the native
 * bridge only if it is a known configurator or the user explicitly turned it on
 * for that site. That keeps the "no untrusted site gets native USB" property
 * while removing the hard-coded limit of two addresses.
 */
class SitePolicy(private val persistence: SitePersistence) {

    companion object {
        /**
         * Configurators that get USB out of the box.
         *
         * Origins are post-redirect and exact: am32-configurator.vercel.app
         * redirects to am32.ca, and it is the final origin the page actually
         * runs on that matters.
         */
        // blackbox.betaflight.com is deliberately absent: the configurator now
        // carries a Blackbox Viewer tab of its own, and a log viewer has no use
        // for USB anyway — listing it would only widen the set of origins that
        // can reach the bridge for no benefit.
        val BUILT_IN: List<Site> = listOf(
            Site("https://app.betaflight.com", "Betaflight Configurator", builtIn = true, usbEnabled = true),
            Site("https://esc-configurator.com", "ESC Configurator", builtIn = true, usbEnabled = true),
            Site("https://am32.ca", "AM32 Configurator", builtIn = true, usbEnabled = true),
            Site("https://expresslrs.github.io", "ExpressLRS Web Flasher", builtIn = true, usbEnabled = true),
        )
    }

    private val sites = LinkedHashMap<String, Site>()

    init {
        BUILT_IN.forEach { sites[it.origin] = it }
        persistence.load().forEach { stored ->
            val builtIn = sites[stored.origin]
            sites[stored.origin] = if (builtIn != null) {
                // A stored entry for a built-in only carries the USB override;
                // the title and built-in flag stay authoritative in code.
                builtIn.copy(usbEnabled = stored.usbEnabled)
            } else {
                stored.copy(builtIn = false)
            }
        }
    }

    @Synchronized
    fun sites(): List<Site> = sites.values.sortedWith(
        compareByDescending<Site> { it.builtIn }.thenBy { it.title.lowercase() },
    )

    @Synchronized
    fun siteFor(origin: String?): Site? = OriginPolicy.normalize(origin)?.let { sites[it] }

    /** Origins currently permitted to use USB, as origin rules for androidx.webkit. */
    @Synchronized
    fun usbOrigins(): Set<String> =
        sites.values.filter { it.usbEnabled }.map { it.origin }.toSet()

    @Synchronized
    fun isUsbAllowed(origin: String?): Boolean {
        val normalized = OriginPolicy.normalize(origin) ?: return false
        return sites[normalized]?.usbEnabled == true
    }

    @Synchronized
    fun isKnown(origin: String?): Boolean =
        OriginPolicy.normalize(origin)?.let { sites.containsKey(it) } == true

    /**
     * @return the added site, or null when the address is not a usable https origin.
     */
    @Synchronized
    /**
     * Adds an origin, with USB access on unless told otherwise.
     *
     * On by default because typing an address in is already the act of intent,
     * and because the origin flag is not what protects the hardware — the native
     * device picker is. A page with navigator.serial can ask; it cannot reach
     * anything until a device is picked and Android grants permission, and
     * getPorts() only ever returns what was already granted to that origin.
     * Desktop Chrome hands every https origin the same API with no list at all.
     *
     * Off by default made the app refuse to do the one thing it exists for until
     * the user found a toggle, usually after a configurator had already shown
     * its own unsupported-browser screen.
     */
    fun addSite(url: String, title: String? = null, usbEnabled: Boolean = true): Site? {
        val origin = OriginPolicy.normalize(url) ?: return null
        val existing = sites[origin]
        /*
         * Adding a site is not a way to revoke one. Typing an origin that is
         * already known — a built-in, or one added earlier with USB on — used to
         * overwrite its flag with this parameter's `false` default, which
         * silently switched USB off for a bundled configurator. The page then
         * showed its own "browser has no Web Serial" message and the app said
         * nothing. Turning access off is setUsbEnabled's job, behind the toggle.
         */
        val site = existing?.copy(usbEnabled = existing.usbEnabled || usbEnabled)
            ?: Site(origin, title?.takeIf { it.isNotBlank() } ?: origin.removePrefix("https://"), false, usbEnabled)
        sites[origin] = site
        persist()
        return site
    }

    @Synchronized
    fun setUsbEnabled(origin: String, enabled: Boolean): Site? {
        val normalized = OriginPolicy.normalize(origin) ?: return null
        val site = sites[normalized] ?: return null
        val updated = site.copy(usbEnabled = enabled)
        sites[normalized] = updated
        persist()
        return updated
    }

    /** Built-in sites cannot be removed, only have their USB access revoked. */
    @Synchronized
    fun removeSite(origin: String): Boolean {
        val normalized = OriginPolicy.normalize(origin) ?: return false
        val site = sites[normalized] ?: return false
        if (site.builtIn) return false
        sites.remove(normalized)
        persist()
        return true
    }

    private fun persist() {
        // Built-ins are only persisted when their USB flag differs from the
        // shipped default, so changing the built-in list in a later version is
        // not overridden by stale storage.
        val defaults = BUILT_IN.associateBy { it.origin }
        persistence.save(
            sites.values.filter { site ->
                val default = defaults[site.origin]
                default == null || default.usbEnabled != site.usbEnabled
            },
        )
    }
}
