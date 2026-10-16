# Changelog

## 0.24.0 (2026-10-08)

### Changed
- Version updated to:
  - `versionName`: `0.24#17.7-r2`
  - `versionCode`: `3013`
- Auto-open now targets Android Auto `17.x`. Android Auto 17 removed
  `com.google.android.projection.gearhead.service.CarSystemUiControllerService`, so AADisplay asks the
  app decorator (`com.google.android.gearhead.appdecor.CarUiEntry.startCarActivity(Intent)`) to show its
  car activity. The legacy static entry point is kept as a fallback for older Android Auto builds.
- Every `CarUiEntry` instance (one per car region) is captured and the start request is retried
  (5 attempts, ~1.6 s apart). Android Auto brings up its own home and app list first and drops activity
  starts that arrive before its car activity manager is ready, which previously left the user on the
  Android Auto app list for ~10 s.
- The launcher and its PiP tasks are re-homed onto the AADisplay virtual display on connect/reconnect and
  whenever Android moves them to the phone display.
- AADisplay teardown is deferred by `DelayDestroyTime` so a short Android Auto reconnect reuses the same
  virtual display instead of letting Android relocate the launcher to the phone display.
- The left rail now disappears in every full screen state and the projected app fills the whole screen.
  The `HideAaRail` option (on by default) drives the no-rail layout request
  (`hasVerticalRail = false`), the app area widening and the `GhFacetBar` window hiding, instead of only
  acting while `leco_fullscreen` is `1`. Without the layout change Android Auto keeps reserving the rail
  width: the projected app area - and with it the AADisplay virtual display - stayed at
  `display width - 80 px` (720x480 on an 800x480 head unit) with a black strip on the left, no matter how
  the rail window was hidden afterwards. Reserving the rail also keeps Android Auto's own rail container
  in the car UI, so the same option is what stops the rail from coming back and stealing the left edge
  touches when full screen turns off.
- The app area is no longer "restored with the rail" when full screen turns off, so that transition no
  longer reopens the projected app (and no longer flickers the car screen).
- `VirtualDisplayDpi` is applied to the projection display itself, on connect and when the car display is
  resized. `0` (or any value <= 50) keeps the density Android Auto reports.
- The screen density and the LecoAuto minimum width can be typed in the settings screen, not only picked
  from the predefined list. `LecoAuto Min Width` clamps to `200..1000`, which is the range LecoAuto
  accepts, and `0` keeps the value from the theme.

### Fixed
- AADisplay no longer stays closed on Android Auto `17.7` (auto-open was silently disabled because its
  internal start-app service no longer exists).
- Auto-open no longer blocks the Android Auto main thread: the start request moved from the car UI entry
  constructor to the moment Android Auto has built its layout, which removes the "AADisplay is not
  responding" dialog.
