package com.mcbox.launcher.runtime

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.utils.JREUtils
import net.kdt.pojavlaunch.utils.OldVersionsUtils
import net.kdt.pojavlaunch.plugins.FFmpegPlugin
import net.kdt.pojavlaunch.value.MinecraftAccount
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile
import java.io.File

object PojavGameLauncher {
    fun launch(
        activity: AppCompatActivity?,
        gameRoot: String,
        instanceDir: String,
        versionId: String,
        memoryMb: Int,
        username: String,
        uuid: String,
        accessToken: String,
        renderer: String
    ): Int {
        val rootFile = File(gameRoot)
        val rootParent = rootFile.parentFile?.absolutePath ?: gameRoot

        val ctx = activity?.applicationContext
            ?: throw IllegalArgumentException("context required")

        // 1) 先让 Pojav 初始化自己的常量与偏好（它会调用 initStorageConstants）
        Tools.initEarlyConstants(ctx)
        LauncherPreferences.loadPreferences(ctx)
        LauncherPreferences.PREF_RAM_ALLOCATION = memoryMb
        LauncherPreferences.PREF_RENDERER = renderer
        LauncherPreferences.PREF_CUSTOM_JAVA_ARGS = ""

        // 2) 再用本启动器的真实游戏目录覆盖（必须在 loadPreferences 之后）
        Tools.DIR_GAME_HOME = rootParent
        Tools.DIR_GAME_NEW = rootFile.absolutePath
        Tools.DIR_HOME_VERSION = rootFile.absolutePath + "/versions"
        Tools.DIR_HOME_LIBRARY = rootFile.absolutePath + "/libraries"
        Tools.ASSETS_PATH = rootFile.absolutePath + "/assets"
        Tools.DIR_HOME_CRASH = rootFile.absolutePath + "/crash-reports"
        Tools.OBSOLETE_RESOURCES_PATH = rootFile.absolutePath + "/resources"
        Tools.LOCAL_RENDERER = renderer

        val account = MinecraftAccount()
        account.username = username
        account.profileId = uuid
        account.accessToken = accessToken
        account.xuid = "0"

        val profile = MinecraftProfile()
        profile.javaArgs = ""
        profile.javaDir = ""

        val versionInfo = Tools.getVersionInfo(versionId)
        val requiredJava = versionInfo.javaVersion?.majorVersion ?: 8

        // 明确按版本要求选 Java：优先精确版本，其次最近的更高版本
        val available = MultiRTUtils.getRuntimes()
        android.util.Log.i("PojavGameLauncher",
            "MULTIRT_HOME=" + Tools.MULTIRT_HOME + " runtimes=" +
                available.joinToString { it.name + ":" + it.javaVersion })
        val exact = MultiRTUtils.getExactJreName(requiredJava)
        val nearest = MultiRTUtils.getNearestJreName(requiredJava)
        val runtimeName = exact ?: nearest
            ?: throw IllegalStateException(
                "No Java " + requiredJava + " runtime in " + Tools.MULTIRT_HOME +
                " (found: " + available.joinToString { it.name + ":" + it.javaVersion } + ")")
        val runtime = MultiRTUtils.forceReread(runtimeName)
        if (runtime.javaVersion == 0 || runtime.versionString == null) {
            throw IllegalStateException(
                "Runtime not valid: " + runtimeName + " at " + MultiRTUtils.getRuntimeHome(runtimeName) +
                " (missing or malformed release file)")
        }
        android.util.Log.i("PojavGameLauncher",
            "selected runtime=" + runtimeName + " java=" + runtime.javaVersion + " arch=" + runtime.arch)
        val gamedir = File(instanceDir)

        Tools.disableSplash(gamedir)
        val launchArgs = Tools.getMinecraftClientArgs(account, versionInfo, gamedir)
        OldVersionsUtils.selectOpenGlVersion(versionInfo)
        val launchClassPath = Tools.generateLaunchClassPath(versionInfo, versionId)
        val javaArgList = mutableListOf<String>()
        Tools.getCacioJavaArgs(javaArgList, runtime.javaVersion == 8)
        // logging.client.file 在新版 version.json 中可能缺少 id 字段，
        // 且 Tools.DIR_DATA 在未初始化时为 null，这里必须逐级判空。
        val loggingClient = versionInfo.logging?.client
        val loggingFile = loggingClient?.file
        val loggingId = loggingFile?.id
        if (loggingClient != null && loggingId != null) {
            val dataDir = Tools.DIR_DATA
            val patchedName = loggingId.replace("client", "log4j-rce-patch")
            val candidates = buildList {
                if (dataDir != null) add(File(dataDir, "security/$patchedName").absolutePath)
                add(File(Tools.DIR_GAME_NEW, loggingId).absolutePath)
            }
            candidates.firstOrNull { File(it).exists() }?.let { path ->
                javaArgList.add("-Dlog4j.configurationFile=$path")
            }
        }
        val cacheDir: File? = Tools.DIR_CACHE
        val nativesDir = cacheDir?.let { File(it, "natives/$versionId") }
        if (nativesDir != null && nativesDir.exists()) {
            javaArgList.add("-Djava.library.path=${nativesDir.absolutePath}:${Tools.NATIVE_LIB_DIR}")
            javaArgList.add("-Djna.boot.library.path=${nativesDir.absolutePath}")
        }
        javaArgList.addAll(Tools.getMinecraftJVMArgs(versionId, gamedir))
        javaArgList.add("-cp")
        javaArgList.add(launchClassPath + ":" + getLWJGL3ClassPath())
        javaArgList.add(versionInfo.mainClass)
        javaArgList.addAll(launchArgs)

        activity?.let { FFmpegPlugin.discover(it) }
        JREUtils.launchJavaVM(activity, runtime, gamedir, javaArgList, "")
        return JREUtils.lastExitCode
    }

    private fun getLWJGL3ClassPath(): String {
        val libStr = StringBuilder()
        val folder = File(Tools.DIR_GAME_HOME, "lwjgl3")
        val files = folder.listFiles()
        if (files != null) {
            for (f in files) {
                if (f.name.endsWith(".jar")) libStr.append(f.absolutePath).append(":")
            }
        }
        if (libStr.isNotEmpty()) libStr.setLength(libStr.length - 1)
        return libStr.toString()
    }
}