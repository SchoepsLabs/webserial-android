package com.trustedconfigurator.browser.bridge

/**
 * Origin normalisation — the one place a URL is reduced to the identity that
 * USB access is keyed on.
 *
 * Which origins are actually allowed lives in [SitePolicy]; this object only
 * answers "what origin is this, and can I trust the answer". It is deliberately
 * strict: anything it cannot reduce to a plain https origin returns null, and a
 * null origin is never granted anything.
 */
object OriginPolicy {

    /**
     * Reduces a URL to `https://host[:port]`.
     *
     * @return the origin, or null when the input is not a plain https URL or
     * could smuggle a different authority past a naive prefix check.
     */
    fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        // http, file, data, javascript and friends never reach the bridge.
        if (scheme != "https") return null

        val rest = trimmed.substring(schemeEnd + 3)
        // Userinfo would let https://app.betaflight.com@evil.test/ pass a
        // startsWith check while actually loading evil.test.
        if (rest.contains('@')) return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        if (authority.isEmpty()) return null
        return if (isValidAuthority(authority)) "$scheme://$authority" else null
    }

    /**
     * @return true when [authority] is a plain `host` or `host:port`.
     *
     * Without this, text that merely looks address-ish — `javascript:alert(1)`
     * has no `://`, so [toUrl] would happily prefix it with `https://` — becomes
     * an "origin" that no real page could ever match but that still gets stored
     * and compared against.
     */
    private fun isValidAuthority(authority: String): Boolean {
        val host: String
        val port: String?
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1)
        } else {
            host = authority
            port = null
        }

        if (port != null && (port.isEmpty() || !port.all { it.isDigit() })) return false
        if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false
        return host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    /**
     * Turns whatever the user typed into a loadable URL.
     *
     * @return an https URL, or null when the text cannot be read as an address.
     */
    fun toUrl(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null

        val candidate = when {
            text.startsWith("https://", ignoreCase = true) -> text
            text.startsWith("http://", ignoreCase = true) -> "https://" + text.substring(7)
            text.contains("://") -> return null // some other scheme; not ours to load
            else -> "https://$text"
        }
        // Must survive normalisation, i.e. have a real host and no userinfo.
        return if (normalize(candidate) != null) candidate else null
    }
}
