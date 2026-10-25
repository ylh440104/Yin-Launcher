package com.mcbox.launcher

import android.app.Application
import net.kdt.pojavlaunch.MainActivity as PojavMainBridge
import java.io.File

class McBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            PojavMainBridge.attach(this)
        } catch (t: Throwable) {
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(getExternalFilesDir(null), "mcbox")
                dir.mkdirs()
                File(dir, "crash.txt").writeText(
                    "Thread: ${thread.name}\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            } catch (ignored: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: McBoxApp
        @JvmStatic fun getAppNativeLibDir(): String = instance.applicationInfo.nativeLibraryDir
    }
}