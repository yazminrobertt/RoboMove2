package com.robomove.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.robomove.app.robot.DamanHeadControl

class RoboMoveApplication : Application() {

    companion object {
        private const val TAG = "RoboMoveApplication"
    }

    private lateinit var headControl: DamanHeadControl
    private var activeActivityCount = 0

    override fun onCreate() {
        super.onCreate()

        headControl = DamanHeadControl(this)
        headControl.connect()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) {
                activeActivityCount++
                Log.d(TAG, "Activity started: ${activity.localClassName} — active=$activeActivityCount")
            }

            override fun onActivityStopped(activity: Activity) {
                activeActivityCount--
                Log.d(TAG, "Activity stopped: ${activity.localClassName} — active=$activeActivityCount")

                if (activeActivityCount == 0) {
                    // No activities visible — app is going to background or closing
                    Log.d(TAG, "App fully in background — resetting head to center")
                    headControl.resetToCenter()
                }
            }

            // Required overrides — leave empty
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}