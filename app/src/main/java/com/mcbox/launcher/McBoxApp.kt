package com.mcbox.launcher

import android.app.Application
import net.kdt.pojavlaunch.MainActivity as PojavMainBridge

class McBoxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            PojavMainBridge.attach(this)
        } catch (t: Throwable) {
        }
    }

    companion object {
        lateinit var instance: McBoxApp
        @JvmStatic fun getAppNativeLibDir(): String = instance.applicationInfo.nativeLibraryDir
    }
}