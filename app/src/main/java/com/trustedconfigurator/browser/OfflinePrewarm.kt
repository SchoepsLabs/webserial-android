package com.trustedconfigurator.browser

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.trustedconfigurator.browser.bridge.BrowserSettings
import com.trustedconfigurator.browser.usb.TransferKind
import com.trustedconfigurator.browser.usb.TransferLog

/**
 * Visits the built-in configurators once so they work with no signal.
 *
 * Both of them are progressive web apps: their own service workers cache the
 * whole tool the first time you open it, and after that they load offline. That
 * only helps if the first visit already happened, which at a flying field is
 * exactly when it has not. This does that visit ahead of time.
 *
 * Nothing is stored by the app — the cache belongs to the site, in the WebView's
 * own storage, and clearing site data clears it. This only causes the visit.
 *
 * Deliberately restrained about it:
 *  - unmetered networks only, so it never spends mobile data
 *  - one site at a time, and it gives up on one that will not load
 *  - at most once a week, since a site that is already cached gains nothing
 *  - switchable off, and then it never runs
 */
class OfflinePrewarm(
    private val context: Context,
    private val settings: BrowserSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** @return the reason it will not run now, or null when it should. */
    fun skipReason(origins: List<String>): String? = skipReason(
        enabled = settings.offlinePrewarmEnabled,
        origins = origins,
        metered = isMetered(),
        lastRun = settings.lastPrewarm,
        now = clock(),
    )

    /**
     * Loads each origin in turn in an off-screen WebView.
     *
     * Off-screen rather than in the visible one because the point is to warm a
     * cache without disturbing whatever the user is reading.
     */
    fun run(origins: List<String>, onFinished: (Int) -> Unit = {}) {
        val reason = skipReason(origins)
        if (reason != null) {
            TransferLog.record(TransferKind.EVENT, "-", "-", "Offline preload skipped: $reason")
            onFinished(0)
            return
        }
        settings.lastPrewarm = clock()
        TransferLog.record(TransferKind.EVENT, "-", "-", "Offline preload started for ${origins.size} sites")
        visit(origins, 0, 0, onFinished)
    }

    private fun visit(origins: List<String>, index: Int, done: Int, onFinished: (Int) -> Unit) {
        if (index >= origins.size) {
            TransferLog.record(TransferKind.EVENT, "-", "-", "Offline preload finished; $done of ${origins.size} cached")
            onFinished(done)
            return
        }
        val origin = origins[index]
        val web = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        var settled = false

        fun finishOne(ok: Boolean) {
            if (settled) return
            settled = true
            handler.removeCallbacksAndMessages(null)
            // Destroying immediately would cancel the service worker's own
            // fetches; it registers during load and keeps caching after.
            handler.postDelayed({
                runCatching { web.destroy() }
                visit(origins, index + 1, done + if (ok) 1 else 0, onFinished)
            }, SETTLE_MS)
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                TransferLog.record(TransferKind.EVENT, origin, "-", "Offline preload loaded")
                finishOne(true)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    TransferLog.record(TransferKind.ERROR, origin, "-", "Offline preload failed")
                    finishOne(false)
                }
            }
        }
        handler.postDelayed({
            TransferLog.record(TransferKind.ERROR, origin, "-", "Offline preload timed out")
            finishOne(false)
        }, TIMEOUT_MS)
        web.loadUrl(origin)
    }

    private fun isMetered(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return manager?.isActiveNetworkMetered ?: true
    }

    companion object {
        /**
         * The whole decision, as a function of its inputs.
         *
         * Pulled out so it can be tested for real rather than by a copy in the
         * test — this is the part that could quietly spend someone's mobile
         * data, so it is worth exercising the actual code.
         */
        fun skipReason(
            enabled: Boolean,
            origins: List<String>,
            metered: Boolean,
            lastRun: Long,
            now: Long,
        ): String? = when {
            !enabled -> "switched off"
            origins.isEmpty() -> "no sites"
            metered -> "the connection is metered"
            now - lastRun < INTERVAL_MS -> "done recently"
            else -> null
        }

        const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 4_000L
    }
}
