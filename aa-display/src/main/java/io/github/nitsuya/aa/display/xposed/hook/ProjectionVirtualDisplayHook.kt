package io.github.nitsuya.aa.display.xposed.hook

import android.util.SparseBooleanArray
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections

/**
 * Runs inside the system framework.
 *
 * Android turns the virtual displays of the projection off together with the phone screen and hides
 * their windows when the keyguard shows up. The projected screens then freeze and the launcher
 * paints its app slots black although all of its apps are still running.
 *
 * Those displays exist to be shown on the car screen, so they have to survive the phone screen:
 *  - their requested state is kept ON when the phone screen turns off,
 *  - they are reported as "always unlocked", which is what keeps the keyguard from hiding their
 *    windows (the same flag AADisplay's own display is created with).
 */
object ProjectionVirtualDisplayHook : BaseHook() {
    override val tagName: String = "AAD_ProjectionDisplays"

    private const val VIRTUAL_DISPLAY_DEVICE =
        "com.android.server.display.VirtualDisplayAdapter\$VirtualDisplayDevice"

    private const val DISPLAY_DEVICE_INFO = "com.android.server.display.DisplayDeviceInfo"

    private const val DISPLAY_MANAGER_SERVICE = "com.android.server.display.DisplayManagerService"

    private const val INPUT_MANAGER_LOCAL_SERVICE = "com.android.server.input.InputManagerService\$LocalService"

    // DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED (DisplayDeviceInfo uses its own bit numbering, this is
    // not Display.FLAG_ALWAYS_UNLOCKED)
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 15

    // AADisplay creates its own display from the system context, so the owner package name is
    // "android" - only the display name identifies it.
    private const val OWN_DISPLAY_NAME_PREFIX = "AADisplay-"

    private val displayDeviceInfoFlags: Field by lazy {
        loadClass(DISPLAY_DEVICE_INFO).getDeclaredField("flags").apply { isAccessible = true }
    }

    private val virtualDisplayConfigName: Method by lazy {
        loadClass("android.hardware.display.VirtualDisplayConfig").getMethod("getName")
    }

    /**
     * No input event reaches a display which the power notifier reports as "not interactive", and the
     * notifier marks every display that way when the phone screen turns off. The projection displays
     * keep receiving touches from the head unit, so they are kept interactive.
     */
    private val projectionDisplays: MutableSet<Int> =
        Collections.synchronizedSet(mutableSetOf<Int>())

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val virtualDisplayDevice = loadClass(VIRTUAL_DISPLAY_DEVICE)

        findAllMethods(virtualDisplayDevice) {
            name == "requestDisplayStateLocked"
            && parameterTypes[0] == Int::class.javaPrimitiveType
        }.hookBefore { param ->
            if (param.args[0] as Int != Display.STATE_OFF) return@hookBefore
            val displayName = displayName(param.thisObject)
            if (!isProjectionDisplay(param.thisObject, displayName)) return@hookBefore
            log(tagName, "keeping display $displayName on while the phone screen is off")
            param.args[0] = Display.STATE_ON
        }

        findAllMethods(virtualDisplayDevice) {
            name == "getDisplayDeviceInfoLocked"
        }.hookAfter { param ->
            val info = param.result ?: return@hookAfter
            val displayName = displayName(param.thisObject)
            if (!isProjectionDisplay(param.thisObject, displayName)) return@hookAfter
            val flags = displayDeviceInfoFlags.getInt(info)
            if (flags and FLAG_ALWAYS_UNLOCKED == 0) {
                displayDeviceInfoFlags.setInt(info, flags or FLAG_ALWAYS_UNLOCKED)
                log(tagName, "display $displayName is always unlocked now")
            }
        }

        findAllMethods(loadClass(DISPLAY_MANAGER_SERVICE)) {
            name == "createVirtualDisplayInternal"
            && parameterTypes[0] == loadClass("android.hardware.display.VirtualDisplayConfig")
        }.hookAfter { param ->
            val displayId = param.result as? Int ?: return@hookAfter
            val displayName = virtualDisplayConfigName.invoke(param.args[0]) as? String ?: return@hookAfter
            if (!isProjectionDisplayOf(displayName, param.args[5] as? String)) return@hookAfter
            projectionDisplays.add(displayId)
            log(tagName, "tracking display $displayId ($displayName)")
        }

        findAllMethods(loadClass(INPUT_MANAGER_LOCAL_SERVICE)) {
            name == "setDisplayInteractivities"
            && parameterTypes[0] == SparseBooleanArray::class.java
        }.hookBefore { param ->
            val interactivities = param.args[0] as? SparseBooleanArray ?: return@hookBefore
            var kept = 0
            for (displayId in projectionDisplays.toIntArray()) {
                if (interactivities.indexOfKey(displayId) >= 0 && !interactivities.get(displayId)) {
                    interactivities.put(displayId, true)
                    kept++
                }
            }
            if (kept > 0) {
                log(tagName, "kept $kept projection display(s) interactive")
            }
        }
    }

    private fun displayName(device: Any): String = device.getObjectAs<String>("mName").orEmpty()

    private fun isProjectionDisplay(device: Any, displayName: String): Boolean =
        isProjectionDisplayOf(displayName, device.getObjectAs<String>("mOwnerPackageName"))

    private fun isProjectionDisplayOf(displayName: String, ownerPackage: String?): Boolean {
        if (displayName.startsWith(OWN_DISPLAY_NAME_PREFIX)) return true
        if (ownerPackage == null) return false
        val config = CoreManagerService.config
        return ownerPackage == AADisplayConfig.LauncherPackage.get(config)
            || ownerPackage == AADisplayConfig.HomePackage.get(config)
    }
}
