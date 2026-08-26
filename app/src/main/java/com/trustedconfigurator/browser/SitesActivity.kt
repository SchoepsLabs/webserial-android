package com.trustedconfigurator.browser

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.trustedconfigurator.browser.bridge.BrowserSettings
import com.trustedconfigurator.browser.bridge.OriginPolicy
import com.trustedconfigurator.browser.bridge.SharedPreferencesSitePersistence
import com.trustedconfigurator.browser.bridge.Site
import com.trustedconfigurator.browser.bridge.SitePolicy
import com.trustedconfigurator.browser.databinding.ActivitySitesBinding

/**
 * Which sites exist, which of them may use USB, and whether they are kept
 * available offline.
 *
 * Sites can use USB, added ones included — a page may ask, and reaches nothing
 * until a device is picked in Android's own dialog. The switch here is how you
 * stop a site asking at all.
 */
class SitesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySitesBinding
    private lateinit var policy: SitePolicy
    private lateinit var settings: BrowserSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySitesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        settings = BrowserSettings(this)
        binding.offlineSwitch.isChecked = settings.offlinePrewarmEnabled
        binding.offlineSwitch.setOnCheckedChangeListener { _, checked ->
            settings.offlinePrewarmEnabled = checked
            if (!checked) return@setOnCheckedChangeListener
            // Switching it on is a request to do it, not permission for later.
            settings.lastPrewarm = 0L
            val origins = policy.sites().map { it.origin }
            OfflinePrewarm(this, settings).run(origins) { cached ->
                Snackbar.make(binding.root, getString(R.string.offline_done, cached), Snackbar.LENGTH_LONG).show()
            }
            Snackbar.make(binding.root, R.string.offline_on, Snackbar.LENGTH_SHORT).show()
        }
        supportActionBar?.setTitle(R.string.sites_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        policy = SitePolicy(SharedPreferencesSitePersistence(this))

        binding.addSite.setOnClickListener {
            val text = binding.newSite.text.toString()
            val origin = OriginPolicy.normalize(OriginPolicy.toUrl(text))
            if (origin == null) {
                Snackbar.make(binding.root, R.string.bad_address, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            policy.addSite(origin, usbEnabled = true)
            binding.newSite.setText("")
            render()
        }

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        binding.siteList.removeAllViews()
        policy.sites().forEach { site -> binding.siteList.addView(rowFor(site)) }
    }

    private fun rowFor(site: Site): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(
            TextView(this).apply {
                text = site.title
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@SitesActivity, R.color.chrome_text))
            },
        )
        labels.addView(
            TextView(this).apply {
                text = site.origin
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SitesActivity, R.color.chrome_hint))
            },
        )
        labels.addView(
            TextView(this).apply {
                text = getString(
                    if (site.usbEnabled) R.string.usb_on_summary else R.string.usb_off_summary,
                )
                textSize = 12f
                setTextColor(
                    ContextCompat.getColor(
                        this@SitesActivity,
                        if (site.usbEnabled) R.color.usb_on else R.color.usb_off,
                    ),
                )
            },
        )
        labels.setOnLongClickListener {
            if (!site.builtIn) confirmRemove(site)
            true
        }
        row.addView(labels)

        row.addView(
            MaterialSwitch(this).apply {
                isChecked = site.usbEnabled
                setOnClickListener {
                    if (isChecked) {
                        policy.setUsbEnabled(site.origin, true)
                        render()
                    } else {
                        // Bounce back until the warning is read. Switching a site
                        // off stops every configurator on it working, and the page
                        // reports that as its own browser being unsupported — so
                        // it has to be a decision, not a stray tap.
                        isChecked = true
                        confirmDisable(site)
                    }
                }
            },
        )

        return row
    }

    private fun confirmDisable(site: Site) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.disable_usb_title, site.origin))
            .setMessage(R.string.disable_usb_message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
            .setOnCancelListener { render() }
            .setPositiveButton(R.string.disable_usb_confirm) { _, _ ->
                policy.setUsbEnabled(site.origin, false)
                render()
            }
            .show()
    }

    private fun confirmRemove(site: Site) {
        AlertDialog.Builder(this)
            .setTitle(site.origin)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove_site) { _, _ ->
                policy.removeSite(site.origin)
                render()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
