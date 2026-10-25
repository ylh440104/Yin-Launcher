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
        Tools.LOCAL_RENDERER = renderer
        LauncherPreferences.loadPreferences(context)
        LauncherPreferences.PREF_RAM_ALLOCATION = config.maxMemoryMb

        JREUtils.ensureLoaded()
        JREUtils.beginLog(logFile)
        JREUtils.appendLog("launching via pojav chain: $jreHome\n")

        val builder = ArgumentBuilder()

        val extraJvmArgs = mutableListOf<String>()
        extraJvmArgs.add("-Dorg.lwjgl.opengl.libname=$rendererLibrary")
        extraJvmArgs.add("-Dorg.lwjgl.freetype.libname=$nativeLibDir/libfreetype.so")
        extraJvmArgs.add("-Dorg.lwjgl.librarypath=$nativeLibDir")
        extraJvmArgs.add("-Djava.library.path=$nativeLibDir:${config.nativesDir.absolutePath}")
        extraJvmArgs.add("-Djna.boot.library.path=$nativeLibDir")
        extraJvmArgs.add("-Djna.tmpdir=${cacheDir.absolutePath}")
        extraJvmArgs.add("-Dfile.encoding=UTF-8")
        extraJvmArgs.add("-Dminecraft.launcher.brand=yin")
        extraJvmArgs.add("-Dminecraft.launcher.version=1.0.0")

        config.versionJson.logging?.client?.let { logging ->
            val logConfig = File(gameDir, "logging/${File(logging.file.path).name}")
            if (logConfig.exists()) {
                extraJvmArgs.add(logging.argument.replace("\${path}", logConfig.absolutePath))
            }
        }

        extraJvmArgs.addAll(builder.buildJvmArgs(config).filter { arg ->
            !arg.startsWith("-Xms") && !arg.startsWith("-Xmx") && !arg.startsWith("-cp") && arg != "-classpath"
        })
        extraJvmArgs.addAll(config.extraJvmArgs)

        val mainClass = config.versionJson.mainClass.ifEmpty { "net.minecraft.client.main.Main" }
        val allJvmArgs = mutableListOf<String>()
        allJvmArgs.add("-cp")
        allJvmArgs.add(config.classpath.joinToString(File.pathSeparator) { it.absolutePath })
        allJvmArgs.addAll(extraJvmArgs)
        allJvmArgs.add(mainClass)
        allJvmArgs.addAll(builder.buildGameArgs(config))
        allJvmArgs.addAll(config.extraGameArgs)

        JREUtils.launchJavaVM(activity, jreHome, gameDir, allJvmArgs, "")

        JREUtils.appendLog("pojav chain exit: ${JREUtils.lastExitCode}")
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