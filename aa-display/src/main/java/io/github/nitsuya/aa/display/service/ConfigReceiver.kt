package io.github.nitsuya.aa.display.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess

/**
 * Writes a single AADisplay setting for callers that cannot use the settings screen: the screen is
 * small on a head unit and some values are easier to switch from an automation app.
 *
 * ```
 * adb shell am broadcast -n io.github.nitsuya.aa.display/.service.ConfigReceiver \
 *     -a aa.display.action.SET_CONFIG --es key VirtualDisplayDpi --es value 240
 * ```
 *
 * The `am` command runs inside the system process, so the caller cannot be told apart from a normal
 * app here; the same is true for the existing `SelfStartReceiver`. The receiver only writes the
 * module's own preferences, so it is kept without a permission check.
 */
class ConfigReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AADisplay_ConfigReceiver"
        const val ACTION_SET_CONFIG = "aa.display.action.SET_CONFIG"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        const val EXTRA_TYPE = "type"

        private const val TYPE_BOOLEAN = "boolean"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_CONFIG) return
        try {
            val key = intent.getStringExtra(EXTRA_KEY)?.trim().orEmpty()
            val value = intent.getStringExtra(EXTRA_VALUE)?.trim() ?: return
            if (key.isEmpty()) return
            val editor = SharedPreferencesAccess.openForHooks(context, AADisplayConfig.ConfigName).edit()
            if (intent.getStringExtra(EXTRA_TYPE)?.lowercase() == TYPE_BOOLEAN) {
                editor.putBoolean(key, value.toBoolean())
            } else {
                editor.putString(key, value)
            }
            val saved = editor.commit()
            SharedPreferencesAccess.makeReadableForHooks(context, AADisplayConfig.ConfigName)
            Log.i(TAG, "config $key=$value saved=$saved")
        } catch (t: Throwable) {
            Log.e(TAG, "config change failed", t)
        }
    }
}

