package io.github.nitsuya.aa.display.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.topjohnwu.superuser.Shell
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.GoogleMapsOnAaManager
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.util.WazeOnAaManager
import io.github.nitsuya.template.bases.getAttr

class MainActivity :
    BaseActivity<ActivityMainBinding>(
        ActivityMainBinding::class.java,
        Config.NO_BACK,
        Config.LAYOUT_MATCH_HORI
    ),
    MenuProvider {
    companion object {
        const val TAG = "AADisplay_MainActivity"
    }

    private data class LauncherOption(
        val packageName: String,
        val label: String
    ) {
        val displayName: String
            get() = if (label.equals(packageName, ignoreCase = true)) {
                packageName
            } else {
                "$label ($packageName)"
            }
    }

    private data class DelayOption(
        val seconds: Int,
        val label: String
    )

    private data class DensityOption(
        val dpi: Int,
        val label: String
    )

    private data class MinWidthOption(
        val width: Int,
        val label: String
    )

    private val appConfig by lazy {
        SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName)
    }

    private var launcherOptions: List<LauncherOption> = emptyList()
    private var launcherDisplayToPackage: Map<String, String> = emptyMap()
    private var delayOptions: List<DelayOption> = emptyList()
    private var delayDisplayToSeconds: Map<String, Int> = emptyMap()
    private var densityOptions: List<DensityOption> = emptyList()
    private var densityDisplayToDpi: Map<String, Int> = emptyMap()
    private var minWidthOptions: List<MinWidthOption> = emptyList()
    private var minWidthDisplayToWidth: Map<String, Int> = emptyMap()
    private var defaultHomePackage: String? = null
    private var savedLauncherPackage: String? = null
    private var savedDelayDestroyTime: Int = 180
    private var savedDisplayDensity: Int = 0
    private var savedLecoMinWidth: Int = 0
    private var savedAutoOpen: Boolean = false
    private var savedDisableWazeOnAa: Boolean = false
    private var savedDisableGoogleMapsOnAa: Boolean = false
    private var savedScreenOffReplaceLockScreen: Boolean = false
    private var savedHideAaRail: Boolean = true
    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityMainBinding.inflate(LayoutInflater.from(this))
        super.onCreate(savedInstanceState)
        SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        addMenuProvider(this, this)
    }

    override fun initViews() {
        super.initViews()
        setupSettingControls()
    }

    @SuppressLint("SetTextI18n")
    override fun initData() {
        val buildTime = CoreApi.buildTime
        Log.d(TAG, "buildtime: $buildTime ${BuildConfig.BUILD_TIME}")
        when (buildTime) {
            0L -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_error_outline_24)
                baseBinding.tvActive.setText(R.string.not_activated)
                baseBinding.tvVersion.text = ""
                val colorError = android.R.attr.colorError
                val colorOnError = theme.getAttr(com.google.android.material.R.attr.colorOnError).data
                baseBinding.mcvStatus.setCardBackgroundColor(colorError)
                baseBinding.mcvStatus.outlineAmbientShadowColor = colorError
                baseBinding.mcvStatus.outlineSpotShadowColor = colorError
                baseBinding.tvActive.setTextColor(colorOnError)
                baseBinding.tvVersion.setTextColor(colorOnError)
            }

            BuildConfig.BUILD_TIME -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_round_check_circle_24)
                baseBinding.tvActive.setText(R.string.activated)
                baseBinding.tvVersion.text = CoreApi.versionName
            }

            else -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_warning_amber_24)
                baseBinding.tvActive.setText(R.string.need_reboot)
                baseBinding.tvVersion.text =
                    "system: ${CoreApi.versionName}\nmodule: ${BuildConfig.VERSION_NAME}"
                baseBinding.mcvStatus.setCardBackgroundColor(
                    MaterialColors.harmonizeWithPrimary(this, getColor(R.color.color_warning))
                )
                baseBinding.mcvStatus.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.need_reboot)
                        .setPositiveButton(R.string.reboot) { _, _ ->
                            Shell.getShell().newJob().add("reboot").exec()
                        }
                        .show()
                }
            }
        }

        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            baseBinding.systemVersion.text =
                "${Build.VERSION.CODENAME} Preview (API ${Build.VERSION.SDK_INT})"
        } else {
            baseBinding.systemVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        baseBinding.rootPrivilege.text = if (Shell.getShell().isRoot) "YES" else "NO"
        refreshSettingControls()
    }

    override fun onResume() {
        super.onResume()
        refreshSettingControls()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.github -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/Stashboy/AADisplay".toUri()
                })
                true
            }

            else -> false
        }
    }

    private fun setupSettingControls() {
        baseBinding.switchAutoOpen.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.switchDisableWazeOnAa.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.switchDisableGoogleMapsOnAa.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.switchScreenOffReplaceLockScreen.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.switchHideAaRail.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.actvLauncherPackage.apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            threshold = 0
            isCursorVisible = false
            isLongClickable = false
            showSoftInputOnFocus = false
            setTextIsSelectable(false)
            setOnClickListener {
                showAllLauncherOptions()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showAllLauncherOptions()
                }
                if (!hasFocus) {
                    validateLauncherSelection()
                }
            }
            setOnItemClickListener { _, _, _, _ ->
                validateLauncherSelection()
                updateSaveButtonState()
            }
        }

        baseBinding.actvDelayDestroyTime.apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            threshold = 0
            isCursorVisible = false
            isLongClickable = false
            showSoftInputOnFocus = false
            setTextIsSelectable(false)
            setOnClickListener {
                showAllDelayOptions()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showAllDelayOptions()
                }
                if (!hasFocus) {
                    validateDelaySelection(showError = true)
                }
            }
            setOnItemClickListener { _, _, _, _ ->
                validateDelaySelection(showError = false)
                updateSaveButtonState()
            }
        }

        // Both fields accept a typed value as well, the drop down only offers the common ones.
        baseBinding.actvDisplayDensity.apply {
            setOnItemClickListener { _, _, _, _ ->
                validateDensitySelection(showError = false)
                updateSaveButtonState()
            }
            // Typing does not go through the item click listener, so the Save button would not notice
            // a value that was entered by hand.
            doAfterTextChanged {
                validateDensitySelection(showError = false)
                updateSaveButtonState()
            }
        }

        baseBinding.actvLecoMinWidth.apply {
            setOnItemClickListener { _, _, _, _ ->
                validateMinWidthSelection(showError = false)
                updateSaveButtonState()
            }
            doAfterTextChanged {
                validateMinWidthSelection(showError = false)
                updateSaveButtonState()
            }
        }

        // The settings activity is translucent and an overlay (the launcher draws in its own window on
        // top of the phone screen) can take the focus without warning, which drops a value the user has
        // just typed. Re-checking the fields whenever the screen gets the focus back keeps the Save
        // button in sync with what the fields actually contain.
        baseBinding.ll.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) updateSaveButtonState()
        }

        baseBinding.btnSave.setOnClickListener {
            persistSettings()
        }
    }

    private fun refreshSettingControls() {
        savedAutoOpen = AADisplayConfig.AutoOpen.get(appConfig)
        savedDisableWazeOnAa = AADisplayConfig.DisableWazeOnAa.get(appConfig)
        savedDisableGoogleMapsOnAa = AADisplayConfig.DisableGoogleMapsOnAa.get(appConfig)
        savedScreenOffReplaceLockScreen = AADisplayConfig.ScreenOffReplaceLockScreen.get(appConfig)
        savedHideAaRail = AADisplayConfig.HideAaRail.get(appConfig)
        savedLauncherPackage = AADisplayConfig.LauncherPackage.get(appConfig)?.trim().orEmpty()
        savedDelayDestroyTime = AADisplayConfig.DelayDestroyTime.get(appConfig)
        savedDisplayDensity = AADisplayConfig.VirtualDisplayDpi.get(appConfig)
        savedLecoMinWidth = AADisplayConfig.LecoMinWidth.get(appConfig)
        baseBinding.switchAutoOpen.isChecked = savedAutoOpen
        baseBinding.switchDisableWazeOnAa.isChecked = savedDisableWazeOnAa
        baseBinding.switchDisableGoogleMapsOnAa.isChecked = savedDisableGoogleMapsOnAa
        baseBinding.switchScreenOffReplaceLockScreen.isChecked = savedScreenOffReplaceLockScreen
        baseBinding.switchHideAaRail.isChecked = savedHideAaRail

        detectLauncherEnvironment()
        bindLauncherDropdown()
        bindDelayDropdown()
        bindDensityDropdown()
        bindMinWidthDropdown()

        val configuredLauncher = savedLauncherPackage.orEmpty()
        val selected = launcherOptions.firstOrNull { it.packageName == configuredLauncher }
            ?: launcherOptions.firstOrNull { it.packageName != defaultHomePackage }
            ?: launcherOptions.firstOrNull()
        if (selected != null) {
            baseBinding.actvLauncherPackage.setText(selected.displayName, false)
        } else {
            baseBinding.actvLauncherPackage.setText("", false)
        }

        val selectedDelay = delayOptions.firstOrNull { it.seconds == savedDelayDestroyTime }
            ?: delayOptions.firstOrNull()
        if (selectedDelay != null) {
            baseBinding.actvDelayDestroyTime.setText(selectedDelay.label, false)
        } else {
            baseBinding.actvDelayDestroyTime.setText("", false)
        }

        val selectedDensity = densityOptions.firstOrNull { it.dpi == savedDisplayDensity }
        if (selectedDensity != null) {
            baseBinding.actvDisplayDensity.setText(selectedDensity.label, false)
        } else {
            baseBinding.actvDisplayDensity.setText(if (savedDisplayDensity == 0) "" else "$savedDisplayDensity dpi", false)
        }

        val selectedMinWidth = minWidthOptions.firstOrNull { it.width == savedLecoMinWidth }
        if (selectedMinWidth != null) {
            baseBinding.actvLecoMinWidth.setText(selectedMinWidth.label, false)
        } else {
            baseBinding.actvLecoMinWidth.setText(
                if (savedLecoMinWidth == 0) getString(R.string.leco_min_width_from_theme) else "$savedLecoMinWidth",
                false
            )
        }

        validateLauncherSelection()
        validateDelaySelection(showError = false)
        validateDensitySelection(showError = false)
        validateMinWidthSelection(showError = false)
        updateSaveButtonState()
    }

    private fun detectLauncherEnvironment() {
        val pm = packageManager
        launcherOptions = queryLauncherOptions(pm)

        defaultHomePackage = resolveDefaultHomePackageFallback(pm)

        val homeDisplay = defaultHomePackage?.let { pkg ->
            launcherOptions.firstOrNull { it.packageName == pkg }?.displayName
                ?: runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    "${pm.getApplicationLabel(appInfo)} ($pkg)"
                }.getOrDefault(pkg)
        }

        baseBinding.tvDefaultHome.text = if (!homeDisplay.isNullOrBlank()) {
            getString(R.string.default_home_detected, homeDisplay)
        } else {
            getString(R.string.default_home_unknown)
        }

    }

    private fun bindLauncherDropdown() {
        launcherDisplayToPackage = launcherOptions.associate { it.displayName to it.packageName }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            launcherOptions.map { it.displayName }
        )
        baseBinding.actvLauncherPackage.setAdapter(adapter)
    }

    private fun bindDelayDropdown() {
        delayOptions = listOf(
            DelayOption(60, getString(R.string.delay_60_seconds)),
            DelayOption(120, getString(R.string.delay_120_seconds)),
            DelayOption(180, getString(R.string.delay_180_seconds))
        )
        delayDisplayToSeconds = delayOptions.associate { it.label to it.seconds }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            delayOptions.map { it.label }
        )
        baseBinding.actvDelayDestroyTime.setAdapter(adapter)
    }

    private fun bindDensityDropdown() {
        densityOptions = listOf(
            DensityOption(0, getString(R.string.display_density_from_aa)),
            DensityOption(160, "160 dpi"),
            DensityOption(200, "200 dpi"),
            DensityOption(213, "213 dpi"),
            DensityOption(240, "240 dpi"),
            DensityOption(280, "280 dpi"),
            DensityOption(320, "320 dpi")
        )
        densityDisplayToDpi = densityOptions.associate { it.label to it.dpi }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            densityOptions.map { it.label }
        )
        baseBinding.actvDisplayDensity.setAdapter(adapter)
    }

    private fun bindMinWidthDropdown() {
        minWidthOptions = listOf(0, 200, 250, 300, 350, 400, 450, 500, 550, 600, 650, 700, 750, 800, 900, 1000)
            .map { width ->
                MinWidthOption(
                    width,
                    if (width == 0) getString(R.string.leco_min_width_from_theme) else width.toString()
                )
            }
        minWidthDisplayToWidth = minWidthOptions.associate { it.label to it.width }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            minWidthOptions.map { it.label }
        )
        baseBinding.actvLecoMinWidth.setAdapter(adapter)
    }

    private fun showAllLauncherOptions() {
        @Suppress("UNCHECKED_CAST")
        val adapter = baseBinding.actvLauncherPackage.adapter as? ArrayAdapter<String> ?: return
        // Force-reset filtering so the popup always shows all launcher choices.
        adapter.filter.filter(null)
        baseBinding.actvLauncherPackage.showDropDown()
    }

    private fun showAllDelayOptions() {
        @Suppress("UNCHECKED_CAST")
        val adapter = baseBinding.actvDelayDestroyTime.adapter as? ArrayAdapter<String> ?: return
        adapter.filter.filter(null)
        baseBinding.actvDelayDestroyTime.showDropDown()
    }

    private fun queryLauncherOptions(pm: PackageManager): List<LauncherOption> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pmCandidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        }

        val pmOptions = pmCandidates
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkg = activityInfo.packageName?.trim().orEmpty()
                val cls = activityInfo.name?.trim().orEmpty()
                if (!isLauncherCandidateAllowed(pkg, cls)) return@mapNotNull null
                val label = resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                LauncherOption(pkg, label)
            }
            .distinctBy { it.packageName }

        val mergedByPackage = linkedMapOf<String, LauncherOption>()
        pmOptions.forEach { mergedByPackage[it.packageName] = it }

        queryLauncherPackagesViaShell().forEach { pkg ->
            if (mergedByPackage.containsKey(pkg)) return@forEach
            val label = runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString().trim().ifBlank { pkg }
            }.getOrDefault(pkg)
            mergedByPackage[pkg] = LauncherOption(pkg, label)
        }

        return mergedByPackage.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun isLauncherCandidateAllowed(pkg: String, cls: String): Boolean {
        if (pkg.isBlank()) return false
        if (
            pkg == "android" ||
            pkg == "com.android.internal.app" ||
            pkg == "com.android.settings" ||
            cls.contains("FallbackHome", ignoreCase = true) ||
            cls.contains("ResolverActivity", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private fun queryLauncherPackagesViaShell(): List<String> {
        if (!Shell.getShell().isRoot) return emptyList()

        val output = runCatching {
            Shell.getShell().newJob()
                .add("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .exec()
                .out
        }.getOrDefault(emptyList())

        val packageNames = linkedSetOf<String>()
        for (line in output) {
            val trimmed = line.trim()
            if (!trimmed.contains("/")) continue
            val parts = trimmed.split("/", limit = 2)
            if (parts.size != 2) continue
            val pkg = parts[0].trim()
            val cls = parts[1].trim()
            if (!isLauncherCandidateAllowed(pkg, cls)) continue
            packageNames.add(pkg)
        }
        return packageNames.toList()
    }

    private fun resolveDefaultHomePackageFallback(pm: PackageManager): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } ?: return null

        val pkg = resolveInfo.activityInfo?.packageName ?: return null
        val cls = resolveInfo.activityInfo?.name.orEmpty()
        return if (
            pkg == "android" ||
            pkg == "com.android.internal.app" ||
            cls.contains("ResolverActivity", ignoreCase = true)
        ) {
            null
        } else {
            pkg
        }
    }

    private fun resolveSelectedLauncherPackage(): String? {
        val raw = baseBinding.actvLauncherPackage.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        launcherDisplayToPackage[raw]?.let { return it }
        return launcherOptions.firstOrNull { it.packageName == raw }?.packageName
    }

    private fun resolveSelectedDelaySeconds(): Int? {
        val raw = baseBinding.actvDelayDestroyTime.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        delayDisplayToSeconds[raw]?.let { return it }
        return delayOptions.firstOrNull { it.label == raw }?.seconds
    }

    private fun resolveSelectedDisplayDensity(): Int? {
        val raw = baseBinding.actvDisplayDensity.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        densityDisplayToDpi[raw]?.let { return it }
        parseTypedNumber(raw)?.let { return it }
        return densityOptions.firstOrNull { it.label == raw }?.dpi
    }

    private fun resolveSelectedMinWidth(): Int? {
        val raw = baseBinding.actvLecoMinWidth.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        minWidthDisplayToWidth[raw]?.let { return it }
        parseTypedNumber(raw)?.let { return it }
        return minWidthOptions.firstOrNull { it.label == raw }?.width
    }

    /** Reads a value the user typed, e.g. "221", "221 dpi" or "550 px". */
    private fun parseTypedNumber(raw: String): Int? {
        val digits = raw.takeWhile { it.isDigit() }
        return digits.takeIf { it.isNotEmpty() }?.toIntOrNull()
    }

    private fun validateLauncherSelection(): Boolean {
        val selectedPackage = resolveSelectedLauncherPackage()

        if (launcherOptions.isEmpty()) {
            baseBinding.tilLauncherPackage.error = getString(R.string.launcher_list_missing)
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        if (launcherOptions.size <= 1) {
            baseBinding.tilLauncherPackage.error = getString(R.string.single_launcher_warning)
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        if (selectedPackage.isNullOrBlank()) {
            baseBinding.tilLauncherPackage.error = getString(R.string.default_launch_package_empty)
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        if (selectedPackage == defaultHomePackage) {
            val errorText = getString(R.string.package_same_error)
            baseBinding.tilLauncherPackage.error = errorText
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        baseBinding.tilLauncherPackage.error = null
        applyLaunchPackageValidState()
        showValidationMessage(getString(R.string.package_validation_ok), true)
        return true
    }

    private fun validateDelaySelection(showError: Boolean): Boolean {
        val value = resolveSelectedDelaySeconds()
        val valid = value != null && delayOptions.any { it.seconds == value }
        if (!valid && showError) {
            baseBinding.tilDelayDestroyTime.error = getString(R.string.delay_destroy_time_invalid)
        } else if (valid) {
            baseBinding.tilDelayDestroyTime.error = null
        }
        return valid
    }

    private fun validateDensitySelection(showError: Boolean): Boolean {
        val value = resolveSelectedDisplayDensity()
        val valid = value != null && (value == 0 || value > 50)
        if (!valid && showError) {
            baseBinding.tilDisplayDensity.error = getString(R.string.display_density_invalid)
        } else if (valid) {
            baseBinding.tilDisplayDensity.error = null
        }
        return valid
    }

    private fun validateMinWidthSelection(showError: Boolean): Boolean {
        val value = resolveSelectedMinWidth()
        val valid = value != null && value >= 0
        if (!valid && showError) {
            baseBinding.tilLecoMinWidth.error = getString(R.string.leco_min_width_invalid)
        } else if (valid) {
            baseBinding.tilLecoMinWidth.error = null
        }
        return valid
    }

    private fun persistSettings() {
        val launcherValid = validateLauncherSelection()
        val delayValid = validateDelaySelection(showError = true)
        val densityValid = validateDensitySelection(showError = true)
        val minWidthValid = validateMinWidthSelection(showError = true)
        if (!launcherValid || !delayValid || !densityValid || !minWidthValid) {
            updateSaveButtonState()
            return
        }

        val launcherPackage = resolveSelectedLauncherPackage() ?: return
        val delay = resolveSelectedDelaySeconds() ?: return
        val disableWazeOnAa = baseBinding.switchDisableWazeOnAa.isChecked
        val disableGoogleMapsOnAa = baseBinding.switchDisableGoogleMapsOnAa.isChecked
        val screenOffReplaceLockScreen = baseBinding.switchScreenOffReplaceLockScreen.isChecked
        val hideAaRail = baseBinding.switchHideAaRail.isChecked
        if (disableWazeOnAa != savedDisableWazeOnAa) {
            val applied = WazeOnAaManager.apply(disableWazeOnAa)
            if (!applied) {
                Toast.makeText(this, getString(R.string.disable_waze_on_aa_apply_failed), Toast.LENGTH_SHORT).show()
                baseBinding.switchDisableWazeOnAa.isChecked = savedDisableWazeOnAa
                updateSaveButtonState()
                return
            }
        }
        if (disableGoogleMapsOnAa != savedDisableGoogleMapsOnAa) {
            val applied = GoogleMapsOnAaManager.apply(disableGoogleMapsOnAa)
            if (!applied) {
                Toast.makeText(this, getString(R.string.disable_google_maps_on_aa_apply_failed), Toast.LENGTH_SHORT).show()
                baseBinding.switchDisableGoogleMapsOnAa.isChecked = savedDisableGoogleMapsOnAa
                updateSaveButtonState()
                return
            }
        }

        val settingsSaved = appConfig.edit()
            .putBoolean(AADisplayConfig.AutoOpen.key, baseBinding.switchAutoOpen.isChecked)
            .putBoolean(AADisplayConfig.DisableWazeOnAa.key, disableWazeOnAa)
            .putBoolean(AADisplayConfig.DisableGoogleMapsOnAa.key, disableGoogleMapsOnAa)
            .putBoolean(AADisplayConfig.ScreenOffReplaceLockScreen.key, screenOffReplaceLockScreen)
            .putBoolean(AADisplayConfig.HideAaRail.key, hideAaRail)
            .putString(AADisplayConfig.LauncherPackage.key, launcherPackage)
            .putString(AADisplayConfig.HomePackage.key, launcherPackage)
            .putString(AADisplayConfig.DelayDestroyTime.key, delay.toString())
            .putString(AADisplayConfig.VirtualDisplayDpi.key, (resolveSelectedDisplayDensity() ?: 0).toString())
            .putString(AADisplayConfig.LecoMinWidth.key, (resolveSelectedMinWidth() ?: 0).toString())
            .commit()

        val readableForHooks = SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        if (!settingsSaved || !readableForHooks) {
            Toast.makeText(this, getString(R.string.settings_saved_pref_access_failed), Toast.LENGTH_LONG).show()
            updateSaveButtonState()
            return
        }

        savedAutoOpen = baseBinding.switchAutoOpen.isChecked
        savedDisableWazeOnAa = disableWazeOnAa
        savedDisableGoogleMapsOnAa = disableGoogleMapsOnAa
        savedScreenOffReplaceLockScreen = screenOffReplaceLockScreen
        savedHideAaRail = hideAaRail
        savedLauncherPackage = launcherPackage
        savedDelayDestroyTime = delay
        savedDisplayDensity = resolveSelectedDisplayDensity() ?: 0
        savedLecoMinWidth = resolveSelectedMinWidth() ?: 0
        Toast.makeText(this, getString(R.string.settings_saved_successfully), Toast.LENGTH_SHORT).show()
        updateSaveButtonState()
    }

    private fun updateSaveButtonState() {
        val hasChanges = hasPendingChanges()
        val enabled = hasChanges &&
            validateLauncherSelection() &&
            validateDelaySelection(showError = false) &&
            validateDensitySelection(showError = false) &&
            validateMinWidthSelection(showError = false)
        baseBinding.btnSave.isEnabled = enabled
        baseBinding.btnSave.alpha = if (enabled) 1f else 0.6f
    }

    private fun hasPendingChanges(): Boolean {
        val currentAutoOpen = baseBinding.switchAutoOpen.isChecked
        val currentDisableWazeOnAa = baseBinding.switchDisableWazeOnAa.isChecked
        val currentDisableGoogleMapsOnAa = baseBinding.switchDisableGoogleMapsOnAa.isChecked
        val currentScreenOffReplaceLockScreen = baseBinding.switchScreenOffReplaceLockScreen.isChecked
        val currentHideAaRail = baseBinding.switchHideAaRail.isChecked
        val currentLauncher = resolveSelectedLauncherPackage()
        val currentDelay = resolveSelectedDelaySeconds()
        val currentDisplayDensity = resolveSelectedDisplayDensity()
        val currentMinWidth = resolveSelectedMinWidth()
        return currentAutoOpen != savedAutoOpen ||
            currentDisableWazeOnAa != savedDisableWazeOnAa ||
            currentDisableGoogleMapsOnAa != savedDisableGoogleMapsOnAa ||
            currentScreenOffReplaceLockScreen != savedScreenOffReplaceLockScreen ||
            currentHideAaRail != savedHideAaRail ||
            currentLauncher != savedLauncherPackage ||
            currentDelay != savedDelayDestroyTime ||
            currentDisplayDensity != savedDisplayDensity ||
            currentMinWidth != savedLecoMinWidth
    }

    private fun showValidationMessage(message: String, success: Boolean) {
        val color = if (success) {
            getColor(android.R.color.holo_green_dark)
        } else {
            MaterialColors.getColor(baseBinding.tvPackageValidation, android.R.attr.colorError)
        }
        baseBinding.tvPackageValidation.visibility = View.VISIBLE
        baseBinding.tvPackageValidation.text = message
        baseBinding.tvPackageValidation.setTextColor(color)
    }

    private fun hideValidationMessage() {
        baseBinding.tvPackageValidation.visibility = View.GONE
    }

    private fun applyLaunchPackageValidState() {
        val successColor = getColor(android.R.color.holo_green_dark)
        baseBinding.tilLauncherPackage.endIconMode = TextInputLayout.END_ICON_CUSTOM
        baseBinding.tilLauncherPackage.endIconDrawable = AppCompatResources.getDrawable(this, R.drawable.ic_round_check_circle_24)
        baseBinding.tilLauncherPackage.setEndIconTintList(ColorStateList.valueOf(successColor))
        baseBinding.tilLauncherPackage.boxStrokeColor = successColor
    }

    private fun clearLaunchPackageValidState() {
        baseBinding.tilLauncherPackage.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        if (baseBinding.tilLauncherPackage.error.isNullOrBlank()) {
            baseBinding.tilLauncherPackage.boxStrokeColor =
                MaterialColors.getColor(baseBinding.tilLauncherPackage, com.google.android.material.R.attr.colorOutline)
        }
    }
}
