package com.aatorque.prefs

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.aatorque.datastore.UserPreference
import com.aatorque.stats.NotiService
import com.aatorque.stats.TorqueRefresher
import com.aatorque.stats.TorqueServiceWrapper
import com.aatorque.stats.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections

class SettingsFragment : PreferenceFragmentCompat() {
    lateinit var numScreensPref: EditTextPreference
    lateinit var dashboardsCat: PreferenceCategory
    lateinit var backgroundPref: ImageListPreference
    lateinit var themePref: ImageListPreference
    lateinit var fontPref: ImageListPreference
    lateinit var centerGaugeLargePref: CheckBoxPreference
    lateinit var minMaxBelowPref: CheckBoxPreference
    lateinit var mediaBgPref: CheckBoxPreference
    lateinit var opacityPref: SeekBarPreference
    lateinit var darkenArtPref: SeekBarPreference
    lateinit var blurArtPref: SeekBarPreference
    lateinit var refreshIntervalPref: SeekBarPreference
    lateinit var barPidPref: ListPreference
    lateinit var barTitlePref: EditTextPreference
    private var barTorqueBound = false
    private var savedBarPid = "0104"

    private val barTorqueConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val torqueService = (service as TorqueServiceWrapper.LocalBinder).getService()
            torqueService.loadPidInformation(false) { pids ->
                activity?.runOnUiThread {
                    val values = pids.map { "torque_${it.first}" }.toTypedArray()
                    val names = pids.map { it.second[0] }.toTypedArray()
                    barPidPref.entries = names
                    barPidPref.entryValues = values
                    val wanted = "torque_$savedBarPid"
                    barPidPref.value = if (values.contains(wanted)) wanted else values.firstOrNull() ?: ""
                    barPidPref.isEnabled = values.isNotEmpty()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            barTorqueBound = false
        }
    }
    lateinit var barMinPref: EditTextPreference
    lateinit var barMaxPref: EditTextPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferencesName = null
        dashboardsCat = findPreference("dashboardsCat")!!
        numScreensPref = findPreference("dashboardCount")!!
        backgroundPref = findPreference("selectedBackground")!!
        themePref = findPreference("selectedTheme")!!
        fontPref = findPreference("selectedFont")!!
        centerGaugeLargePref = findPreference("centerGaugeLarge")!!
        minMaxBelowPref = findPreference("minMaxBelow")!!
        mediaBgPref = findPreference("mediaBg")!!
        opacityPref = findPreference("gaugeOpacity")!!
        blurArtPref = findPreference("blurArtwork")!!
        darkenArtPref = findPreference("darkenArtwork")!!
        refreshIntervalPref = findPreference("refreshIntervalMs")!!
        barPidPref = findPreference("barPid")!!
        barTitlePref = findPreference("barTitle")!!
        barMinPref = findPreference("barMin")!!
        barMaxPref = findPreference("barMax")!!
        val legacyBarSettings = requireContext().getSharedPreferences("horizontal_bar_settings", android.content.Context.MODE_PRIVATE)
        barPidPref.isEnabled = false
        barPidPref.summary = "Loading available Torque PIDs..."
        barPidPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        barMinPref.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        barMaxPref.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }

        lifecycleScope.launch {
            var settings = requireContext().dataStore.data.first()
            if (settings.horizontalBarPid.isEmpty() && legacyBarSettings.contains("pid")) {
                val legacyPid = legacyBarSettings.getString("pid", "0104") ?: "0104"
                val legacyTitle = legacyBarSettings.getString("title", "PID $legacyPid") ?: "PID $legacyPid"
                val legacyMin = legacyBarSettings.getString("min", "0") ?: "0"
                val legacyMax = legacyBarSettings.getString("max", "100") ?: "100"
                settings = requireContext().dataStore.updateData { current ->
                    if (current.horizontalBarPid.isNotEmpty()) current
                    else current.toBuilder()
                        .setHorizontalBarPid(legacyPid.removePrefix("torque_").uppercase())
                        .setHorizontalBarTitle(legacyTitle)
                        .setHorizontalBarMin(legacyMin)
                        .setHorizontalBarMax(legacyMax)
                        .build()
                }
            }
            savedBarPid = settings.horizontalBarPid.ifBlank { "0104" }.removePrefix("torque_").uppercase()
            barTitlePref.text = settings.horizontalBarTitle.ifBlank { "PID ${savedBarPid}" }
            barMinPref.text = settings.horizontalBarMin.ifBlank { "0" }
            barMaxPref.text = settings.horizontalBarMax.ifBlank { "100" }
            barTitlePref.summary = "Current: ${barTitlePref.text}"
            barMinPref.summary = "Current: ${barMinPref.text}"
            barMaxPref.summary = "Current: ${barMaxPref.text}"
        }

        barTitlePref.setOnPreferenceChangeListener { pref, newValue ->
            val title = (newValue as String).trim()
            if (title.isEmpty()) return@setOnPreferenceChangeListener false
            lifecycleScope.launch {
                requireContext().dataStore.updateData { it.toBuilder().setHorizontalBarTitle(title).build() }
            }
            pref.summary = "Current: $title"
            true
        }
        barPidPref.setOnPreferenceChangeListener { _, newValue ->
            val pid = (newValue as String).removePrefix("torque_").uppercase()
            if (pid.isEmpty()) return@setOnPreferenceChangeListener false
            savedBarPid = pid
            lifecycleScope.launch {
                requireContext().dataStore.updateData { it.toBuilder().setHorizontalBarPid(pid).build() }
            }
            true
        }
        barMinPref.setOnPreferenceChangeListener { pref, newValue ->
            val minText = (newValue as String).trim()
            val min = minText.toDoubleOrNull() ?: return@setOnPreferenceChangeListener false
            val max = barMaxPref.text?.toDoubleOrNull() ?: 100.0
            if (!min.isFinite() || min >= max) return@setOnPreferenceChangeListener false
            lifecycleScope.launch {
                requireContext().dataStore.updateData { it.toBuilder().setHorizontalBarMin(minText).build() }
            }
            pref.summary = "Current: $minText"
            true
        }
        barMaxPref.setOnPreferenceChangeListener { pref, newValue ->
            val maxText = (newValue as String).trim()
            val max = maxText.toDoubleOrNull() ?: return@setOnPreferenceChangeListener false
            val min = barMinPref.text?.toDoubleOrNull() ?: 0.0
            if (!max.isFinite() || max <= min) return@setOnPreferenceChangeListener false
            lifecycleScope.launch {
                requireContext().dataStore.updateData { it.toBuilder().setHorizontalBarMax(maxText).build() }
            }
            pref.summary = "Current: $maxText"
            true
        }
        barTorqueBound = TorqueServiceWrapper.runStartIntent(requireContext(), barTorqueConnection)
        themePref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        fontPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        backgroundPref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        numScreensPref.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        numScreensPref.setOnPreferenceChangeListener { _, newValue ->
            val intVal = (newValue as String).toInt()
            if (intVal in 1..10) {
                lifecycleScope.launch {
                    requireContext().dataStore.updateData { currentSettings ->
                        var bldr = currentSettings.toBuilder()
                        if (bldr.screensCount > intVal) {
                            val keeping = bldr.screensList.subList(0, intVal)
                            bldr = bldr.clearScreens().addAllScreens(keeping)
                        } else if (currentSettings.screensCount < intVal) {
                            val newItms = Collections.nCopies(
                                intVal - currentSettings.screensCount,
                                UserPreferenceSerializer.defaultScreen.build()
                            )
                            bldr = bldr.addAllScreens(newItms.toMutableList())
                            Timber.i("${newItms.size} added, ${intVal} specified")
                        }
                        return@updateData bldr.build()
                    }
                }
                return@setOnPreferenceChangeListener true
            }
            return@setOnPreferenceChangeListener false
        }

        themePref.setOnPreferenceChangeListener {
            _, newValue ->
            updateDatastorePref {
                it.setSelectedTheme(newValue as String)
            }
            return@setOnPreferenceChangeListener true
        }
        fontPref.setOnPreferenceChangeListener {
                preference, newValue ->
            updateDatastorePref {
                it.setSelectedFont(newValue as String)
            }
            Timber.i("Setting font $newValue")
            return@setOnPreferenceChangeListener true
        }
        backgroundPref.setOnPreferenceChangeListener {
                preference, newValue ->
            updateDatastorePref {
                it.setSelectedBackground(newValue as String)
            }
            return@setOnPreferenceChangeListener true
        }
        centerGaugeLargePref.setOnPreferenceChangeListener {
                preference, newValue ->
            updateDatastorePref {
                it.setCenterGaugeLarge(newValue as Boolean)
            }
            return@setOnPreferenceChangeListener true
        }
        minMaxBelowPref.setOnPreferenceChangeListener { preference, newValue ->
            updateDatastorePref {
                it.setMinMaxBelow(newValue as Boolean)
            }
            return@setOnPreferenceChangeListener true
        }
        mediaBgPref.setOnPreferenceChangeListener { preference, newValue ->
            updateDatastorePref {
                if (newValue as Boolean && !NotiService.isNotificationAccessEnabled(requireContext())) {
                    startActivity(
                        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                putExtra(
                                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                    NotiService::class.java.canonicalName
                                )
                            }
                        }
                    )
                }
                it.setAlbumArt(newValue)
            }
            return@setOnPreferenceChangeListener true
        }
        opacityPref.setOnPreferenceChangeListener { preference, newValue ->
            updateDatastorePref {
                it.setOpacity(newValue as Int)
            }
            return@setOnPreferenceChangeListener true
        }
        darkenArtPref.setOnPreferenceChangeListener { preference, newValue ->
            updateDatastorePref {
                it.setDarkenArt(newValue as Int)
            }
            return@setOnPreferenceChangeListener true
        }
        blurArtPref.setOnPreferenceChangeListener { preference, newValue ->
            updateDatastorePref {
                it.setBlurArt(newValue as Int)
            }
            return@setOnPreferenceChangeListener true
        }
        blurArtPref.isVisible = Build.VERSION.SDK_INT >= 31

        refreshIntervalPref.setOnPreferenceChangeListener { _, newValue ->
            updateDatastorePref {
                it.setRefreshIntervalMs(newValue as Int)
            }
            return@setOnPreferenceChangeListener true
        }
        
        numScreensPref.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }

        lifecycleScope.launch {
            requireContext().dataStore.data.collect {
                themePref.value = it.selectedTheme
                fontPref.value = it.selectedFont
                backgroundPref.value = it.selectedBackground
                centerGaugeLargePref.isChecked = it.centerGaugeLarge
                minMaxBelowPref.isChecked = it.minMaxBelow
                mediaBgPref.isChecked =
                    it.albumArt && NotiService.isNotificationAccessEnabled(requireContext())
                opacityPref.value = if (it.opacity == 0) 100 else it.opacity
                blurArtPref.value = it.blurArt
                darkenArtPref.value = it.darkenArt
                val refreshInterval =
                    if (it.refreshIntervalMs == 0) 300 else it.refreshIntervalMs
                
                refreshIntervalPref.value = refreshInterval
                TorqueRefresher.REFRESH_INTERVAL = refreshInterval.toLong()

                savedBarPid = it.horizontalBarPid.ifBlank { "0104" }.removePrefix("torque_").uppercase()
                barPidPref.value = "torque_$savedBarPid"
                barTitlePref.text = it.horizontalBarTitle.ifBlank { "PID $savedBarPid" }
                barMinPref.text = it.horizontalBarMin.ifBlank { "0" }
                barMaxPref.text = it.horizontalBarMax.ifBlank { "100" }
                barTitlePref.summary = "Current: ${barTitlePref.text}"
                barMinPref.summary = "Current: ${barMinPref.text}"
                barMaxPref.summary = "Current: ${barMaxPref.text}"
            }
        }
        lifecycleScope.launch {
            requireContext().dataStore.data.distinctUntilChangedBy{
                it.screensList.map { it.title }
            }.collect { userPreference ->
                numScreensPref.text = userPreference.screensCount.toString()
                dashboardsCat.removeAll()
                userPreference.screensList.forEachIndexed {
                        i, screen ->
                    dashboardsCat.addPreference(Preference(requireContext()).also {
                        it.title = requireContext().getString(
                            R.string.pref_data_element_settings,
                             i + 1
                        )
                        it.key = "dashboard_$i"
                        it.fragment = SettingsDashboard::class.java.canonicalName
                        it.summary = screen.title
                    })
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun updateDatastorePref(updateBuilder: (obj: UserPreference.Builder) -> UserPreference.Builder) {
        GlobalScope.launch(Dispatchers.IO) {
            requireContext().dataStore.updateData { currentSettings ->
                updateBuilder(currentSettings.toBuilder()).build()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)
    }

    override fun onStart() {
        super.onStart()
        (requireActivity() as SettingsActivity).supportActionBar!!.subtitle = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (barTorqueBound) {
            try {
                requireContext().unbindService(barTorqueConnection)
            } catch (e: IllegalArgumentException) {
                Timber.e("Failed to unbind Torque PID service", e)
            }
            barTorqueBound = false
        }
    }
}
