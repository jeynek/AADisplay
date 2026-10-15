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
    still rendered in the launcher's slots.

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
