package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.log
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Runs inside the launcher (com.lecoauto).
 *
 * The launcher keeps its own settings in the `lecoauto_setting` preferences under the MD5 of the
 * setting name, so the minimum width of its car desktop is stored as `md5("minimum_width")`:
 *
 * ```xml
 * <int name="c6807f04aac660664170aafab00fd696" value="3300" />
 * ```
 *
 * That preference is what the launcher's own settings screen reads and writes, and it wins over the
 * `minimum_width` of the active theme (`/data/user/0/com.lecoauto/files/themes/<hash>/config.json`),
 * which is only the default. The theme value is parsed by the launcher itself and accepted in
 * `200..1000` only; the preference has no such limit.
 *
 * AADisplay's `LecoMinWidth` (`0` leaves the launcher alone) is applied by writing that preference
 * when the launcher process starts, which is also what makes the value show up in the launcher's own
 * settings screen. The theme read is overridden as well, in case no preference has been written yet.
 */
object LecoAutoHook : BaseHook() {
    override val tagName: String = "AAD_LecoAutoHook"

    /** The launcher package this hook runs in, also used to route the hook in the module entry point. */
    const val LAUNCHER_PACKAGE = "com.lecoauto"

    private const val SETTING_NAME = "minimum_width"
    private const val SETTING_PREFS_NAME = "lecoauto_setting"

    /** The range the launcher accepts for the value it parses out of the theme itself. */
    private const val MIN_THEME_WIDTH = 200
    private const val MAX_THEME_WIDTH = 1000

    /** The setting name is obfuscated in the preferences, so the key has to be hashed the same way. */
    private val settingKey: String = MessageDigest.getInstance("MD5")
        .digest(SETTING_NAME.toByteArray())
        .joinToString("") { "%02x".format(it) }

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val config = XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
        hookThemeRead(config)
        applyPreferenceOnStart(config)
        log(
            tagName,
            "hooked ${lpparam.packageName} (uid ${lpparam.appInfo?.uid}, minimum_width key=$settingKey)"
        )
    }

    /** Answers the theme read with the configured width, so the override also works without a preference. */
    private fun hookThemeRead(config: XSharedPreferences) {
        findMethod(JSONObject::class.java) {
            name == "getInt"
            && parameterCount == 1
            && parameterTypes[0] == String::class.java
        }.hookAfter { param ->
            if (param.args[0] as? String != SETTING_NAME) return@hookAfter
            config.reload()
            val configured = AADisplayConfig.LecoMinWidth.get(config)
            if (configured <= 0) return@hookAfter
            if (configured !in MIN_THEME_WIDTH..MAX_THEME_WIDTH) {
                log(
                    tagName,
                    "minimum_width from theme kept at ${param.result}: $configured is outside " +
                        "$MIN_THEME_WIDTH..$MAX_THEME_WIDTH, it is applied through the preference instead"
                )
                return@hookAfter
            }
            log(tagName, "minimum_width from theme: ${param.result} -> $configured")
            param.result = configured
        }
    }

    /**
     * The launcher rebuilds its desktop from its own preferences, so the configured width has to be
     * written there. That needs the application context, which only exists once the launcher starts.
     */
    private fun applyPreferenceOnStart(config: XSharedPreferences) {
        try {
            var applicationCreated: XC_MethodHook.Unhook? = null
            applicationCreated = findMethod(Instrumentation::class.java) {
                name == "callApplicationOnCreate"
                && parameterCount == 1
                && parameterTypes[0] == Application::class.java
            }.hookBefore {
                applicationCreated?.unhook()
                EzXHelperInit.initAppContext()
                runCatching { applyConfiguredMinWidth(config, InitFields.appContext) }
                    .onFailure { log(tagName, "minimum_width preference write failed", it) }
            }
        } catch (t: Throwable) {
            log(tagName, "minimum_width preference hook failed", t)
        }
    }

    private fun applyConfiguredMinWidth(config: XSharedPreferences, context: Context) {
        config.reload()
        val configured = AADisplayConfig.LecoMinWidth.get(config)
        if (configured <= 0) {
            log(tagName, "minimum_width preference left alone: no AADisplay override")
            return
        }
        val preferences = context.getSharedPreferences(SETTING_PREFS_NAME, Context.MODE_PRIVATE)
        val previous = preferences.getInt(settingKey, Int.MIN_VALUE)
        if (previous == configured) {
            log(tagName, "minimum_width preference already $configured")
            return
        }
        preferences.edit().putInt(settingKey, configured).apply()
        log(
            tagName,
            "minimum_width preference: " +
                (if (previous == Int.MIN_VALUE) "unset" else previous.toString()) + " -> $configured"
        )
    }
}
