package com.mcbox.launcher.runtime

import android.content.Context
import android.util.Log
import com.mcbox.core.launch.ArgumentBuilder
import com.mcbox.core.launch.LaunchConfig
import com.mcbox.core.launch.VersionResolver
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File

class JavaLauncher(
    private val context: Context,
    private val nativeLibDir: String
) {

    companion object {
        private const val TAG = "McBoxJava"
    }

    var renderer: String = "opengles3"
    var rendererLibrary: String = "libgl4es_114.so"
    var glesVersion: String = "3"
    var zink: Boolean = false
    var bigCoreAffinity: Boolean = false
    var forceVsync: Boolean = false

    fun initJreHome(runtime: RuntimeInfo, gameDir: File, cacheDir: File) {
        Tools.init(context, gameDir.parentFile?.absolutePath ?: gameDir.absolutePath)
        Tools.LOCAL_RENDERER = renderer
        LauncherPreferences.loadPreferences(context)
        LauncherPreferences.PREF_RAM_ALLOCATION = configRam(runtime)
    }

    private fun configRam(runtime: RuntimeInfo): Int = 2048

    fun loadJvmLibrariesQuiet(runtime: RuntimeInfo) {
        try {
            JREUtils.ensureLoaded()
            JREUtils.relocateLibPath(runtime.home.absolutePath)
            JREUtils.jvmLibraryPath = runtime.jvmDir.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "preload failed", t)
        }
    }

    fun launch(
        activity: android.app.Activity?,
        runtime: RuntimeInfo,
        config: LaunchConfig,
        resolver: VersionResolver,
        gameDir: File,
        cacheDir: File,
        logFile: File
    ): Int {
        val jreHome = runtime.home.absolutePath

        if (Tools.DIR_DATA == null || Tools.DIR_CACHE == null) {
            Tools.init(context, gameDir.parentFile?.absolutePath ?: gameDir.absolutePath)
        }

        JREUtils.ensureLoaded()
        JREUtils.beginLog(logFile)
        JREUtils.appendLog("launching via zalith chain: $jreHome\n")

        val mainClass = config.versionJson.mainClass.ifEmpty { "net.minecraft.client.main.Main" }
        val builder = ArgumentBuilder()
        val gameArgs = mutableListOf<String>()
        gameArgs.add("-cp")
        gameArgs.add(config.classpath.joinToString(File.pathSeparator) { it.absolutePath })
        gameArgs.add(mainClass)
        gameArgs.addAll(builder.buildGameArgs(config))
        gameArgs.addAll(config.extraGameArgs)

        val zc = Class.forName("net.kdt.pojavlaunch.utils.ZalithUtils")
        fun setF(name: String, value: Any?) {
            val f = zc.getDeclaredField(name)
            f.isAccessible = true
            f.set(null, value)
        }
        setF("gameHome", gameDir.parentFile?.absolutePath ?: gameDir.absolutePath)
        setF("cacheDir", cacheDir)
        setF("dataDir", File(Tools.DIR_DATA ?: context.filesDir.parent ?: "/data/data/${context.packageName}"))
        setF("rendererId", renderer)
        setF("rendererLibrary", rendererLibrary)
        setF("ramAllocationMb", config.maxMemoryMb)
        setF("bigCoreAffinity", bigCoreAffinity)
        setF("forceVsync", forceVsync)
        setF("zink", zink)
        setF("resolutionRatio", 1.0f)
        setF("versionName", config.versionId)
        val m = zc.getMethod(
            "launchWithUtils",
            android.content.Context::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            String::class.java,
            Runnable::class.java
        )
        m.invoke(null, context, "arm64-v8a", jreHome, gameDir.absolutePath, gameArgs, "", null)
        return JREUtils.lastExitCode
    }

    fun offlineAccount(name: String): Account = Account(
        id = AccountUuid.fromOfflineName(name),
        type = AccountType.Offline,
        displayName = name,
        uuid = AccountUuid.fromOfflineName(name),
        accessToken = "0"
    )
}