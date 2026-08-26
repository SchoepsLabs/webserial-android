package com.trustedconfigurator.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import org.json.JSONArray
import org.json.JSONObject
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import com.trustedconfigurator.browser.bridge.BridgeInstaller
import com.trustedconfigurator.browser.bridge.BrowserSettings
import com.trustedconfigurator.browser.bridge.ConfiguratorBridge
import com.trustedconfigurator.browser.bridge.DevicePicker
import com.trustedconfigurator.browser.bridge.GrantStore
import com.trustedconfigurator.browser.bridge.OriginPolicy
import com.trustedconfigurator.browser.bridge.SharedPreferencesGrantPersistence
import com.trustedconfigurator.browser.bridge.SharedPreferencesSitePersistence
import com.trustedconfigurator.browser.bridge.SitePolicy
import com.trustedconfigurator.browser.databinding.ActivityMainBinding
import com.trustedconfigurator.browser.files.FileBridge
import com.trustedconfigurator.browser.files.FilePicker
import com.trustedconfigurator.browser.usb.TransferKind
import com.trustedconfigurator.browser.usb.TransferLog
import com.trustedconfigurator.browser.update.AvailableUpdate
import com.trustedconfigurator.browser.update.UpdateChecker
import com.trustedconfigurator.browser.usb.UsbHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The browser.
 *
 * Loads the configurators live from the network and adds three things a WebView
 * does not have: Web Serial, WebUSB, and the File System Access API — the last
 * because otherwise every "save preset" and "save blackbox log" button is a
 * no-op.
 */
class MainActivity : AppCompatActivity(), DevicePicker, FilePicker {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hub: UsbHub
    private lateinit var bridge: ConfiguratorBridge
    private lateinit var installer: BridgeInstaller
    private lateinit var policy: SitePolicy
    private lateinit var settings: BrowserSettings
    private lateinit var files: FileBridge

    /** Session-only: see applyScrollLock. */
    private var pageLocked = false
    /** True when the lock was put on by the motor detection, not by the user. */
    private var lockAutoEngaged = false

    private var currentOrigin: String? = null
    private var chromeExpanded = true

    // SAF pickers. Registered once; each launch resumes the coroutine that asked.
    private var pendingCreate: ((Uri?) -> Unit)?
        get() = pendingCreateRequest
        set(value) { pendingCreateRequest = value }

