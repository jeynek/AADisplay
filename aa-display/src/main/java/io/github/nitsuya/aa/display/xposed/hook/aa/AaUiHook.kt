package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.getObjectOrNull
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XCallback
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.service.AaActivityService
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.FullscreenState
import io.github.nitsuya.aa.display.xposed.hook.abortMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.delay
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

object AaUiHook: AaHook() {
    override val tagName: String = "AAD_AaUiHook"

    private lateinit var layoutInfoConstructor: Constructor<*>

    /** Legacy (Android Auto < 17.x) entry point: CarSystemUiControllerService.a(Intent). */
    private var startMethod: Method? = null

    /** Android Auto 17.x entry point: CarUiEntry.startCarActivity(Intent), an instance method. */
    private var carUiEntryClass: Class<*>? = null
    private var startCarActivityMethod: Method? = null

    /**
     * Android Auto 17.x creates one CarUiEntry per car region (trampoline, app launcher, ...), and a
     * request sent to the wrong region is ignored. Keep every instance and try each of them.
     */
    private val carUiEntryInstances = CopyOnWriteArrayList<Any>()
    private const val MAX_CAR_UI_ENTRIES = 4
    private const val AUTO_OPEN_ATTEMPTS = 2
    private const val AUTO_OPEN_RETRY_DELAY_MS = 1600L
    /** Action handled by SelfStartReceiver in AADisplay's own process. */
    private const val SELF_START_ACTION = "aa.display.action.SELF_START"
    /** Android Auto drops early requests, so also retry once its car UI has been up for a while. */
    private const val AUTO_OPEN_LATE_DELAY_MS = 12_000L

    /** ViewGroup.LayoutParams.MATCH_PARENT, used to make the app area cover the whole display. */
    private const val FILL = -1

    /** Window title Android Auto gives its rail window. */
    private const val FACET_BAR_WINDOW_TITLE = "GhFacetBar"

    /** How often the rail window lookup is retried before giving up, and with which delay. */
    private const val FACET_BAR_LOOKUP_ATTEMPTS = 40
    private const val FACET_BAR_LOOKUP_DELAY_MS = 500L

    private const val VIEW_ROOT_IMPL = "android.view.ViewRootImpl"
    private const val VIEW_MANAGER_GLOBAL = "android.view.WindowManagerGlobal"

    /** Original width/height of id/activity, kept so the full screen state can be reverted. */
    private var mActivityOriginalSize: IntArray? = null

    /** Original background of id/rail, restored when the rail comes back. */
    private var mRailBackground = WeakReference<android.graphics.drawable.Drawable>(null)

    /** Android Auto's rail window, captured when it is added. */
    private var mFacetBarWindow = WeakReference<View>(null)
    private var mFacetBarParams: WindowManager.LayoutParams? = null
    private var mFacetBarLookups = 0

    private val mMainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** App area mode the projected app was last opened with, null until the car UI is inflated. */
    private var mAppAreaFull: Boolean? = null

    private var mConfig: SharedPreferences? = null
    /**
     * Every facet bar this hook built. Android Auto inflates its rail layout more than once (one
     * instance per rail surface), so all of them have to follow the full screen state: hiding only
     * the last one leaves the earlier bars visible as white icons on a transparent background.
     */
    private val mFacetBars = CopyOnWriteArrayList<WeakReference<View>>()

    private var resLayoutLeftResourceId: Int = 0
    private var resLayoutRightResourceId: Int = 0

    private var resLayoutGhFacetBarId: Int = 0
    private var resIdStatusBarId: Int = 0
    private var resIdLauncherAndDashboardIconContainerId: Int = 0
    private var resIdLauncherAndDashboardIconId: Int = 0
    private var canHookLayout: Boolean = false
    private var canHookFacetBar: Boolean = false

    /** Android Auto's car UI views that decide how wide the projected app area is. */
    private var resIdActivity: Int = 0
    private var resIdRail: Int = 0
    private var resIdRailSpacer: Int = 0

