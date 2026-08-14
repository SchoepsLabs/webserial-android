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
import com.trustedconfigurator.browser.bridge.OriginPolicy
import com.trustedconfigurator.browser.bridge.SharedPreferencesSitePersistence
import com.trustedconfigurator.browser.bridge.Site
import com.trustedconfigurator.browser.bridge.SitePolicy
import com.trustedconfigurator.browser.databinding.ActivitySitesBinding

/**
 * Which sites exist and which of them may use USB.
 *
 * The built-in configurators ship with USB on. Anything added here starts off,
 * and turning it on shows the same warning as the in-page toggle — a site never
 * gains native USB without the user reading what that means.
 */
class SitesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySitesBinding
    private lateinit var policy: SitePolicy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySitesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
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
            policy.addSite(origin, usbEnabled = false)
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
                        // Bounce back until the warning is accepted, so a stray
                        // tap cannot hand a site USB access.
                        isChecked = false
                        confirmEnable(site)
                    } else {
                        policy.setUsbEnabled(site.origin, false)
                        render()
                    }
                }
            },
        )

        return row
    }

    private fun confirmEnable(site: Site) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enable_usb_title, site.origin))
            .setMessage(R.string.enable_usb_message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
            .setOnCancelListener { render() }
            .setPositiveButton(R.string.enable_usb_confirm) { _, _ ->
                policy.setUsbEnabled(site.origin, true)
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
