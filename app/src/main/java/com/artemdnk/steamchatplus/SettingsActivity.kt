package com.artemdnk.steamchatplus

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.artemdnk.steamchatplus.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var pageDirty = false

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>(Prefs.PAGE_WIDTH)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }
            findPreference<EditTextPreference>(Prefs.CUSTOM_CSS)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                it.minLines = 4
                it.typeface = android.graphics.Typeface.MONOSPACE
            }
            findPreference<Preference>("battery")?.setOnPreferenceClickListener {
                requestIgnoreBattery(); true
            }
            findPreference<Preference>("logout")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_logout)
                    .setMessage(R.string.pref_logout_confirm)
                    .setPositiveButton(R.string.pref_logout) { _, _ ->
                        WebHolder.logout()
                        requireActivity().finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<Preference>("version")?.summary =
                "${BuildConfig.VERSION_NAME} · WebView ${webViewVersion()}"
            updateBatterySummary()
        }

        override fun onResume() {
            super.onResume()
            PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)
            updateBatterySummary()
        }

        override fun onPause() {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
            if (pageDirty) {
                pageDirty = false
                WebHolder.applySettings()
            }
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
            when (key ?: return) {
                Prefs.KEEP_ALIVE -> SessionService.sync(requireContext())
                in Prefs.PAGE_KEYS -> pageDirty = true
            }
        }

        private fun webViewVersion(): String =
            androidx.webkit.WebViewCompat.getCurrentWebViewPackage(requireContext())?.versionName ?: "?"

        private fun ignoringBattery(): Boolean {
            val pm = requireContext().getSystemService(PowerManager::class.java)
            return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        }

        private fun updateBatterySummary() {
            findPreference<Preference>("battery")?.summary = getString(
                if (ignoringBattery()) R.string.pref_battery_on else R.string.pref_battery_off
            )
        }

        @SuppressLint("BatteryLife")
        private fun requestIgnoreBattery() {
            val ctx = requireContext()
            val intent = if (!ignoringBattery()) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            } else {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
            runCatching { startActivity(intent) }
        }
    }
}
