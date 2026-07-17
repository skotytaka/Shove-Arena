package com.romansgame.shove

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.romansgame.shove.audio.MusicManager
import com.romansgame.shove.audio.SoundManager

class ShoveApp : Application(), Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        SoundManager.init(this)
        MusicManager.init(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) MusicManager.resume()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities <= 0) MusicManager.pause()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
