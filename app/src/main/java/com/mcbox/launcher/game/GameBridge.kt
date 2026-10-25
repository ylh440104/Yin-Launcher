package com.mcbox.launcher.game

import android.content.Intent
import android.util.Log
import com.mcbox.core.GamePaths
import com.mcbox.core.PlatformInfo
import com.mcbox.core.launch.ArgumentBuilder
import com.mcbox.core.launch.LaunchConfig
import com.mcbox.core.launch.VersionResolver
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import com.mcbox.launcher.data.InstanceStore
import com.mcbox.launcher.data.SettingsStore
import com.mcbox.launcher.runtime.JavaLauncher
import com.mcbox.launcher.runtime.NativeSelfCheck
import com.mcbox.launcher.runtime.RuntimeManager
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File

object GameBridge {

    @Volatile
    private var thread: Thread? = null
    private val exited = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(activity: GameActivity, intent: Intent, holder: android.view.SurfaceHolder?, widthPx: Int, heightPx: Int) {
        val instanceId = intent.getStringExtra(GameActivity.EXTRA_INSTANCE) ?: return
        val settings = SettingsStore(activity)
        val instanceStore = InstanceStore(activity)
        val instance = instanceStore.find(instanceId)
        if (instance == null) {
            activity.appendStatus("实例不存在: $instanceId")
            return
        }

        val paths = settings.paths()
        val javaMajor = intent.getIntExtra(GameActivity.EXTRA_JAVA_MAJOR, instance.javaMajor)
        val memory = intent.getIntExtra(GameActivity.EXTRA_MEMORY, settings.memoryMb)
        val renderer = intent.getStringExtra(GameActivity.EXTRA_RENDERER) ?: settings.renderer
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)

        val runtimeManager = RuntimeManager(activity, paths.runtimes)
        val runtime = runtimeManager.findForMajor(javaMajor)
        if (runtime == null) {
            activity.onGameExit(-3)
            return
        }

        val instanceDir = File(paths.instances, instance.id).apply { mkdirs() }
        val account = resolveAccount(settings, instance.accountId)

