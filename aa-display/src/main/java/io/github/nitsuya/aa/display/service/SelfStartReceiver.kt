package io.github.nitsuya.aa.display.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.util.Log
import io.github.nitsuya.aa.display.util.getGmsCarFirstPartyManager
import io.github.nitsuya.aa.display.util.startCarAaDisplay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens AADisplay without the user having to pick it in the Android Auto app list.
 *
 * Android Auto 17.8 rejects the app decorator request (CarUiEntry.startCarActivity) unless the intent
 * is a navigation or dial intent, so the request cannot be made from the Android Auto process. The
 * car API that AADisplay itself uses to come back to the front is used instead: it connects to the
 * car service and asks it to show AADisplay's car activity.
 */
class SelfStartReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "AADisplay_SelfStart"
        const val ACTION_SELF_START = "aa.display.action.SELF_START"
        private const val MAX_ATTEMPTS = 12
        private const val RETRY_DELAY_MS = 1500L
        private val running = AtomicBoolean(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var attempts = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SELF_START) return
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "self start already in progress")
            return
        }
        Log.i(TAG, "self start: connecting to the car service")
        car = try {
            Car.createCar(context.applicationContext, object : CarConnectionCallback() {
                override fun onConnected(car: Car) {
                    Log.i(TAG, "self start: car connected")
                    askCarToShowAaDisplay(car)
                }

                override fun onDisconnected(car: Car) {
                    Log.i(TAG, "self start: car disconnected")
                    finish()
                }
            }).apply { connect() }
        } catch (t: Throwable) {
            Log.e(TAG, "self start: Car.createCar failed", t)
            finish()
            null
        }
    }

    private fun askCarToShowAaDisplay(car: Car) {
        val request = object : Runnable {
            override fun run() {
                if (attempts >= MAX_ATTEMPTS) {
                    Log.i(TAG, "self start: giving up after $MAX_ATTEMPTS attempts")
                    finish()
                    return
                }
                attempts++
                val manager = car.getGmsCarFirstPartyManager()
                Log.i(TAG, "self start: attempt $attempts/$MAX_ATTEMPTS, manager=${manager != null}")
                try {
                    manager.startCarAaDisplay()
                } catch (t: Throwable) {
                    Log.e(TAG, "self start: startCarActivity failed", t)
                }
                handler.postDelayed(this, RETRY_DELAY_MS)
            }
        }
        handler.post(request)
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        runCatching { car?.disconnect() }
        car = null
        attempts = 0
        running.set(false)
        Log.i(TAG, "self start: done")
    }
}
