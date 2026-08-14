package com.trustedconfigurator.browser.bridge

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.trustedconfigurator.browser.usb.TransferKind
import com.trustedconfigurator.browser.usb.TransferLog

/**
 * Installs the polyfill and the native bridge object, scoped to exactly the
 * origins currently allowed to use USB.
 *
 * The allow-list is no longer fixed at build time, so both injections have to be
 * torn down and re-registered whenever the user enables or disables a site.
 * Re-registering only affects documents loaded afterwards, which is why enabling
 * USB for a site reloads it.
 */
class BridgeInstaller(
    private val context: Context,
    private val webView: WebView,
    private val policy: SitePolicy,
    private val bridge: ConfiguratorBridge,
) {

    private var scriptHandler: ScriptHandler? = null
    private var listenerInstalled = false

    /** Origins the current registration was made for, null when nothing is installed. */
    private var installedOrigins: Set<String>? = null

    /** Features the bridge cannot work without, empty when everything is present. */
    fun missingFeatures(): List<String> = buildList {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) add("DOCUMENT_START_SCRIPT")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) add("WEB_MESSAGE_LISTENER")
    }

    /**
     * (Re)registers both injections for the current USB origin set.
     *
     * @return true when the bridge is active for at least one origin.
     */
    fun install(): Boolean {
        if (missingFeatures().isNotEmpty()) return false

        val origins = policy.usbOrigins()

        /*
         * Re-registering is destructive: removing a WebMessageListener kills the
         * JavaScriptReplyProxy objects the live document is still holding, and
         * using one afterwards segfaults the WebView. onResume fires on every
         * dialog dismissal — the device picker, the USB permission prompt — so
         * reinstalling unconditionally tore the bridge out from under a page
         * mid-call. Only rebuild when the origin set has actually changed.
         */
        if (installedOrigins == origins) return origins.isNotEmpty()

        remove()
        if (origins.isEmpty()) {
            TransferLog.record(TransferKind.EVENT, "-", "-", "No site is allowed to use USB; bridge not installed")
            installedOrigins = origins
            return false
        }

        WebViewCompat.addWebMessageListener(webView, Protocol.JS_OBJECT_NAME, origins, bridge)
        listenerInstalled = true

        scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, polyfillSource(), origins)
        installedOrigins = origins

        TransferLog.record(
            TransferKind.EVENT,
            origin = "-",
            device = "-",
            detail = "USB bridge installed for ${origins.joinToString()}",
        )
        return true
    }

    fun remove() {
        // Any proxy handed out by the listener being removed is about to become
        // a dangling native pointer; drop them before the removal, not after.
        bridge.forgetProxies()
        scriptHandler?.let { runCatching { it.remove() } }
        scriptHandler = null
        if (listenerInstalled) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, Protocol.JS_OBJECT_NAME) }
            listenerInstalled = false
        }
        installedOrigins = null
    }

    private fun polyfillSource(): String =
        context.assets.open(POLYFILL_ASSET).bufferedReader().use { it.readText() }

    private companion object {
        const val POLYFILL_ASSET = "bridge/polyfill.js"
    }
}