        thread = Thread {
            try {
                activity.appendStatus("自检原生库")
                val check = NativeSelfCheck.run(activity, activity.applicationInfo.nativeLibraryDir)
                Log.i("GameBridge", "selfcheck allOk=${check.allOk} msg=${check.message}")
                JREUtils.appendLog("selfcheck allOk=${check.allOk} ${check.message}")
                activity.appendStatus(check.message)
                if (!check.allOk) {
                    activity.appendStatus("原生库不可用，已停止启动，避免崩溃")
                    exited.set(true)
                    activity.onGameExit(-1)
                    return@Thread
                }
                activity.appendStatus("加载 JVM 库")
                JavaLauncher(activity.applicationContext, activity.applicationInfo.nativeLibraryDir)
                    .loadJvmLibrariesQuiet(runtime)
                CallbackBridge.sendUpdateWindowSize(width, height)
                activity.appendStatus("解析版本 ${instance.versionId}")
                val resolver = VersionResolver(paths)
                val merged = resolver.loadMerged(instance.versionId)
                val platform = PlatformInfo.android()
                val classpath = buildList {
                    addAll(resolver.buildClasspath(merged, platform, includeLwjgl = false))
                    addAll(collectLwjglJars(paths))
                    val jar = paths.versionJar(instance.versionId)
                    if (jar.exists()) add(jar)
                    else {
                        val parentId = merged.inheritsFrom
                        if (parentId != null) {
                            val parentJar = paths.versionJar(parentId)
                            if (parentJar.exists()) add(parentJar)
                        }
                    }
                }

                val nativesDir = prepareNatives(paths, merged, platform, instance.id, activity)
                val config = LaunchConfig(
                    versionId = instance.versionId,
                    versionJson = merged,
                    gameDir = instanceDir,
                    assetsDir = paths.assets,
                    librariesDir = paths.libraries,
                    nativesDir = nativesDir,
                    classpath = classpath,
                    account = account,
                    maxMemoryMb = memory,
                    minMemoryMb = minOf(512, memory / 2),
                    width = width,
                    height = height,
                    javaMajor = runtime.javaMajor,
                    extraJvmArgs = instance.jvmArgs.split(' ').filter { it.isNotBlank() },
                    extraGameArgs = instance.gameArgs.split(' ').filter { it.isNotBlank() },
                    serverAddress = instance.server,
                    userType = if (account.type == AccountType.Microsoft) "msa" else "legacy",
                    versionType = merged.type.ifEmpty { "release" }
                )

                val launcher = JavaLauncher(activity.applicationContext, activity.applicationInfo.nativeLibraryDir)
                launcher.renderer = renderer
                launcher.rendererLibrary = settings.rendererLibrary
                launcher.glesVersion = settings.glesVersion
                launcher.zink = settings.zink
                launcher.forceVsync = settings.vsync
                launcher.bigCoreAffinity = settings.bigCore

                activity.appendStatus("启动 JVM (Java ${runtime.javaMajor})")
                exited.set(false)
                val logFile = File(paths.logs, "game-${System.currentTimeMillis()}.log")
                val exit = launcher.launch(
                    activity = activity,
                    runtime = runtime,
                    config = config,
                    resolver = resolver,
                    gameDir = instanceDir,
                    cacheDir = activity.cacheDir,
                    logFile = logFile
                )
                exited.set(true)
                activity.onGameExit(exit)
            } catch (t: Throwable) {
                JREUtils.appendLog("launch failed: ${t.stackTraceToString()}")
                exited.set(true)
                activity.onGameExit(-2)
                activity.appendStatus("启动失败: ${t.message}")
            }
        }.apply {
            name = "mcbox-jvm"
            start()
        }
    }

    fun copyLogs(paths: GamePaths): File {
        val target = File(paths.logs, "latest.log")
        val all = paths.logs.listFiles()?.filter { it.name.startsWith("game-") }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull()
        if (all != null && all.exists()) {
            all.copyTo(target, overwrite = true)
        }
        return target
    }

    private fun resolveAccount(settings: SettingsStore, accountId: String?): Account {
        val accounts = settings.accounts()
        val found = accounts.firstOrNull { it.id == accountId }
        if (found != null) return found
        val first = accounts.firstOrNull()
        if (first != null) return first
        val name = settings.playerName.ifBlank { "Player" }
        return Account(
            id = AccountUuid.fromOfflineName(name),
            type = AccountType.Offline,
            displayName = name,
            uuid = AccountUuid.fromOfflineName(name),
            accessToken = "0"
        )
    }

    private fun collectLwjglJars(paths: GamePaths): List<File> {
        val dir = paths.lwjgl
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }.orEmpty()
    }

    private fun prepareNatives(
        paths: GamePaths,
        version: com.mcbox.core.model.VersionJson,
        platform: PlatformInfo,
        instanceId: String,
        activity: GameActivity
    ): File {
        val nativesDir = paths.nativeDir(instanceId)
        if (!nativesDir.exists()) nativesDir.mkdirs()
        val resolver = VersionResolver(paths)
        val natives = resolver.nativeLibraries(version, platform)
        for ((lib, classifier) in natives) {
            val art = lib.downloads?.classifiers?.get(classifier) ?: continue
            val jar = paths.libraryPath(art.path)
            if (!jar.exists()) continue
            extractNatives(jar, nativesDir)
        }
        return nativesDir
    }

    private fun extractNatives(jar: File, targetDir: File) {
        java.util.zip.ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.endsWith(".so") && !name.endsWith(".dylib") && !name.endsWith(".dll")) continue
                val fileName = File(name).name
                val out = File(targetDir, fileName)
                if (out.exists() && out.length() == entry.size) continue
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}