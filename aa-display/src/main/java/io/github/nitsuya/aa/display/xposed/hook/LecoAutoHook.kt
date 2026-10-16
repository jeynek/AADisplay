package io.github.nitsuya.aa.display.xposed.hook

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.log
import org.json.JSONObject

/**
 * Runs inside the launcher (com.lecoauto).
 *
 * The launcher scales its desktop with `minimum_width` of the active theme and only accepts that
 * value between 200 and 1000 (below or above it keeps the value it already had). The value lives in
 * the theme, which makes it awkward to change while sitting in the car, so AADisplay's settings
 * screen can override it: the launcher reads the theme through `JSONObject.getInt`, and that call is
 * what this hook answers with the configured value.
 */
object LecoAutoHook : BaseHook() {
    override val tagName: String = "AAD_LecoAutoHook"

    private const val MINIMUM_WIDTH_KEY = "minimum_width"

    /** The launcher package this hook runs in, also used to route the hook in the module entry point. */
    const val LAUNCHER_PACKAGE = "com.lecoauto"

    /** The range the launcher itself accepts, values outside it would be ignored by the launcher. */
    private const val MIN_ACCEPTED_WIDTH = 200
    private const val MAX_ACCEPTED_WIDTH = 1000

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val config = XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
        findMethod(JSONObject::class.java) {
            name == "getInt"
            && parameterCount == 1
            && parameterTypes[0] == String::class.java
        }.hookAfter { param ->
            if (param.args[0] as? String != MINIMUM_WIDTH_KEY) return@hookAfter
            config.reload()
            val configured = AADisplayConfig.LecoMinWidth.get(config)
            val fromTheme = param.result
            if (configured !in MIN_ACCEPTED_WIDTH..MAX_ACCEPTED_WIDTH) {
                log(tagName, "minimum_width from theme: $fromTheme (no override)")
                return@hookAfter
            }
            log(tagName, "minimum_width from theme: $fromTheme -> $configured")
            param.result = configured
        }
        log(tagName, "hooked ${lpparam.packageName} (uid ${lpparam.appInfo?.uid})")
    }
}
