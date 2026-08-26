package com.trustedconfigurator.browser.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists user-added sites and USB overrides for built-in ones. */
class SharedPreferencesSitePersistence(context: Context) : SitePersistence {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<Site> {
        val raw = prefs.getString(KEY_SITES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val origin = OriginPolicy.normalize(json.optString("origin")) ?: return@mapNotNull null
                Site(
                    origin = origin,
                    title = json.optString("title", origin),
                    builtIn = false,
                    usbEnabled = json.optBoolean("usb", false),
                )
            }
        } catch (e: Exception) {
            // Corrupt storage must not brick the browser; fall back to built-ins.
            emptyList()
        }
    }

    override fun save(sites: List<Site>) {
        val array = JSONArray()
        sites.forEach { site ->
            array.put(
                JSONObject()
                    .put("origin", site.origin)
                    .put("title", site.title)
                    .put("usb", site.usbEnabled),
            )
        }
        prefs.edit().putString(KEY_SITES, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "sites"
        const val KEY_SITES = "sites_json"
    }
}

/**
 * Persists per-origin device grants.
 *
 * Stored as a JSON object keyed by origin so the set of origins is open-ended —
 * the previous implementation could only persist grants for a fixed allow-list.
 */
class SharedPreferencesGrantPersistence(context: Context) : GrantPersistence {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Map<String, Set<String>> {
        val raw = prefs.getString(KEY_GRANTS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { origin ->
                val keys = json.optJSONArray(origin) ?: JSONArray()
                (0 until keys.length()).mapNotNull { keys.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun save(grants: Map<String, Set<String>>) {
        val json = JSONObject()
        grants.forEach { (origin, keys) ->
            if (keys.isNotEmpty()) {
                json.put(origin, JSONArray().also { array -> keys.forEach(array::put) })
            }
        }
        prefs.edit().putString(KEY_GRANTS, json.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "usb_origin_grants"
        const val KEY_GRANTS = "grants_json"
    }
}

/** Small user preferences that change how pages are rendered. */
class BrowserSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var desktopMode: Boolean
        // Off by default: the phone layout is what a phone should get, and the
        // desktop one is a deliberate choice from the menu. The setting sticks
        // once it is made either way.
        get() = prefs.getBoolean(KEY_DESKTOP, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP, value).apply()

    /**
     * Whether the built-in configurators are visited once so they work offline.
     *
     * On by default: a phone taken to a flying field has no signal, and a
     * configurator that was never opened at home is useless there. It only ever
     * runs on an unmetered connection, so it costs no mobile data.
     */
    var offlinePrewarmEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREWARM, true)
        set(value) = prefs.edit().putBoolean(KEY_PREWARM, value).apply()

    /** When the sites were last visited for their offline cache. */
    var lastPrewarm: Long
        get() = prefs.getLong(KEY_PREWARM_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_PREWARM_AT, value).apply()

    var fullScreen: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    /** Off means no network call to GitHub is ever made. */
    var updateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_UPDATE_CHECK, value).apply()

    var lastUpdateCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** Version the user has already been told about, so it is not offered twice. */
    var dismissedUpdate: String
        get() = prefs.getString(KEY_DISMISSED_UPDATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISMISSED_UPDATE, value).apply()

    var lastUrl: String
        get() = prefs.getString(KEY_LAST_URL, null) ?: SitePolicy.BUILT_IN.first().origin
        set(value) = prefs.edit().putString(KEY_LAST_URL, value).apply()

    private companion object {
        const val PREFS_NAME = "browser_settings"
        const val KEY_DESKTOP = "desktop_mode"
        const val KEY_PREWARM = "offline_prewarm"
        const val KEY_PREWARM_AT = "offline_prewarm_at"
        const val KEY_FULLSCREEN = "full_screen"
        const val KEY_LAST_URL = "last_url"
        const val KEY_UPDATE_CHECK = "update_check"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_DISMISSED_UPDATE = "dismissed_update"
    }
}
