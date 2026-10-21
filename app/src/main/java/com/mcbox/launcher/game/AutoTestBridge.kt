package com.mcbox.launcher.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mcbox.core.GamePaths
import com.mcbox.core.install.InstallProgress
import com.mcbox.core.install.ProgressListener
import com.mcbox.core.install.VersionInstaller
import com.mcbox.core.launch.VersionResolver
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import com.mcbox.core.model.VersionJson
import com.mcbox.launcher.data.Instance
import com.mcbox.launcher.data.InstanceStore
import com.mcbox.launcher.data.SettingsStore
import com.mcbox.launcher.runtime.JavaLauncher
import com.mcbox.launcher.runtime.RuntimeManager
import net.kdt.pojavlaunch.utils.JREUtils
import java.io.File

object AutoTestBridge {

    private const val TAG = "YinAutoTest"

    fun runFullTest(context: Context) {
        val appContext = context.applicationContext
        Thread {
            val report = File(appContext.getExternalFilesDir(null), "autotest-result.txt")
            val sb = StringBuilder()
            fun step(name: String, ok: Boolean, detail: String = "") {
                val line = "$name=${if (ok) "OK" else "FAIL"} $detail"
                Log.i(TAG, line)
                sb.append(line).append('\n')
                report.writeText(sb.toString())
            }

            try {
                val settings = SettingsStore(appContext)
                val paths = GamePaths(File(settings.gameRoot)).ensure()

                step("mkdirs", true, settings.gameRoot)

                val installed = paths.versions.listFiles()
                    ?.filter { it.isDirectory }
                    ?.map { it.name }
                    ?.sortedDescending()
                    .orEmpty()
                step("scan-installed", true, installed.joinToString(",").ifEmpty { "none" })

                val pickedId = installed.firstOrNull { File(paths.versionDir(it), "$it.json").exists() }
                val versionId: String
                val versionJson: VersionJson
                if (pickedId != null) {
                    versionId = pickedId
                    versionJson = com.mcbox.core.util.JsonCompact.decodeFromString(
                        VersionJson.serializer(),
                        File(paths.versionDir(pickedId), "$pickedId.json").readText()
                    )
                    step("reuse-installed", true, pickedId)
                } else {
                    val installer = VersionInstaller(paths, mirrors = settings.mirrors())
                    val manifest = installer.fetchManifest()
                    step("manifest", manifest.versions.isNotEmpty(), "count=${manifest.versions.size}")
                    val target = manifest.versions.first { it.type == "release" }
                    versionId = target.id
                    versionJson = installer.fetchVersionJson(target)
                    step("versionjson", versionJson.mainClass.isNotEmpty(), "id=${versionJson.id}")
                    installer.install(target.id, target.url, object : ProgressListener {
                        override fun onProgress(progress: InstallProgress) {}
                    }, includeAssets = false)
                    step("download-libs-client", true, target.id)
                }

                val store = InstanceStore(appContext)
                val instId = "inst-autotest-" + System.currentTimeMillis()
                store.upsert(
                    Instance(
                        id = instId,
                        name = "AutoTest " + versionId,
                        versionId = versionId,
                        loader = "vanilla",
                        javaMajor = 21
                    )
                )
                step("instance-created", true, instId)

                val runtimeManager = RuntimeManager(appContext, paths.runtimes)
                var runtime = runtimeManager.findForMajor(21)
                if (runtime == null) {
                    runtime = runtimeManager.installFromAssets(
                        name = "jre-21",
                        universalAsset = "runtime/jre21/universal.tar.xz",
                        binAsset = "runtime/jre21/bin-arm64.tar.xz"
                    )
                    step("jre-installed", runtime != null, "java=${runtime?.javaMajor}")
                } else {
                    step("jre-found", true, "java=${runtime.javaMajor}")
                }

                val launcher = JavaLauncher(
                    appContext,
                    appContext.applicationInfo.nativeLibraryDir
                )
                launcher.prepare(runtime!!, File(paths.root, "autotest"), paths.temp)

                val jliLib = File(runtime.home, "lib/libjli.so")
                val jliPreloaded = jliLib.exists() && net.kdt.pojavlaunch.utils.JREUtils.safeDlopen(jliLib.absolutePath)
                Log.i(TAG, "libjli preload: path=${jliLib.absolutePath} exists=${jliLib.exists()} ok=$jliPreloaded")
                step("jli-preload", jliPreloaded)

                val resolver = VersionResolver(paths)
                val merged = resolver.loadMerged(versionId)
                val classpath = resolver.buildClasspath(merged, com.mcbox.core.PlatformInfo.android())
                val account = Account(
                    id = AccountUuid.fromOfflineName("AutoTester"),
                    type = AccountType.Offline,
                    displayName = "AutoTester",
                    uuid = AccountUuid.fromOfflineName("AutoTester"),
                    accessToken = "0"
                )

                val exit = launcher.launch(
                    runtime = runtime,
                    config = com.mcbox.core.launch.LaunchConfig(
                        versionId = versionId,
                        versionJson = merged,
                        gameDir = File(paths.root, "autotest"),
                        assetsDir = paths.assets,
                        librariesDir = paths.libraries,
                        nativesDir = paths.nativeDir(versionId),
                        classpath = classpath,
                        account = account,
                        maxMemoryMb = 1024,
                        minMemoryMb = 512,
                        javaMajor = runtime.javaMajor
                    ),
                    resolver = resolver,
                    gameDir = File(paths.root, "autotest"),
                    cacheDir = paths.temp,
                    logFile = File(paths.logs, "autotest.log")
                )
                step("jvm-launched", exit >= 0, "exit=$exit")
                step("FULL_TEST_DONE", true)
            } catch (t: Throwable) {
                Log.e(TAG, "autotest failed", t)
                step("exception", false, t.message ?: t.javaClass.simpleName)
            }
        }.apply {
            name = "yin-autotest"
            isDaemon = true
            start()
        }
    }
}

class AutoTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("YinAutoTest", "broadcast received")
        AutoTestBridge.runFullTest(context)
    }
}