- The launcher keeps showing its apps when the phone screen goes off or the phone locks. Three things
  happen when Android turns the phone screen off, and all of them look like "the apps disappeared":
  - the display manager requests `STATE_OFF` for the virtual displays as well (they belong to the
    phone's display group), which freezes the projection and paints the launcher's app slots black,
    so `ProjectionVirtualDisplayHook` keeps AADisplay's own display and the launcher's slot displays on
    (`VirtualDisplayAdapter$VirtualDisplayDevice.requestDisplayStateLocked`),
  - the window manager hides the windows of the displays which are not "always unlocked" while the
    keyguard is up, so the same hook reports those displays as always unlocked
    (`DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED`, the flag AADisplay's own display is created with),
  - Android puts its keyguard on every display that is not marked "always unlocked"
    (`KeyguardDisplayManager`, Android 16: `isKeyguardShowable`), which then covers those displays
    with a black window, so `SystemUiKeyguardHook` skips it (`isKeyguardShowable` and
    `showPresentation` return `false`, `updateNavigationBarVisibility` is skipped when it would show a
    navigation bar).
  The keyguard of the phone itself is untouched. `com.android.systemui` must be added to the module's
  scope (`vector-cli scope add` or the LSPosed scope list) and SystemUI restarted.
- Touches from the head unit keep working while the phone screen is off. The power notifier marks
  every display as "not interactive" when the phone screen turns off
  (`PowerManagerService.Notifier` -> `InputManagerInternal.setDisplayInteractivities`), which makes
  the input dispatcher drop every injected touch (`dumpsys input`: `Display not interactive:
  {0,4,5,...}`). `ProjectionVirtualDisplayHook` tracks the display ids of the projection
  (`DisplayManagerService.createVirtualDisplayInternal`) and keeps those entries interactive.
- The left edge of the projected app is usable in full screen mode. Android Auto's rail sits above the
  app area, so taps on the left edge landed on Android Auto's own (still visible) facet bar and opened
  e.g. its notification facet. The rail is not part of the car UI view tree: Android Auto draws it -
  the status icons and the facet buttons - into its own `GhFacetBar` window, a `PRIVATE_PRESENTATION`
  window on a display of its own (`dumpsys display`: `virtual:...gearhead,10297,GhFacetBar,...`, 80x480).
  `AaUiHook` now finds that window (`WindowManagerGlobal.mViews`, matched by `WindowManager.LayoutParams
  .title`), sets it to `alpha = 0` with `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` and falls back to
  `View.GONE` if the layout update fails, and the rail view of the car UI is also set to `INVISIBLE`.
  Android Auto adds the window shortly after the car UI is inflated, so the lookup is also retried.
- The rail window state is re-applied every 250 ms and whenever Android Auto rebuilds the window.
  Android Auto recreates the rail together with the projection, which happens on every Android Auto
  restart and also when the launcher is restarted, and the recreated bar is visible and touchable
  again: without the watchdog the rail came back and stole the touches of the left edge until the
  full screen state changed.
- The rail also comes back when Android Auto rewrites the layout of its own window instead of creating
  it again, which was observed when the car switched between day and night mode and when a notification
  made it re-apply its car UI. The watchdog compares the window's current layout and its view with what
  it wants instead of trusting the copy it wrote itself, applies the change again up to three times
  (100 ms apart) when Android Auto rejected it, and hides the rail view when it keeps overwriting it.

### Added
- Full screen mode for the launcher that drives the projection: the `hide-aadisplay-facet-bar` work.
  While `Settings.Global leco_fullscreen` is `1` (published by the LecoAuto navbar module) AADisplay
  reports the rail layout with `hasVerticalRail = false` and hides the facet bar, and the projected app
  area (`id/activity`) is resized to the whole display. Android Auto hands the projected app exactly the
  bounds of that view, so the app - and therefore the AADisplay virtual display - then gets the full
  screen size (e.g. 1024x600) instead of the display minus the 80 px rail (944x600).
  Keep `sys_ui_layout_canonical_vertical_rail_*` in use: it is the only layout Android Auto builds the
  app decor (`CarUiEntry`) for, which auto-open depends on.
- `FullscreenNoRail` and `FullscreenLayout` configuration keys. `FullscreenLayout` replaces the full
  screen layout resource by name, which is useful for experiments.
- The app area change is applied again when the full screen state changes while Android Auto is already
  connected, and the projected app is reopened so Android Auto re-creates its display at the new size.
- AADisplay opens itself when Android Auto connects, without the user having to pick it in the Android
  Auto app list. Android Auto 17.8 rejects the app decorator request (`CarUiEntry.startCarActivity`)
  unless the intent is a navigation or dial intent, so `SelfStartReceiver` opens AADisplay through the
  car API instead (`CarFirstPartyManager.startCarActivity`), which is the same call AADisplay uses to
  bring itself back to the front. The request is a broadcast with `FLAG_INCLUDE_STOPPED_PACKAGES`
  because AADisplay's process is usually not running yet and a background service start is not allowed.
- `Screen off replaces lock screen` switch in the settings screen for the existing
  `ScreenOffReplaceLockScreen` option: the power button only turns the phone screen off, and the
  projection keeps running. It takes effect on the next Android Auto connection.
- `Rail hidden` switch (`HideAaRail`, on by default): hides Android Auto's left rail and gives its width
  to the projected app in every full screen state.
- `LecoAuto Min Width` (`LecoMinWidth`) plus `LecoAutoHook`, which runs in the launcher's own process and
  overrides the `minimum_width` of its car desktop theme where LecoAuto reads it
  (`JSONObject.getInt("minimum_width")`, clamped to the `200..1000` LecoAuto accepts). LecoAuto re-reads
  its theme when it builds the desktop, so the override applies the next time the desktop is created -
  add `com.lecoauto` to the module scope (`vector-cli scope add io.github.nitsuya.aa.display
  com.lecoauto/0`).
- `ConfigReceiver` (`aa.display.action.SET_CONFIG` broadcast with `key`/`value`/`type`) to set a
  configuration value from the outside, which is how the settings were driven while the settings
  activity could not be automated (it is a floating window whose position changes).

### Verification
- Built successfully:
  - `:aa-display:assembleRelease`
- Verified in the Desktop Head Unit against a rooted Samsung SM-G970F (Android Auto 17.8):
  - full screen state published by the launcher module -> facet bar hidden, `id/activity` widened, and
    the AADisplay virtual display reported `800x480` instead of `720x480` on an 800x480 head unit,
  - full screen state absent -> the rail layout is left untouched and the virtual display stays `720x480`,
  - phone screen off (`input keyevent 26`) while the launcher ran with Maps, Spotify and Torque in its
    slots: `VirtualDisplayAdapter` no longer reports `from ON to OFF` for the projection displays,
    `KeyguardDisplayManager` no longer logs `Keyguard enabled on display` (it logged that for 15
    displays before), `DisplayInfo` of those displays reports `FLAG_ALWAYS_UNLOCKED`, and the Desktop
    Head Unit screenshots taken before and after the power press are identical - all three apps are
    still rendered in the launcher's slots,
  - a click on the left edge of the car screen before the change opened Android Auto's
    "no notifications" facet, after the change the same click reaches the launcher,
  - the Android Auto rail is gone from the car screen in full screen: the `GhFacetBar` display of the
    phone (`dumpsys SurfaceFlinger --display-id`, `screencap -d <id>`) is blank after the change
    although it still renders the status icons and the facet glyphs before it, and the same content
    that used to sit in the left strip of the projected car screen is gone,
  - leaving full screen restores the rail (`facet bar window shown`, `app area restored with the rail`),
  - restarting Android Auto while full screen stayed enabled (`am force-stop
    com.google.android.projection.gearhead`, head unit server again): the rebuilt `GhFacetBar` window is
    hidden again right away (`facet bar window not up yet, waiting for it`, then
    `facet bar window hidden`) although `leco_fullscreen` was never touched, and `dumpsys window` reports
    the new window with `alpha=0.0` and `fl=... NOT_TOUCHABLE NOT_FOCUSABLE`,
  - `dumpsys input` reports the projection displays as interactive while the phone screen is off, and
    the touches of the Desktop Head Unit keep reaching the launcher in that state,
  - with the rail hidden and `leco_fullscreen` **0**, the AADisplay virtual display is created at the
    full `800x480` of the head unit (it was `720x480` with an empty strip on the left before), the
    launcher's desktop fills the whole car screen, and `dumpsys window` reports the `GhFacetBar` window
    with `alpha=0.0` and `fl=... NOT_TOUCHABLE NOT_FOCUSABLE`,
  - AADisplay opened itself again after a clean `Desktop Head Unit` disconnect/reconnect without the user
    touching the app list, and the launcher's task was moved onto the projection display
    (`onActivityRestartAttempt: rehome com.lecoauto (task=...) -> display <id>`).

## 0.23.6 (2026-07-26)

### Changed
- Production version updated to:
  - `versionName`: `0.23#17.2-r5`
  - `versionCode`: `3011`
- AADisplay now creates its virtual display with the active Android Auto `TextureView` surface immediately instead of creating a surface-less display and attaching later.
- Default launcher startup now resolves from installed home launchers at runtime when no explicit launch package is configured, avoiding stale hard-coded package defaults.

### Fixed
- Fixed blank AADisplay rendering on Android Auto `17.2` by preserving the `TextureView` surface, attaching it during virtual-display creation, and reattaching it on reconnect.
- Prevented duplicate virtual display creation during rapid Android Auto lifecycle callbacks.
- Made hook preference access resilient under LSPosed by committing preferences synchronously and verifying hook-readable preference files.
- Android Auto hooks now continue with safe defaults if preference access is temporarily unavailable instead of aborting all hook setup.

### Verification
- Built successfully:
  - `:aa-display:assembleRelease`
- Installed and reboot-tested on the connected device.
- Live Android Auto validation confirmed:
  - AADisplay side control panel works
  - on-device Android Auto controls work
  - AADisplay opens and renders launcher content
  - backend virtual display and AA-hosted display both showed the same rendered content

## 0.23.4 (2026-05-23)

### Added
- Separate production toggle for Google Maps-on-phone while Android Auto remains connected:
  - new persisted setting `DisableGoogleMapsOnAa`
  - component-level enable/disable manager for Maps projection services + ghost activity

### Changed
- Production version updated to:
  - `versionName`: `0.23#16.8-r1`
  - `versionCode`: `3006`
- Waze component toggle execution now uses the same success gating strategy as Google Maps (all component operations must succeed before returning success).

### Fixed
- Android Auto UI/resource hook stability:
  - `AaUiHook` now skips optional hooks when required resources are missing instead of asserting/crashing.
  - projection decoration constructor hook now uses compatible constructor matching rather than hard-coded constructor lookup.
- Gearhead phenotype property hook robustness:
  - `AaPropsHook` now resolves string fields dynamically to reduce obfuscation fragility.

### Cleanup
- Removed dead/unused code and assets not used by current production flow:
  - removed `AaControlService`, `AaCarService`, `SettingsActivity`, legacy `LauncherHook`, deprecated pref XML, and orphaned resources.
- Removed obsolete global build-feature flag from `gradle.properties` (now explicitly configured in module build config).

### Evidence / Validation
- Built successfully:
  - `:aa-display:assembleRelease`
  - `:aa-display:lintDebug`
- Verified on connected device app targets:
  - Android Auto `16.8.661854-release`
  - Google Maps `26.20.01.913318892`
  - Waze `5.18.5.6`
- Waze and Maps toggles validated as separate independent controls in app flow.

## 0.23.3 (2026-05-05)

### Added
- Smart-sidebar style controller flow:
  - floating controller now starts minimized by default
  - tap edge handle to open, tap close to hide, drag and snap left/right edge

### Changed
- Keyboard routing on virtual display now enforces local IME policy (`DisplayImePolicy=0`) for on-screen input on AADisplay side instead of fallback-to-phone behavior.

### Fixed
- Removed the non-functional disconnect-type (`A`) button from the floating controller.
- Disconnect countdown now replaces the monitor button slot directly during disconnect flow for a cleaner 2-control vertical stack.

### Verification
- `:aa-display:assembleDebug` passed after controller + IME policy updates.
- Installed successfully to test device for live verification.

## 0.23.2 (2026-05-05)

### Changed
- Release build signing now falls back to debug signing when `KEY_ANDROID` is not present, so maintainers can still produce a release artifact locally without exposing private signing credentials.

### Fixed
- Prevented DHU disconnect teardown from forcing the phone back to launcher/home when AADisplay session closes on the virtual-display home screen.
- Teardown package-stop logic now excludes:
  - AADisplay itself
  - configured launch/home package
  - current foreground package on the primary phone display

### Verification
- Reproduced issue with config where `LauncherPackage` and `HomePackage` were both launcher-based, then validated corrected behavior after patch.
- `:aa-display:assembleDebug` succeeds with this change.
- Installed and prepared for live device retest flow.

## 0.23.1 (2026-05-04)

### Added
- In-app GitHub menu now points to the production fork: `https://github.com/Stashboy/AADisplay`.

### Changed
- Internal AADisplay behavior now syncs `HomePackage` to `LauncherPackage` so task-view home behavior is consistent with selected launch package.
- Sidebar Back key handling now uses Android virtual-display key event semantics (`FLAG_FROM_SYSTEM | FLAG_VIRTUAL_HARD_KEY`) for more reliable back navigation.

### Fixed
- Sidebar Back now works reliably in task-view sessions.
- Backing out to AADisplay home now clears pinned PiP state using the same cleanup strategy as Home (with front-home checks).

### Cleanup / Optimization (Evidence-Based)
- Fixed a real API-compatibility risk in `AADisplayConfig`: replaced Java Stream `.toList()` usage (API 34+) with Kotlin collection operators (safe for min SDK 31).
- Added missing `super.onDestroy()` in `AaControlService` to resolve lifecycle correctness lint error.
- Removed unused settings warning view/resources and unused string bloat in main settings screen.
- Internationalization cleanup for top-right menu title (`GitHub` moved to string resource).

### Verification
- `:aa-display:assembleDebug` succeeds after all changes.
- `:aa-display:lintDebug` re-run confirms resolved issues for:
  - `MissingSuperCall` (AaControlService)
  - `NewApi` (AADisplayConfig stream/toList path)
  - `HardcodedText` (`menu_main.xml` GitHub title)
- Runtime verification completed against Android Auto `16.6` behavior in both DHU and real head unit sessions.

## 0.23.0 (2026-05-04)

### Added
- Dynamic TaskView sizing lock to stabilize UI rendering across different head unit dimensions.
- Better teardown behavior for TaskView sessions to reduce lingering app states on disconnect/exit.
- Android Auto 16.6 hook compatibility updates.

### Changed
- Simplified settings UI to a single screen with only production-relevant options:
  - Auto Open
  - Default Launch Package
  - Delay Destroy Time
- Default GitHub menu flow retained from the main screen (settings gear flow removed).
- Production version string updated to `0.23#16.6-r1`.

### Fixed
- Kernel panic scenario during specific TaskView lifecycle transitions.
- Home/exit transition handling that previously left abnormal PiP-like states.
- Inconsistent YouTube rendering outcomes caused by unstable virtual display dimensions.

### Security / Privacy
- Removed repository-tracked signing key material from source control.
- Added keystore patterns to `.gitignore`.
- Removed machine-specific Gradle JDK path from tracked config.

### Compatibility
- Validated behavior against Android Auto 16.6 and device-side testing with real head unit workflow.

### Fixed
- The rail no longer comes back when Android Auto rebuilds its car UI (observed when switching the car
  between day and night mode) or when a notification makes it re-apply the layout of its rail window:
  the watchdog used to skip the work whenever the rail was in the state it thought it had set, but
  Android Auto overwrites that state on its own. The current layout of the window is checked now, the
  window view is checked for `GONE` as well, and a layout that Android Auto rejected is applied again
  up to three times (100 ms apart) before the rail view itself is hidden.

### Fixed
- The touches of the left edge no longer reach Android Auto when the rail is hidden. Hiding the rail
  window (`GhFacetBar`) was not enough: Android Auto kept its rail layout in use, and the region under
  the rail stayed Android Auto's, so a touch there opened its notification view. Android Auto's
  `sys_ui_layout_projected_app` is used as the car UI layout whenever the rail is not wanted now: it has
  no rail at all and hands the projected app the whole display. This also removes the empty strip the
  rail layout used to reserve. Verified on a Pixel-class head unit through the Desktop Head Unit:
  switching the car between day and night mode - which rebuilds Android Auto's car UI and used to bring
  the left edge block back every time - no longer takes the touches of the launcher.
  `FullscreenLayout` still overrides the layout resource, and setting it to
  `sys_ui_layout_canonical_vertical_rail_lhd` restores the previous behaviour.
