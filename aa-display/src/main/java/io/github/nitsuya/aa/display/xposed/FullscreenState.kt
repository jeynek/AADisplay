package io.github.nitsuya.aa.display.xposed

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.nitsuya.aa.display.xposed.log

/**
 * Full screen state of the LecoAuto desktop, published by the LecoAuto Xposed module through
 * Settings.Global (the launcher runs as uid 1000 and can write secure settings).
 *
 * This module lives inside Android Auto's projection process, which is a different process, so the
 * value is read from Settings and tracked with a ContentObserver instead of sharing memory.
 */
object FullscreenState {
    private const val TAG = "AAD_FullscreenState"

    /** Settings.Global key written by the LecoAuto module. */
    const val KEY = "leco_fullscreen"

    @Volatile
    private var sFullscreen = false

    private var sWatching = false

    /** Invoked on the main thread whenever the published state changes. */
    @Volatile
    var onChange: ((Boolean) -> Unit)? = null

    fun value(): Boolean = sFullscreen

    fun start(context: Context) {
        if (sWatching) return
        sWatching = true
        read(context)
        log(TAG, "started, initial fullscreen=$sFullscreen")
        try {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        read(context)
                        log(TAG, "changed to fullscreen=$sFullscreen")
                        onChange?.invoke(sFullscreen)
                    }
                })
        } catch (t: Throwable) {
            log(TAG, "registerContentObserver failed", t)
        }
    }

    private fun read(context: Context) {
        sFullscreen = try {
            Settings.Global.getInt(context.contentResolver, KEY, 0) != 0
        } catch (t: Throwable) {
            false
        }
    }
}
