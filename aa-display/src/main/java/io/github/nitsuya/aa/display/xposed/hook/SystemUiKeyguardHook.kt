package io.github.nitsuya.aa.display.xposed.hook

import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.log

/**
 * Runs inside com.android.systemui.
 *
 * When the phone's screen turns off (or it locks), SystemUI shows its keyguard presentation on
 * every display which is not marked "always unlocked". The launcher renders its app slots as
 * embedded virtual displays, so such a presentation covers them with a black window and the
 * launcher looks empty although all apps are still alive.
 *
 * Secondary displays are our car/projected surfaces, the keyguard of the phone stays untouched.
 */
object SystemUiKeyguardHook : BaseHook() {
    override val tagName: String = "AAD_SystemUiKeyguard"

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        findMethod(KEYGUARD_DISPLAY_MANAGER) {
            name == "isKeyguardShowable"
            && parameterTypes[0] == Display::class.java
        }.hookBefore { param ->
            log(tagName, "keyguard presentation blocked for display ${(param.args[0] as? Display)?.displayId}")
            param.result = false
        }
        findMethod(KEYGUARD_DISPLAY_MANAGER) {
            name == "showPresentation"
            && parameterTypes[0] == Display::class.java
        }.hookBefore { param ->
            param.result = false
        }
        findMethod(KEYGUARD_DISPLAY_MANAGER) {
            name == "updateNavigationBarVisibility"
            && parameterTypes[0] == Int::class.javaPrimitiveType
            && parameterTypes[1] == Boolean::class.javaPrimitiveType
        }.hookBefore { param ->
            if ((param.args[1] as Boolean) && (param.args[0] as Int) != Display.DEFAULT_DISPLAY) {
                param.abortMethod()
            }
        }
    }

    private const val KEYGUARD_DISPLAY_MANAGER = "com.android.keyguard.KeyguardDisplayManager"
}