    /** Last inflated car UI root, so the full screen state can be re-applied when it changes. */
    private var mCarUiRoot = WeakReference<View>(null)

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {
        val classes = bridge.findClass {
            searchPackages = listOf("")
            matcher {
                usingStrings {
                    add(
                        "LayoutInfo{layoutResourceId=",
                        StringMatchType.StartsWith,
                        false
                    )
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaUiHook: not found LayoutInfo class：${classes.size}")
        }
        layoutInfoConstructor = resolveLayoutInfoConstructor(classes[0].name)

        // Android Auto 17.x removed CarSystemUiControllerService and exposes the same capability as
        // the public CarUiEntry.startCarActivity(Intent) instance method (AppDecorService, ":projection").
        try{
            carUiEntryClass = loadClass("com.google.android.gearhead.appdecor.CarUiEntry")
            startCarActivityMethod = carUiEntryClass?.getDeclaredMethod("startCarActivity", Intent::class.java)?.apply {
                isAccessible = true
            }
            log(tagName, "AaUiHook: CarUiEntry.startCarActivity resolved=${startCarActivityMethod != null}")
        } catch (e: Throwable){
            carUiEntryClass = null
            startCarActivityMethod = null
            log(tagName,  "AaUiHook: not found CarUiEntry.startCarActivity", e)
        }
        if(startCarActivityMethod == null) {
            // Fallback for Android Auto builds that still ship the legacy controller service.
            try{
                startMethod = loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService").staticMethod("a", null, argTypes(Intent::class.java))
            } catch (e: Throwable){
                log(tagName,  "AaUiHook: not found CarSystemUiControllerService.a static method", e)
            }
        }

        resLayoutGhFacetBarId = InitFields.appContext.resources.getIdentifier("gh_coolwalk_vertical_facet_bar", "layout", InitFields.appContext.packageName)
        resIdStatusBarId = getIdByName("status_bar")
        resIdLauncherAndDashboardIconContainerId = getIdByName("launcher_and_dashboard_icon_container")
        resIdLauncherAndDashboardIconId = getIdByName("launcher_and_dashboard_icon")

        // The projected app renders into id/activity; its left edge is anchored to id/rail_spacer,
        // which is exactly the rail width Android Auto keeps reserved for the vertical facet bar.
        resIdActivity = getIdByName("activity")
        resIdRail = getIdByName("rail")
        resIdRailSpacer = getIdByName("rail_spacer")
        log(tagName, "AaUiHook: car ui ids: activity=$resIdActivity rail=$resIdRail railSpacer=$resIdRailSpacer")

        resLayoutLeftResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_canonical_vertical_rail_lhd", "layout", InitFields.appContext.packageName)
        resLayoutRightResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_canonical_vertical_rail_rhd", "layout", InitFields.appContext.packageName)

        canHookLayout = resLayoutLeftResourceId != 0 && resLayoutRightResourceId != 0
        if (!canHookLayout) {
            log(
                tagName,
                "AaUiHook: skip layout override, missing canonical layout resources: lhd=$resLayoutLeftResourceId, rhd=$resLayoutRightResourceId"
            )
        }
        canHookFacetBar =
            resLayoutGhFacetBarId != 0 &&
            resIdStatusBarId != 0 &&
            resIdLauncherAndDashboardIconContainerId != 0 &&
            resIdLauncherAndDashboardIconId != 0
        if (!canHookFacetBar) {
            log(
                tagName,
                "AaUiHook: skip facet-bar override, missing resources: facet=$resLayoutGhFacetBarId, status=$resIdStatusBarId, launcherContainer=$resIdLauncherAndDashboardIconContainerId, launcherIcon=$resIdLauncherAndDashboardIconId"
            )
        }
    }

    override fun hook(config: SharedPreferences?, lpparam: XC_LoadPackage.LoadPackageParam) {
        log(tagName,  "AaUiHook: ~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        mConfig = config
        log(
            tagName,
            "config: null=${config == null}, autoOpen=${AADisplayConfig.AutoOpen.get(config)}, " +
                "noRail=${AADisplayConfig.FullscreenNoRail.get(config)}, " +
                "layout=${AADisplayConfig.FullscreenLayout.get(config)}"
        )
        FullscreenState.onChange = { applyFullscreenState() }
        // Start watching here as well, so the layout hook still sees the state if the facet bar is
        // never inflated on this Android Auto build.
        try {
            FullscreenState.start(InitFields.appContext)
        } catch (t: Throwable) {
            log(tagName, "FullscreenState.start from appContext failed", t)
        }
        hookBaseClick()
        if (canHookLayout) {
            hookLayout()
            hookCarUiInflation()
        }
        if (startCarActivityMethod != null) {
            hookCarUiEntryInstance()
        }
        if (canHookFacetBar) {
            hookFacetBar(config)
        }
        hookFacetBarWindow()
        hookRadius(config)
    }

    /**
     * CarUiEntry is created per car region by AppDecorService (process ":projection").
     * Capture a live instance so its public startCarActivity(Intent) can open AADisplay.
     */
    private fun hookCarUiEntryInstance() {
        val clazz = carUiEntryClass ?: return
        try {
            clazz.getDeclaredMethod("onStart").hookAfter { rememberCarUiEntry(it.thisObject) }
        } catch (e: Throwable) {
            log(tagName, "hookCarUiEntryInstance: onStart not hooked", e)
        }
        try {
            clazz.declaredConstructors.forEach { ctor ->
                ctor.hookAfter { rememberCarUiEntry(it.thisObject) }
            }
        } catch (e: Throwable) {
            log(tagName, "hookCarUiEntryInstance: constructors not hooked", e)
        }
    }

    private val autoOpenRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Opens AADisplay once per attempt burst. Triggered both from the facet bar and from the first
     * CarUiEntry we capture, so it still works when the bar-less full screen layout is in use and
     * the facet bar is never inflated.
     */
    private fun triggerAutoOpen() {
        if (!AADisplayConfig.AutoOpen.get(mConfig)) return
        if (!autoOpenRunning.compareAndSet(false, true)) return
        runMain {
            try {
                delay(700)
                requestSelfStart()
                requestShowAaDisplay()
            } finally {
                autoOpenRunning.set(false)
            }
        }
    }

    /**
     * Asks AADisplay to open itself through the car API: Android Auto 17.8 only accepts navigation
     * and dial intents on the app decorator path, while this is the same call AADisplay uses to
     * bring itself back to the front. The request is a broadcast because a package that is not
     * running cannot be started with a background service start.
     */
    private fun requestSelfStart() {
        try {
            val intent = Intent(SELF_START_ACTION)
                .setPackage(BuildConfig.APPLICATION_ID)
                // AADisplay's process is usually not running yet, and a package in the stopped state
                // only receives broadcasts when this flag is set.
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            InitFields.appContext.sendBroadcast(intent)
            log(tagName, "auto-open: asked AADisplay to start itself")
        } catch (t: Throwable) {
            log(tagName, "auto-open: self start request failed", t)
        }
    }

    /**
     * Android Auto also drops requests that arrive while its car UI is still starting, and that is
     * especially likely when the layout that is in use has no app decor UI of its own.
     */
    private fun triggerAutoOpenSoonAndLate() {
        triggerAutoOpen()
        listOf(AUTO_OPEN_LATE_DELAY_MS, AUTO_OPEN_LATE_DELAY_MS * 2).forEach { wait ->
            runMain {
                delay(wait)
                triggerAutoOpen()
            }
        }
    }

    private fun rememberCarUiEntry(instance: Any?) {
        if (instance == null) return
        if (carUiEntryInstances.none { it === instance }) {
            carUiEntryInstances.add(instance)
            while (carUiEntryInstances.size > MAX_CAR_UI_ENTRIES) {
                carUiEntryInstances.removeAt(0)
            }
            log(tagName, "CarUiEntry captured: ${instance.javaClass.name} (total=${carUiEntryInstances.size})")
            if (carUiEntryInstances.size == 1) {
                // Android Auto has just built its app decor, which is when it accepts the request
                // most reliably.
                triggerAutoOpenSoonAndLate()
            }
        }
    }

    /**
     * Ask Android Auto to show the AADisplay CarActivityService.
     * Android Auto 17.x uses CarUiEntry.startCarActivity; older builds use the legacy static method.
     */
    private suspend fun requestShowAaDisplay() {
        val intent = Intent().apply {
            component = ComponentName(BuildConfig.APPLICATION_ID, AaActivityService::class.java.name)
            putExtra("android.intent.extra.PACKAGE_NAME", BuildConfig.APPLICATION_ID)
        }
        val method = startCarActivityMethod
        if (method == null) {
            // Legacy fallback path.
            try {
                if (startMethod != null) {
                    startMethod?.invoke(null, intent)
                    log(tagName, "auto-open: CarSystemUiControllerService.a invoked (legacy)")
                } else {
                    log(tagName, "auto-open skipped: no usable entry point")
                }
            } catch (e: Throwable) {
                log(tagName, "auto-open: legacy start app error", e)
            }
            return
        }
        // Android Auto 17.x brings up its own startup screens (home, then its app launcher) first and
        // drops activity starts that arrive before the car activity manager is ready. Retry so
        // AADisplay takes over instead of leaving the user on the Android Auto app list.
        var invoked = false
        repeat(AUTO_OPEN_ATTEMPTS) { attempt ->
            val entries = carUiEntryInstances.toList()
            entries.forEach { entry ->
                try {
                    method.invoke(entry, intent)
                    invoked = true
                } catch (e: Throwable) {
                    log(tagName, "auto-open: CarUiEntry.startCarActivity error", e)
                }
            }
            log(tagName, "auto-open: attempt ${attempt + 1}/$AUTO_OPEN_ATTEMPTS, entries=${entries.size}")
            if (attempt < AUTO_OPEN_ATTEMPTS - 1) {
                delay(AUTO_OPEN_RETRY_DELAY_MS)
            }
        }
        if (!invoked) {
            log(tagName, "auto-open skipped: CarUiEntry instance not captured")
        }
    }

    /**
     * Android Auto's car UI layout gives the projected app (id/activity) the width the display has
     * minus the rail, because id/activity is anchored to the right edge of id/rail_spacer. Removing
     * the rail is therefore not enough: the app has to be re-anchored to the whole display, which is
     * what this does, and Android Auto then hands the wider area to the projected app.
     */
    private fun hookCarUiInflation() {
        if (resIdActivity == 0) {
            log(tagName, "app area widening disabled: id/activity not found")
            return
        }
        try {
            var hooked = 0
            LayoutInflater::class.java.declaredMethods
                .filter {
                    it.name == "inflate" &&
                        it.parameterCount >= 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                .forEach { method ->
                    method.isAccessible = true
                    method.hookAfter { param ->
                        val layoutId = param.args[0] as? Int ?: return@hookAfter
                        if (layoutId != resLayoutLeftResourceId && layoutId != resLayoutRightResourceId) {
                            return@hookAfter
                        }
                        val root = param.result as? View ?: return@hookAfter
                        mCarUiRoot = WeakReference(root)
                        // A fresh car UI means Android Auto is about to open the projected app, so the
                        // current app area already applies to it: only later changes need a reopen.
                        mAppAreaFull = null
                        applyAppAreaState()
                    }
                    hooked++
                }
            log(tagName, "car ui inflation hooked ($hooked overloads)")
        } catch (t: Throwable) {
            log(tagName, "car ui inflation hook failed", t)
        }
    }

    /** Widens the projected app area to the whole display, or restores the rail. */
    private fun applyAppAreaState() {
        val root = mCarUiRoot.get() ?: return
        val full = isNoRailPreferred(mConfig)
        try {
            stretchAppArea(root, full)
        } catch (t: Throwable) {
            log(tagName, "app area stretch failed", t)
        }
        val changed = mAppAreaFull != null && mAppAreaFull != full
        mAppAreaFull = full
        if (changed) {
            // Android Auto picks the size of the projected app display when it opens the app, so the
            // app has to be opened again for the new app area to reach it.
            log(tagName, "app area mode changed, reopening the projected app")
            triggerAutoOpenSoonAndLate()
        }
    }

    /**
     * Android Auto hands the projected app exactly the bounds of id/activity, and that view is
     * anchored to the rail, so the app is narrowed by the rail width. Giving the view the whole
     * display keeps Android Auto's rail layout, and with it the app decor that opens AADisplay by
     * itself, while the projected app still gets the full screen.
     */
    private fun stretchAppArea(root: View, full: Boolean) {
        val activity = root.findViewById<View>(resIdActivity)
        if (activity == null) {
            log(tagName, "app area: id/activity missing in ${root.javaClass.simpleName}")
            return
        }
        val params = activity.layoutParams
        val rail = root.findViewById<View>(resIdRail)
        if (full) {
            if (mActivityOriginalSize == null) {
                mActivityOriginalSize = intArrayOf(params.width, params.height)
            }
            params.width = FILL
            params.height = FILL
            // Android Auto's rail is drawn by its own GhFacetBar window, which is hidden by
            // hookFacetBarWindow(). The rail view in this window is what is left of it: it must not
            // paint (its background) nor take the touches of the left edge.
            if (rail != null && mRailBackground == null) {
                mRailBackground = WeakReference(rail.background)
            }
            rail?.setBackgroundColor(Color.TRANSPARENT)
            rail?.visibility = View.INVISIBLE
        } else {
            val original = mActivityOriginalSize ?: return
            params.width = original[0]
            params.height = original[1]
            rail?.visibility = View.VISIBLE
            mRailBackground?.get()?.let { rail?.background = it }
        }
        activity.layoutParams = params
        activity.requestLayout()
        applyFacetBarWindowState()
        log(tagName, "app area ${if (full) "widened to the whole display" else "restored with the rail"}")
    }

    /**
     * Android Auto draws its rail - the status icons and the facet buttons - into its own
     * `GhFacetBar` window (`PRIVATE_PRESENTATION` on a display of its own), so no change to the car
     * UI view tree can hide it. The window is looked up lazily and made invisible and non-touchable
     * while the app area is widened.
     */
    private fun hookFacetBarWindow() {
        findAllMethods(loadClass(VIEW_ROOT_IMPL)) {
            name == "setView"
            && parameterCount == 3
            && parameterTypes[1] == WindowManager.LayoutParams::class.java
        }.hookAfter { param ->
            val windowParams = param.args[1] as? WindowManager.LayoutParams ?: return@hookAfter
            if (windowParams.title?.toString() != FACET_BAR_WINDOW_TITLE) return@hookAfter
            captureFacetBarWindow(param.args[0] as? View ?: return@hookAfter, windowParams)
        }
    }

    private fun captureFacetBarWindow(view: View, windowParams: WindowManager.LayoutParams) {
        log(tagName, "facet bar window captured")
        mFacetBarWindow = WeakReference(view)
        mFacetBarParams = windowParams
    }

    /** Finds Android Auto's rail window in the window manager, it is added before the hooks run. */
    private fun findFacetBarRoot(): Boolean {
        return runCatching {
            val windowManagerGlobal = loadClass(VIEW_MANAGER_GLOBAL)
            val instance = windowManagerGlobal.getMethod("getInstance").invoke(null)
            // mViews holds the decor view of every window of this process.
            val windows = windowManagerGlobal.getDeclaredField("mViews")
                .apply { isAccessible = true }.get(instance) as? List<*> ?: return false
            for (candidate in windows) {
                val view = candidate as? View ?: continue
                val windowParams = view.layoutParams as? WindowManager.LayoutParams ?: continue
                if (windowParams.title?.toString() != FACET_BAR_WINDOW_TITLE) continue
                captureFacetBarWindow(view, windowParams)
                return true
            }
            return false
        }.onFailure {
            log(tagName, "facet bar window lookup failed", it)
        }.getOrDefault(false)
    }

    /**
     * Android Auto adds its rail window some time after the car UI is inflated, so a failed lookup
     * is retried instead of leaving the rail visible.
     */
    private fun retryFacetBarWindowState() {
        if (mFacetBarLookups++ >= FACET_BAR_LOOKUP_ATTEMPTS) return
        mMainHandler.postDelayed({ applyFacetBarWindowState() }, FACET_BAR_LOOKUP_DELAY_MS)
    }

    private fun applyFacetBarWindowState() {
        // The window is recreated when Android Auto restarts the projection, so a detached view is
        // stale and has to be looked up again.
        if (mFacetBarWindow.get()?.isAttachedToWindow != true) {
            mFacetBarWindow.clear()
            if (!findFacetBarRoot()) {
                retryFacetBarWindowState()
                return
            }
        }
        val view = mFacetBarWindow.get() ?: return
        val windowParams = mFacetBarParams ?: return
        mFacetBarLookups = 0
        val hide = isNoRailPreferred(mConfig)
        val ignoreTouch = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val ignoreFocus = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val wanted = if (hide) windowParams.flags or ignoreTouch or ignoreFocus
        else windowParams.flags and (ignoreTouch or ignoreFocus).inv()
        val wantedAlpha = if (hide) 0f else 1f
        if (windowParams.flags == wanted && windowParams.alpha == wantedAlpha) return
        windowParams.flags = wanted
        windowParams.alpha = wantedAlpha
        val updated = runCatching {
            (InitFields.appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(view, windowParams)
        }.onFailure {
            log(tagName, "facet bar window update failed", it)
        }.isSuccess
        if (!updated) {
            // Hiding the root view makes the window manager drop the window, this also works when
            // the layout could not be updated.
            view.visibility = if (hide) View.GONE else View.VISIBLE
        }
        log(tagName, "facet bar window ${if (hide) "hidden" else "shown"}")
    }

    private fun hookLayout() {
        layoutInfoConstructor.hookAfter { param ->
            log(tagName, param.thisObject.toString())
            // Android Auto has just built its car UI, so its car activity manager is ready. This is
            // the safe moment to ask it to open AADisplay, and unlike the facet bar hook it also
            // happens with the bar-less full screen layout.
            triggerAutoOpenSoonAndLate()
        }
        layoutInfoConstructor.hookBefore { param ->
            if (param.args.size < 5) return@hookBefore
            val layoutTypeCode = layoutTypeCode(param.args[3] ?: return@hookBefore) ?: return@hookBefore
            // Skip cluster/auxiliary layouts to avoid overriding non-main surfaces.
            if (layoutTypeCode in setOf(7, 8, 9)) return@hookBefore
            val isRightHandDrive = (param.args[4] as? Boolean) == true
            val noRail = isNoRailPreferred(mConfig)
            val originalRail = param.args.getOrNull(5) as? Boolean
            val railLayout = if (isRightHandDrive) resLayoutRightResourceId else resLayoutLeftResourceId
            // AADisplayConfig.FullscreenLayout replaces the full screen layout by resource name.
            val overrideName = if (noRail) AADisplayConfig.FullscreenLayout.get(mConfig)?.trim() else null
            val overrideId = if (!overrideName.isNullOrBlank()) {
                InitFields.appContext.resources.getIdentifier(overrideName, "layout", InitFields.appContext.packageName)
            } else 0
            // Keep Android Auto's rail layout in full screen: it is the only variant for which
            // Android Auto builds the app decor (CarUiEntry) that AADisplay uses to open itself.
            // The rail is removed and the app area widened below, after the layout is inflated.
            param.args[0] = if (noRail && overrideId != 0) overrideId else railLayout
            // Force canonical vertical rail for LHD/RHD.
            setLayoutTypeArg(param.args, if (isRightHandDrive) 3 else 2)
            if (param.args.size > 5 && param.args[5] is Boolean) {
                param.args[5] = !noRail
            }
            log(
                tagName,
                "layout requested: fullscreen=${FullscreenState.value()}, noRail=$noRail, rhd=$isRightHandDrive, " +
                    "rail=$originalRail->${param.args.getOrNull(5)}, resourceId=${param.args[0]}"
            )
        }
    }

    private fun resolveLayoutInfoConstructor(className: String): Constructor<*> {
        // Keep only stable shape checks so this works across AA 16.4 and 16.6+.
        val clazz = loadClass(className)
        val fallback = clazz.declaredConstructors.firstOrNull { ctor ->
            val p = ctor.parameterTypes
            p.size >= 10
                && p[0] == Int::class.javaPrimitiveType
                && p[1] == Int::class.javaPrimitiveType
                && p[2] == Int::class.javaPrimitiveType
                && p[4] == Boolean::class.javaPrimitiveType
                && p[5] == Boolean::class.javaPrimitiveType
                && p[7] == Boolean::class.javaPrimitiveType
                && p[8] == Boolean::class.javaPrimitiveType
                && p[9] == Boolean::class.javaPrimitiveType
                && !p[6].isPrimitive
                && (p[3] == Int::class.javaPrimitiveType || p[3].isEnum)
        } ?: throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")

        fallback.isAccessible = true
        log(tagName, "AaUiHook: fallback constructor selected, paramCount=${fallback.parameterCount}, layoutTypeArg=${fallback.parameterTypes[3].name}")
        return fallback
    }

    private fun layoutTypeCode(raw: Any): Int? {
        return when (raw) {
            is Int -> raw - 1
            is Enum<*> -> raw.toString().toIntOrNull()
            else -> raw.toString().toIntOrNull()
        }
    }

    private fun setLayoutTypeArg(args: Array<Any?>, targetCode: Int) {
        val raw = args.getOrNull(3) ?: return
        when (raw) {
            is Int -> args[3] = targetCode + 1
            is Enum<*> -> {
                val enumClass = raw.javaClass
                val enumValue = enumClass.enumConstants?.firstOrNull { c ->
                    c?.toString()?.toIntOrNull() == targetCode
                }
                if (enumValue != null) {
                    args[3] = enumValue
                }
            }
        }
    }

    private fun hookFacetBar(config: SharedPreferences?) {
        val closeLauncherDashboard = AADisplayConfig.CloseLauncherDashboard.get(config)
        val autoOpen = AADisplayConfig.AutoOpen.get(config)
        findMethod(LayoutInflater::class.java) {
            name == "inflate"
            && parameterCount == 3
            && parameterTypes[0] == Int::class.javaPrimitiveType // resource
            && parameterTypes[1] == ViewGroup::class.java // root
            && parameterTypes[2] == Boolean::class.javaPrimitiveType // attachToRoot
        }.hookAfter { param ->
            if (param.args[0] as Int != resLayoutGhFacetBarId) {
                return@hookAfter
            }
            val resultViewGroup = param.result as ViewGroup? ?: return@hookAfter //androidx.constraintlayout.widget.ConstraintLayout
            val ctx = (param.thisObject as LayoutInflater).context
            val ctx2 = CommonContextWrapper.createAppCompatContext(ctx)
            val layoutInflater = LayoutInflater.from(ctx2)
            val resultViewGroupParent = (resultViewGroup.parent as ViewGroup?)?.apply {
                removeView(resultViewGroup)
            }
            if(closeLauncherDashboard){
                resultViewGroup.findViewById<View>(resIdLauncherAndDashboardIconId).apply {
                    setOnClickFinallyListener {
                        performLongClick()
                    }
                }
            }
            val aaFacetBar = layoutInflater.inflate(R.layout.aa_facet_bar, resultViewGroupParent, false) as ConstraintLayout
            if(autoOpen){
                aaFacetBar.post { triggerAutoOpen() }
            }
            val createBtn: (resId: Int, block: View.() -> Unit) -> Int = { resId, block ->
                val btn = ImageView(ctx).apply {
                    id = View.generateViewId()
                    layoutParams = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setImageResource(resId)
                }
                block(btn)
                aaFacetBar.addView(btn)
                btn.id
            }
            val topIds = arrayListOf(
                resIdStatusBarId,
                resIdLauncherAndDashboardIconContainerId
            )
            val bottomIds = arrayListOf(
                createBtn(R.drawable.ic_aa_home_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_HOME)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_aa_fullscreen_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_APP_SWITCH)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_aa_arrow_back_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_BACK)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
            )
            arrayListOf(resIdStatusBarId, resIdLauncherAndDashboardIconContainerId).forEach { vId ->
                val view = resultViewGroup.findViewById<View>(vId)
                (view.parent as ViewGroup?)?.apply {
                    removeView(view)
                }
                aaFacetBar.addView(view)
            }
            val set = ConstraintSet()
            set.clone(aaFacetBar)
            bottomIds.forEachIndexed { index, vId ->
                set.connect(vId, ConstraintSet.BOTTOM, if(index == 0) ConstraintSet.PARENT_ID else bottomIds[index-1], if(index == 0) ConstraintSet.BOTTOM else ConstraintSet.TOP, 0)
                set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            }
            topIds.forEachIndexed { index, vId ->
                set.connect(vId, ConstraintSet.TOP, if(index == 0) ConstraintSet.PARENT_ID else topIds[index-1], if(index == 0) ConstraintSet.TOP else ConstraintSet.BOTTOM, 0)
                set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            }
            set.applyTo(aaFacetBar)
            resultViewGroup.visibility = View.GONE
            aaFacetBar.addView(resultViewGroup)
            mFacetBars.add(WeakReference(aaFacetBar))
            mFacetBars.removeAll { it.get() == null }
            log(tagName, "facet bar built, ${mFacetBars.size} instance(s) tracked")
            FullscreenState.start(ctx)
            applyFullscreenState()
            param.result = aaFacetBar
        }
    }

    private fun hookBaseClick() {
        try {
            findMethod(View::class.java) {
                name == "setOnLongClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnLongClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnLongClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnLongClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriLongClickListener(it.args[0] as View.OnLongClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnLongClickListener", e)
        }
        try {
            findMethod(View::class.java) {
                name == "setOnClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriClickListener(it.args[0] as View.OnClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnClickListener", e)
        }
    }

    /** True while the Launcher is in full screen and the no-rail behaviour is enabled. */
    private fun isNoRailPreferred(config: SharedPreferences?): Boolean =
        FullscreenState.value() && AADisplayConfig.FullscreenNoRail.get(config)

    /**
     * Mirrors the launcher's full screen state onto the facet bar. The projected area itself is
     * resized by Android Auto once the no-rail layout is in use, and AADisplay follows it through
     * onConnected/onReconnected.
     */
    private fun applyFullscreenState() {
        applyAppAreaState()
        val hide = isNoRailPreferred(mConfig)
        mFacetBars.forEach { ref ->
            val bar = ref.get() ?: return@forEach
            bar.post {
                val target = if (hide) View.GONE else View.VISIBLE
                if (bar.visibility != target) {
                    bar.visibility = target
                    log(tagName, "facet bar -> ${if (hide) "GONE" else "VISIBLE"} (fullscreen=${FullscreenState.value()})")
                }
            }
        }
    }

    private fun hookRadius(config: SharedPreferences?) {
        if(!AADisplayConfig.ForceRightAngle.get(config)){
            return
        }
        try{
            val targetClass = loadClass("com.google.android.gms.car.ProjectionWindowDecorationParams")
            val ctor = targetClass.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size >= 9 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == Int::class.javaPrimitiveType &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    p[4] == Int::class.javaPrimitiveType &&
                    p[5] == Int::class.javaPrimitiveType &&
                    p[6] == Int::class.javaPrimitiveType &&
                    p[7] == Boolean::class.javaPrimitiveType &&
                    p[8] == Boolean::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("AaUiHook: ProjectionWindowDecorationParams compatible constructor not found")
            ctor.isAccessible = true
            ctor.hookBefore { param ->
                if (param.args.size > 5 && param.args[5] is Int) {
                    param.args[5] = 0
                }
            }
        } catch (e: Throwable) {
            log(tagName, "ProjectionWindowDecorationParams", e)
        }
    }

    interface FinallyListener
    private fun interface OnClickFinallyListener: View.OnClickListener, FinallyListener
    private fun interface OnLongClickFinallyListener: View.OnLongClickListener, FinallyListener
    private fun View.setOnClickFinallyListener(l: OnClickFinallyListener) = this.setOnClickListener(l)
    private fun View.setOnLongClickFinallyListener(l: OnLongClickFinallyListener) = this.setOnLongClickListener(l)
    private fun View.setOnOriClickListener(l: View.OnClickListener) = this.setTag(R.id.ori_click_listener, l)
    private fun View.setOnOriLongClickListener(l: View.OnLongClickListener) = this.setTag(R.id.ori_long_click_listener, l)
    private fun View.performOriClick() {
        val clickListener = this.getTag(R.id.ori_click_listener) as View.OnClickListener? ?: return
        clickListener.onClick(this)
    }
    private fun View.performOriLongClick(): Boolean {
        val clickListener = this.getTag(R.id.ori_long_click_listener) as View.OnLongClickListener? ?: return false
        return clickListener.onLongClick(this)
    }
}