    private var pendingOpen: ((Uri?) -> Unit)?
        get() = pendingOpenRequest
        set(value) { pendingOpenRequest = value }
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    /*
     * Held in the companion, not the instance: if the system recreates this
     * activity while the picker is in front, the result lands on the new
     * instance and an instance field would already be null — the coroutine
     * would then wait forever and the page's save would hang with no error.
     */
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val callback = pendingCreate
        pendingCreate = null
        callback?.invoke(uri)
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingOpen
        pendingOpen = null
        callback?.invoke(uri)
    }

    private val chooseForInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingFileChooser?.onReceiveValue(if (uri == null) emptyArray() else arrayOf(uri))
        pendingFileChooser = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = BrowserSettings(this)
        policy = SitePolicy(SharedPreferencesSitePersistence(this))
        files = FileBridge(this, this)
        hub = UsbHub(this, GrantStore(SharedPreferencesGrantPersistence(this)))
        bridge = ConfiguratorBridge(hub, this, policy, files, lifecycleScope)

        hub.onDeviceAttached = { device ->
            bridge.notifyDeviceAttached(device)
            runOnUiThread { updateUsbIndicator() }
        }
        hub.onDeviceDetached = { device ->
            bridge.notifyDeviceDetached(device)
            runOnUiThread { updateUsbIndicator() }
        }
        hub.onDfuHandoff = { device, handoffs ->
            toast(getString(R.string.dfu_detected, hub.describe(device), handoffs.first().reason.name))
        }
        hub.start()

        configureWebView()
        installer = BridgeInstaller(this, binding.webView, policy, bridge)
        warnIfBridgeUnavailable()
        installer.install()

        wireChrome()
        applyFullScreen(settings.fullScreen)

        if (savedInstanceState == null) {
            val requested = intent?.dataString?.let { OriginPolicy.toUrl(it) }
            binding.webView.loadUrl(requested ?: settings.lastUrl)
        }

        checkForUpdates(userInitiated = false)

        // After the visible page has been asked for, so the preload never
        // competes with what the user is actually waiting on.
        binding.webView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                OfflinePrewarm(this, settings).run(policy.sites().filter { it.builtIn }.map { it.origin })
            }
        }, PREWARM_DELAY_MS)

        onBackPressedDispatcher.addCallback(this) {
            when {
                // Swallowed while the page is locked. On ESC Configurator the
                // sliders reach the screen edge, so a drag there is also an edge
                // swipe: Android reads it as Back and the page navigates away
                // mid-adjustment, with the motors still spinning. Gesture
                // exclusion alone cannot cover it — Android caps that at 200dp
                // per edge — so the gesture is consumed instead.
                pageLocked -> toast(getString(R.string.locked_back_blocked))
                settings.fullScreen -> applyFullScreen(false)
                binding.webView.canGoBack() -> binding.webView.goBack()
                else -> finish()
            }
        }
    }

    // ------------------------------------------------------------- chrome

    private fun wireChrome() {
        binding.addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateTo(binding.addressBar.text.toString())
                true
            } else {
                false
            }
        }
        binding.menuButton.setOnClickListener { showMenu() }
        binding.usbIndicator.setOnClickListener { onUsbIndicatorTapped() }
        binding.stopMotors.setOnClickListener { stopMotors() }
        binding.exitFullScreen.setOnClickListener { applyFullScreen(false) }

        installScrollReporter()
    }

    /**
     * Hides the address bar while reading.
     *
     * The configurators scroll an inner element rather than the document, so the
     * WebView's own scrollY never changes — AppBarLayout's scroll flags and a
     * View scroll listener are both dead ends. A tiny injected script reports the
     * direction instead. It runs on every origin and is a separate channel from
     * the USB bridge: all it can do is show or hide this app's own toolbar.
     */
    private fun installScrollReporter() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            return
        }
        val anyOrigin = setOf("*")
        WebViewCompat.addWebMessageListener(
            binding.webView,
            CHROME_CHANNEL,
            anyOrigin,
        ) { _, message, _, _, _ ->
            onChromeMessage(message.data)
        }
        WebViewCompat.addDocumentStartJavaScript(
            binding.webView,
            assets.open(CHROME_ASSET).bufferedReader().use { it.readText() },
            anyOrigin,
        )
    }

    /**
     * Handles one message from the page-side chrome script.
     *
     * Parsed rather than substring-matched: it now carries gesture-exclusion
     * rectangles as well as show/hide, and "exclude" contains neither keyword.
     */
    private fun onChromeMessage(raw: String?) {
        val json = runCatching { JSONObject(raw ?: return) }.getOrNull() ?: return
        when (json.optString("chrome")) {
            "exclude" -> applyGestureExclusions(json.optJSONArray("rects"))
            "armed" -> onMotorsArmed(json.optBoolean("armed"))
            "collapse" -> if (!settings.fullScreen && !binding.addressBar.hasFocus()) setChromeExpanded(false)
            "expand" -> if (!settings.fullScreen && !binding.addressBar.hasFocus()) setChromeExpanded(true)
        }
    }

    /**
     * Tells Android not to read a drag on an edge-hugging slider as a Back swipe.
     *
     * ESC Configurator's knobs sit close to the left edge, so adjusting one used
     * to navigate the page away instead. The page reports where those sliders
     * are in CSS pixels; a WebView lays out one CSS pixel per density-independent
     * pixel, so the only conversion needed is the display density.
     *
     * Android ignores exclusions beyond 200dp per edge, which is why the page
     * only ever sends the handful that are on screen.
     */
    private fun applyGestureExclusions(rects: JSONArray?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // The lock claims both edges outright and the page reports on every
        // scroll, so without this the next report would hand them straight back.
        if (pageLocked) return
        val density = resources.displayMetrics.density
        val exclusions = buildList {
            for (index in 0 until (rects?.length() ?: 0)) {
                val r = rects?.optJSONArray(index) ?: continue
                if (r.length() < 4) continue
                add(
                    Rect(
                        (r.optDouble(0) * density).toInt(),
                        (r.optDouble(1) * density).toInt(),
                        (r.optDouble(2) * density).toInt(),
                        (r.optDouble(3) * density).toInt(),
                    ),
                )
            }
        }
        binding.webView.systemGestureExclusionRects = exclusions
    }

    private fun setChromeExpanded(expanded: Boolean) {
        if (chromeExpanded == expanded) return
        chromeExpanded = expanded
        binding.appBar.setExpanded(expanded, true)
    }

    private fun navigateTo(input: String) {
        val url = OriginPolicy.toUrl(input)
        if (url == null) {
            toast(getString(R.string.bad_address))
            return
        }
        hideKeyboard()
        binding.addressBar.clearFocus()
        applyOfflineCacheMode()
        binding.webView.loadUrl(url)
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.menuButton)
        val menu = popup.menu

        policy.sites().forEachIndexed { index, site ->
            menu.add(GROUP_SITES, index, index, site.title)
        }
        menu.add(GROUP_ACTIONS, ID_RELOAD, 100, R.string.menu_reload)
        menu.add(GROUP_ACTIONS, ID_DESKTOP, 101, R.string.menu_desktop).apply {
            isCheckable = true
            isChecked = settings.desktopMode
        }
        menu.add(GROUP_ACTIONS, ID_FULLSCREEN, 102, R.string.menu_full_screen).apply {
            isCheckable = true
            isChecked = settings.fullScreen
        }
        menu.add(GROUP_ACTIONS, ID_SITES, 103, R.string.menu_sites)
        menu.add(GROUP_ACTIONS, ID_DIAGNOSTICS, 104, R.string.menu_diagnostics)
        menu.add(GROUP_ACTIONS, ID_UPDATES, 105, R.string.menu_check_updates)
        menu.add(GROUP_ACTIONS, ID_AUTO_UPDATES, 106, R.string.update_check_toggle).apply {
            isCheckable = true
            isChecked = settings.updateCheckEnabled
        }
        menu.add(GROUP_ACTIONS, ID_OFFLINE, 107, R.string.offline_toggle).apply {
            isCheckable = true
            isChecked = settings.offlinePrewarmEnabled
        }
        menu.add(GROUP_ACTIONS, ID_SCROLL_LOCK, 108, R.string.scroll_lock_toggle).apply {
            isCheckable = true
            isChecked = pageLocked
        }

        popup.setOnMenuItemClickListener { item -> onMenuItem(item) }
        popup.show()
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        if (item.groupId == GROUP_SITES) {
            policy.sites().getOrNull(item.itemId)?.let { binding.webView.loadUrl(it.origin) }
            return true
        }
        return when (item.itemId) {
            ID_RELOAD -> {
                binding.webView.reload(); true
            }
            ID_SCROLL_LOCK -> {
                pageLocked = !pageLocked
                lockAutoEngaged = false
                applyPageLock()
                toast(getString(if (pageLocked) R.string.scroll_lock_on else R.string.scroll_lock_off))
                true
            }
            ID_OFFLINE -> {
                settings.offlinePrewarmEnabled = !settings.offlinePrewarmEnabled
                if (settings.offlinePrewarmEnabled) {
                    // Turning it on is a request to do it, not just to allow it
                    // later, so the weekly gate is cleared for this one run.
                    settings.lastPrewarm = 0L
                    toast(getString(R.string.offline_on))
                    OfflinePrewarm(this, settings).run(policy.sites().filter { it.builtIn }.map { it.origin }) { cached ->
                        toast(getString(R.string.offline_done, cached))
                    }
                } else {
                    toast(getString(R.string.offline_off))
                }
                true
            }
            ID_DESKTOP -> {
                settings.desktopMode = !settings.desktopMode
                applyDesktopMode()
                binding.webView.reload()
                true
            }
            ID_FULLSCREEN -> {
                applyFullScreen(!settings.fullScreen); true
            }
            ID_SITES -> {
                startActivity(Intent(this, SitesActivity::class.java)); true
            }
            ID_DIAGNOSTICS -> {
                startActivity(Intent(this, DiagnosticsActivity::class.java)); true
            }
            ID_UPDATES -> {
                checkForUpdates(userInitiated = true); true
            }
            ID_AUTO_UPDATES -> {
                settings.updateCheckEnabled = !settings.updateCheckEnabled
                toast(
                    getString(
                        if (settings.updateCheckEnabled) R.string.update_auto_on else R.string.update_auto_off,
                    ),
                )
                true
            }
            else -> false
        }
    }

    /**
     * Hides the address bar and the system bars so the page owns the screen.
     * The transient-swipe behaviour means a swipe from the edge brings the
     * system bars back without leaving full screen.
     */
    private fun applyFullScreen(enabled: Boolean) {
        settings.fullScreen = enabled
        val controller = WindowInsetsControllerCompat(window, binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)

        // Without this the window still stops short of the notch, leaving a black
        // band exactly where the address bar used to be — the space is given back
        // by the layout but not by the window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (enabled) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        /*
         * The bar is collapsed to zero height rather than hidden. CoordinatorLayout
         * never lays out a GONE child, so ScrollingViewBehavior would keep offsetting
         * the WebView by the bar's last known bottom edge — the page would lose the
         * bar but not get its space back. A zero-height bar is still laid out, so the
         * offset really does become zero.
         */
        binding.appBar.layoutParams = binding.appBar.layoutParams.apply {
            height = if (enabled) 0 else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (!enabled) {
            setChromeExpanded(true)
        }
        binding.exitFullScreen.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * Shows whether the page in front of you can reach USB.
     *
     * The icon changes shape, not just colour. The same plug tinted red or green
     * told nobody anything, and the state it has to convey is the difference
     * between a configurator working and it showing its own "this browser has no
     * Web Serial" screen.
     */
    private fun updateUsbIndicator() {
        val allowed = policy.isUsbAllowed(currentOrigin)
        val attached = runCatching { hub.devices().isNotEmpty() }.getOrDefault(false)

        val icon = if (allowed) R.drawable.ic_usb else R.drawable.ic_usb_off
        val colour = when {
            !allowed -> R.color.usb_blocked
            attached -> R.color.usb_on
            else -> R.color.usb_off
        }
        val description = when {
            !allowed -> R.string.usb_off_summary
            attached -> R.string.usb_attached_summary
            else -> R.string.usb_on_summary
        }

        binding.usbIndicator.setImageResource(icon)
        binding.usbIndicator.imageTintList = ContextCompat.getColorStateList(this, colour)
        binding.usbIndicator.contentDescription = getString(description)
    }

    /**
     * Says so when a known configurator has been opened with USB switched off.
     *
     * Without this the page just shows its own unsupported-browser message and
     * the app stays silent, which is exactly how a switched-off site was
     * mistaken for a broken bridge.
     */
    private fun warnIfUsbOffForConfigurator() {
        val origin = currentOrigin ?: return
        if (policy.isUsbAllowed(origin)) return
        // Any site, not just the built-ins: a switched-off site looks equally
        // broken whatever its origin, and switching one off is now deliberate
        // enough that saying so cannot be a nuisance.
        if (!policy.isKnown(origin)) return
        Snackbar.make(binding.root, R.string.usb_off_here, Snackbar.LENGTH_LONG)
            .setAction(R.string.usb_off_enable) { enableUsbForCurrentSite() }
            .show()
    }

    /**
     * The USB icon in the address bar. Deliberately one-way.
     *
     * Turning access *on* is one tap, because a page that cannot see the
     * hardware is the app failing at its purpose. Turning it *off* is not
     * reachable from here at all: it used to be the same tap, so a stray touch
     * next to the address bar silently broke every configurator on that site,
     * and the page's own "this browser has no Web Serial" screen is what the
     * user saw. Switching a site off now lives under Sites, behind a
     * confirmation, where it is a decision rather than an accident.
     */
    private fun onUsbIndicatorTapped() {
        val origin = currentOrigin ?: return
        if (policy.isUsbAllowed(origin)) {
            val devices = runCatching { hub.devices() }.getOrDefault(emptyList())
            val message = if (devices.isEmpty()) {
                getString(R.string.usb_on_here)
            } else {
                getString(R.string.usb_attached_here, devices.joinToString { hub.describe(it) })
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                .setAction(R.string.menu_sites) { startActivity(Intent(this, SitesActivity::class.java)) }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enable_usb_title, origin))
            .setMessage(R.string.enable_usb_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.enable_usb_confirm) { _, _ ->
                policy.addSite(origin, binding.webView.title, usbEnabled = true)
                onPolicyChanged(getString(R.string.usb_enabled_for, origin))
            }
            .show()
    }

    /** Turns USB on for the current site without asking; used by the "it is off" prompt. */
    private fun enableUsbForCurrentSite() {
        val origin = currentOrigin ?: return
        policy.addSite(origin, binding.webView.title, usbEnabled = true)
        onPolicyChanged(getString(R.string.usb_enabled_for, origin))
    }

    /**
     * Re-registers both injections and reloads: origin rules are fixed at
     * registration time and only apply to documents loaded afterwards.
     */
    private fun onPolicyChanged(message: String) {
        installer.install()
        updateUsbIndicator()
        binding.webView.reload()
        toast(message)
    }

    // ------------------------------------------------------------ webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Pages are fetched over HTTPS and need no local file reach.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        applyDesktopMode()

        binding.webView.webViewClient = BrowserClient()
        binding.webView.webChromeClient = BrowserChromeClient()
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    /**
     * Desktop mode is off by default and turned on from the menu. The
     * configurators do lay out for a wide window, but forcing that on a phone
     * makes everything small before the user has asked for it.
     *
     * Betaflight decides it is "native Android" from Capacitor, never the user
     * agent, so overriding the UA here does not change which transport it
     * picks.
     */
    /**
     * Falls back to the WebView's own cache when there is no connection.
     *
     * A progressive web app brings its own service worker and needs none of
     * this, but not every tool is one — a plain static site would simply fail at
     * a field. LOAD_CACHE_ELSE_NETWORK serves whatever was stored on the last
     * visit instead, which for hashed build assets is the whole page.
     *
     * Only while actually offline. Leaving it on would serve yesterday's build
     * of a configurator that has since been updated, and loading the current
     * version is the entire point of this app.
     */
    private fun applyOfflineCacheMode() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val online = manager?.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        binding.webView.settings.cacheMode =
            if (online) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
    }

    /**
     * Holds the page still while motors can spin.
     *
     * Betaflight already swallows wheel events over its motor test when testing
     * is armed, "so the page cannot scroll out from under the pointer while the
     * motors can spin". There is no touch equivalent, and on a phone touch is
     * all there is: a scroll that moves the page mid-drag moves the slider under
     * your finger with it.
     *
     * Not persisted. A browser that would not scroll on the next launch, for a
     * reason set days ago, is a browser that looks broken.
     */
    /**
     * Locks the page by itself once a configurator's motors can spin.
     *
     * The page reports it, from a structural signal rather than wording: ESC
     * Configurator's arming checkbox has a stable name, and Betaflight's motor
     * sliders stop being data-disabled. Nothing else on either site produces it.
     *
     * Told, not asked. A modal would be the wrong thing while motors are
     * spinning — it steals the tap you might need for the stop button — so this
     * is a message with a way out, not a decision to make.
     *
     * The lock is only released again if this is what put it on; a lock the user
     * set by hand is theirs to remove.
     */
    private fun onMotorsArmed(armed: Boolean) {
        binding.stopMotors.visibility = if (armed) View.VISIBLE else View.GONE
        if (armed == lockAutoEngaged && armed == pageLocked) return
        if (armed) {
            if (pageLocked) return
            pageLocked = true
            lockAutoEngaged = true
            applyPageLock()
            Snackbar.make(binding.root, R.string.locked_for_motors, Snackbar.LENGTH_LONG)
                .setAction(R.string.locked_unlock) {
                    pageLocked = false
                    lockAutoEngaged = false
                    applyPageLock()
                }
                .show()
        } else if (lockAutoEngaged) {
            pageLocked = false
            lockAutoEngaged = false
            applyPageLock()
            toast(getString(R.string.scroll_lock_off))
        }
    }

    /**
     * Presses the configurator's own stop control.
     *
     * Deliberately not our own idea of stopping: whatever the site does — the
     * MSP writes, the state cleanup — happens exactly as if the button had been
     * reached by hand. If no control can be found the user is told plainly
     * rather than left believing the motors were stopped.
     */
    private fun stopMotors() {
        binding.webView.evaluateJavascript("window.__configuratorStopMotors && window.__configuratorStopMotors()") { result ->
            val found = result != null && !result.contains("not-found") && result != "null"
            toast(getString(if (found) R.string.stop_motors_sent else R.string.stop_motors_failed))
        }
    }

    private fun applyPageLock() {
        binding.webView.evaluateJavascript(
            "window.__configuratorSetScrollLock && window.__configuratorSetScrollLock($pageLocked)",
            null,
        )
        applyEdgeExclusionForLock()
    }

    /**
     * Hands both whole screen edges to the page while it is locked.
     *
     * Consuming Back already stops the navigation, but without this the swipe
     * still animates and the arrow still appears, which looks like the app is
     * about to leave. Android ignores anything past 200dp per edge, so this asks
     * for the tallest band it will honour rather than the whole page.
     */
    private fun applyEdgeExclusionForLock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!pageLocked) return
        val band = (24 * resources.displayMetrics.density).toInt()
        val height = binding.webView.height.coerceAtLeast(1)
        binding.webView.systemGestureExclusionRects = listOf(
            Rect(0, 0, band, height),
            Rect((binding.webView.width - band).coerceAtLeast(0), 0, binding.webView.width, height),
        )
    }

    private fun applyDesktopMode() {
        val webSettings = binding.webView.settings
        if (settings.desktopMode) {
            webSettings.userAgentString = DESKTOP_USER_AGENT
            webSettings.useWideViewPort = true
            webSettings.loadWithOverviewMode = true
            binding.webView.setInitialScale(DESKTOP_SCALE_PERCENT)
        } else {
            webSettings.userAgentString = null
            binding.webView.setInitialScale(0)
        }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        // blob: and data: never reach here — the polyfill intercepts those and
        // routes them through the save dialog instead.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.download_failed, name))
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                // Before scoped storage, the public Downloads folder needs
                // WRITE_EXTERNAL_STORAGE. The app-specific directory needs no
                // permission on any version, which keeps this app permission-free.
                request.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(getString(R.string.download_started, name))
        } catch (e: Exception) {
            TransferLog.record(TransferKind.ERROR, currentOrigin ?: "-", name, "Download failed: ${e.message}")
            toast(getString(R.string.download_failed, name))
        }
    }

    private inner class BrowserClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            // Anything that is not plain https (mailto:, intent:, market:) is the
            // system's business, not this browser's.
            if (OriginPolicy.normalize(url) != null) return false
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                true
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.no_browser))
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            // The previous document's reply channels die with it; using one
            // afterwards crashes the WebView natively.
            bridge.forgetProxies()
            setChromeExpanded(true)
            currentOrigin = OriginPolicy.normalize(url)
            binding.addressBar.setText(url)
            updateUsbIndicator()
        }

        override fun onPageFinished(view: WebView, url: String) {
            currentOrigin = OriginPolicy.normalize(url)
            binding.addressBar.setText(url)
            updateUsbIndicator()
            warnIfUsbOffForConfigurator()
            if (pageLocked) applyPageLock()
            settings.lastUrl = url
        }
    }

    private inner class BrowserChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.progress = newProgress
            binding.progress.visibility = if (newProgress in 1..99) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        /** `<input type="file">` — firmware, presets, blackbox logs, ESC hex files. */
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            pendingFileChooser?.onReceiveValue(null)
            pendingFileChooser = filePathCallback
            val types = fileChooserParams.acceptTypes
                .filter { it.isNotBlank() && it.contains('/') }
                .toTypedArray()
            return try {
                chooseForInput.launch(if (types.isEmpty()) arrayOf("*/*") else types)
                true
            } catch (e: ActivityNotFoundException) {
                pendingFileChooser = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }
    }

    // ------------------------------------------------------- FilePicker

    override suspend fun createDocument(suggestedName: String, mimeType: String): Uri? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                // A request still outstanding means its dialog was lost; settle it
                // so the page that asked gets an AbortError rather than hanging.
                pendingCreate?.invoke(null)
                pendingCreate = { uri -> if (continuation.isActive) continuation.resume(uri) }
                continuation.invokeOnCancellation { pendingCreate = null }
                try {
                    createDocument.launch(suggestedName)
                } catch (e: Exception) {
                    pendingCreate = null
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    override suspend fun openDocument(mimeTypes: Array<String>): Uri? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                pendingOpen?.invoke(null)
                pendingOpen = { uri -> if (continuation.isActive) continuation.resume(uri) }
                continuation.invokeOnCancellation { pendingOpen = null }
                try {
                    openDocument.launch(mimeTypes)
                } catch (e: Exception) {
                    pendingOpen = null
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    // ------------------------------------------------------ DevicePicker

    override suspend fun choose(origin: String, prompt: String, devices: List<UsbDevice>): UsbDevice? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val labels = devices.map { device ->
                    "%s\n%04X:%04X  %s".format(
                        device.productName ?: getString(R.string.unnamed_device),
                        device.vendorId,
                        device.productId,
                        device.deviceName,
                    )
                }.toTypedArray()

                // The origin belongs in the title: AlertController only renders
                // the item list when no message is set, so setMessage() here
                // would hide every device.
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.picker_title, prompt, origin))
                    .setItems(labels) { _, which ->
                        if (continuation.isActive) continuation.resume(devices[which])
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        if (continuation.isActive) continuation.resume(null)
                    }
                    .setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
                    .create()

                continuation.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
        }

    // ------------------------------------------------------------ updates

    /**
     * Asks GitHub whether a newer release exists.
     *
     * The sites this browser loads are always current because they are fetched
     * live; the app around them is not, so it has to say when it has fallen
     * behind. It only ever reports — installing is the system's job, through the
     * release page in a real browser. Automatic checks run at most daily and can
     * be turned off entirely.
     */
    private fun checkForUpdates(userInitiated: Boolean) {
        if (!userInitiated) {
            if (!settings.updateCheckEnabled) return
            val elapsed = System.currentTimeMillis() - settings.lastUpdateCheckMillis
            if (elapsed in 0 until UPDATE_CHECK_INTERVAL_MS) return
        }

        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest(BuildConfig.VERSION_NAME) }
            settings.lastUpdateCheckMillis = System.currentTimeMillis()

            when {
                update == null && userInitiated ->
                    toast(getString(R.string.update_none, BuildConfig.VERSION_NAME))
                update == null -> Unit
                // Do not nag: a version already declined stays declined unless asked for.
                !userInitiated && update.versionName == settings.dismissedUpdate -> Unit
                else -> showUpdateDialog(update)
            }
        }
    }

    private fun showUpdateDialog(update: AvailableUpdate) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, update.versionName))
            .setMessage(getString(R.string.update_body, BuildConfig.VERSION_NAME))
            .setNegativeButton(R.string.update_later) { _, _ ->
                settings.dismissedUpdate = update.versionName
            }
            .setOnCancelListener { settings.dismissedUpdate = update.versionName }
            .setPositiveButton(R.string.update_download) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl)))
                } catch (e: ActivityNotFoundException) {
                    toast(getString(R.string.no_browser))
                }
            }
            .show()
    }

    // ------------------------------------------------------------- misc

    private fun warnIfBridgeUnavailable() {
        val missing = installer.missingFeatures()
        if (missing.isEmpty()) return
        TransferLog.record(
            TransferKind.ERROR,
            "-",
            "-",
            "WebView is missing ${missing.joinToString()}; USB support is unavailable",
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.unsupported_webview_title)
            .setMessage(getString(R.string.unsupported_webview_message, missing.joinToString()))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
    }

    private fun toast(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        applyOfflineCacheMode()
        // Sites screen may have changed who can use USB.
        installer.install()
        updateUsbIndicator()
    }

    override fun onDestroy() {
        /*
         * Order matters here, and getting it wrong crashes on the way out.
         *
         * The bridge hands JavaScriptReplyProxy objects to the live document and
         * pushes USB attach and detach events through them. Those proxies point
         * into the WebView's native side, so anything still holding one when
         * destroy() runs posts into freed memory. installer.remove() drops the
         * proxies and unregisters the listener, and hub.stop() goes first so a
         * detach broadcast cannot arrive mid-teardown and repopulate them.
         */
        hub.stop()
        installer.remove()
        bridge.closeAll()
        files.closeAll()

        /*
         * A WebView has to leave the view hierarchy before destroy(). Left
         * attached, the parent can still measure or draw a view whose native
         * peer is already gone.
         */
        binding.webView.stopLoading()
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val GROUP_SITES = 1
        const val GROUP_ACTIONS = 2
        const val ID_RELOAD = 1000
        const val ID_DESKTOP = 1001
        const val ID_FULLSCREEN = 1002
        const val ID_SITES = 1003
        const val ID_DIAGNOSTICS = 1004
        const val ID_UPDATES = 1005
        const val ID_AUTO_UPDATES = 1006
        const val ID_OFFLINE = 1007
        const val ID_SCROLL_LOCK = 1008
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val PREWARM_DELAY_MS = 8_000L

        /** Chrome on desktop Linux; keeps the configurators in their wide layout. */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        const val DESKTOP_SCALE_PERCENT = 50

        /** Survives activity recreation; see the pendingCreate/pendingOpen note. */
        private var pendingCreateRequest: ((Uri?) -> Unit)? = null
        private var pendingOpenRequest: ((Uri?) -> Unit)? = null

        const val CHROME_CHANNEL = "AndroidBrowserChrome"
        const val CHROME_ASSET = "bridge/chrome.js"
    }
}
