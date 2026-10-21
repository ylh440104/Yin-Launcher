package com.mcbox.launcher.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import com.mcbox.core.launch.ArgumentBuilder
import com.mcbox.core.launch.LaunchConfig
import com.mcbox.core.launch.VersionResolver
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import com.oracle.dalvik.VMLauncher
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

    private var prepared = false

    fun buildEnvironment(runtime: RuntimeInfo, gameDir: File, cacheDir: File): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        val libPath = buildLdLibraryPath(runtime)
        JREUtils.LD_LIBRARY_PATH = libPath

        env["POJAV_NATIVEDIR"] = nativeLibDir
        env["JAVA_HOME"] = runtime.home.absolutePath
        env["HOME"] = gameDir.absolutePath
        env["TMPDIR"] = cacheDir.absolutePath
        env["PATH"] = runtime.home.absolutePath + "/bin:" + (System.getenv("PATH") ?: "/system/bin")
        env["LD_LIBRARY_PATH"] = libPath
        env["LIBGL_MIPMAP"] = "3"
        env["LIBGL_NOERROR"] = "1"
        env["LIBGL_NOINTOVLHACK"] = "1"
        env["LIBGL_NORMALIZE"] = "1"
        env["LIBGL_ES"] = glesVersion
        env["FORCE_VSYNC"] = if (forceVsync) "true" else "false"
        env["MESA_GLSL_CACHE_DIR"] = cacheDir.absolutePath
        env["force_glsl_extensions_warn"] = "true"
        env["allow_higher_compat_version"] = "true"
        env["allow_glsl_extension_directive_midshader"] = "true"
        env["POJAV_RENDERER"] = renderer
        env["AWTSTUB_WIDTH"] = CallbackBridge.windowWidth.toString()
        env["AWTSTUB_HEIGHT"] = CallbackBridge.windowHeight.toString()
        if (zink) {
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["POJAV_VSYNC_IN_ZINK"] = "1"
        }
        if (bigCoreAffinity) {
            env["POJAV_BIG_CORE_AFFINITY"] = "1"
        }
        if (renderer == "opengles3_ltw") {
            env["LIBGL_ES"] = "3"
            env["POJAVEXEC_EGL"] = "libltw.so"
        }
        return env
    }

    fun buildLdLibraryPath(runtime: RuntimeInfo): String {
        val sb = StringBuilder()
        sb.append(File(runtime.home, "lib/jli").absolutePath).append(":")
        sb.append(File(runtime.home, "lib").absolutePath).append(":")
        sb.append(runtime.jvmDir.absolutePath).append(":")
        sb.append(nativeLibDir).append(":")
        sb.append("/system/lib64:/vendor/lib64:/vendor/lib64/hw:/system/lib:/vendor/lib:/vendor/lib/hw")
        return sb.toString()
    }

    fun applyEnvironment(env: Map<String, String>) {
        for ((key, value) in env) {
            try {
                Os.setenv(key, value, true)
            } catch (t: Throwable) {
                Log.w(TAG, "setenv failed: $key", t)
            }
        }
    }

    fun loadJvmLibraries(runtime: RuntimeInfo) {
        val jvmLib = File(runtime.jvmDir, "libjvm.so")
        Log.i(TAG, "jvm library: ${jvmLib.absolutePath}")
        JREUtils.dlopen(File(runtime.home, "lib/jli/libjli.so").absolutePath)
        if (!JREUtils.dlopen(jvmLib.absolutePath)) {
            JREUtils.dlopen("libjvm.so")
        }
        listOf(
            "libverify.so", "libjava.so", "libnet.so", "libnio.so",
            "libawt.so", "libawt_headless.so", "libfreetype.so", "libfontmanager.so",
            "libjimage.so", "libzip.so", "libjsig.so", "libmanagement.so"
        ).forEach { name ->
            val candidates = listOf(
                File(runtime.home, "lib/$name"),
                File(runtime.jvmDir, name),
                File(nativeLibDir, name)
            )
            for (candidate in candidates) {
                if (candidate.exists()) {
                    JREUtils.dlopen(candidate.absolutePath)
                    break
                }
            }
        }
        val ftNative = File(nativeLibDir, "libfreetype.so")
        if (ftNative.exists()) JREUtils.dlopen(ftNative.absolutePath)
        val openal = File(nativeLibDir, "libopenal.so")
        if (openal.exists()) JREUtils.dlopen(openal.absolutePath)
    }

    fun loadJvmLibrariesQuiet(runtime: RuntimeInfo) {
        try {
            loadJvmLibraries(runtime)
        } catch (t: Throwable) {
            Log.w(TAG, "native preload failed", t)
        }
    }

    fun prepare(runtime: RuntimeInfo, gameDir: File, cacheDir: File) {
        if (prepared) return
        val env = buildEnvironment(runtime, gameDir, cacheDir)
        applyEnvironment(env)
        JREUtils.setLdLibraryPath(buildLdLibraryPath(runtime))
        loadJvmLibraries(runtime)
        JREUtils.setupExitMethod(context.applicationContext)
        prepared = true
    }

    fun launch(
        runtime: RuntimeInfo,
        config: LaunchConfig,
        resolver: VersionResolver,
        gameDir: File,
        cacheDir: File,
        logFile: File
    ): Int {
        prepare(runtime, gameDir, cacheDir)

        JREUtils.beginLog(logFile)
        JREUtils.initializeHooks()
        JREUtils.chdir(gameDir.absolutePath)

        val builder = ArgumentBuilder()
        val jvmArgs = mutableListOf<String>()

        jvmArgs.add("-Xms${config.minMemoryMb.coerceAtLeast(512)}M")
        jvmArgs.add("-Xmx${config.maxMemoryMb}M")
        jvmArgs.add("-Dorg.lwjgl.opengl.libname=$rendererLibrary")
        jvmArgs.add("-Dorg.lwjgl.freetype.libname=$nativeLibDir/libfreetype.so")
        jvmArgs.add("-Dorg.lwjgl.librarypath=$nativeLibDir")
        jvmArgs.add("-Djava.library.path=$nativeLibDir:${config.nativesDir.absolutePath}")
        jvmArgs.add("-Djna.boot.library.path=$nativeLibDir")
        jvmArgs.add("-Djna.tmpdir=$cacheDir")
        jvmArgs.add("-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}")
        jvmArgs.add("-Dfile.encoding=UTF-8")
        jvmArgs.add("-Duser.home=${gameDir.absolutePath}")
        jvmArgs.add("-Duser.dir=${gameDir.absolutePath}")
        jvmArgs.add("-Dminecraft.launcher.brand=mcbox")
        jvmArgs.add("-Dminecraft.launcher.version=1.0.0")

        config.versionJson.logging?.client?.let { logging ->
            val logConfig = File(gameDir, "logging/${File(logging.file.path).name}")
            if (logConfig.exists()) {
                jvmArgs.add(logging.argument.replace("\${path}", logConfig.absolutePath))
            }
        }

        jvmArgs.addAll(sanitizeJvmArgs(builder.buildJvmArgs(config)))
        jvmArgs.addAll(config.extraJvmArgs)

        val mainClass = config.versionJson.mainClass.ifEmpty { "net.minecraft.client.main.Main" }
        val gameArgs = builder.buildGameArgs(config)
        val allArgs = mutableListOf<String>()
        allArgs.add("java")
        allArgs.addAll(jvmArgs)
        allArgs.add(mainClass)
        allArgs.addAll(gameArgs)
        allArgs.addAll(config.extraGameArgs)

        JREUtils.appendLog("launch: $mainClass")
        JREUtils.appendLog("args: " + allArgs.joinToString(" "))

        val exit = VMLauncher.launchJVM(allArgs.toTypedArray())
        JREUtils.appendLog("jvm exit: $exit")
        return exit
    }

    private fun sanitizeJvmArgs(args: List<String>): List<String> {
        val blocked = listOf(
            "-Xms", "-Xmx", "-d32", "-d64", "-Xint",
            "-XX:+UseTransparentHugePages", "-XX:+UseLargePagesInMetaspace",
            "-XX:+UseLargePages", "-Dorg.lwjgl.opengl.libname",
            "-Dorg.lwjgl.freetype.libname", "-XX:ActiveProcessorCount",
            "-Djava.library.path", "-cp", "-classpath", "-client", "-server"
        )
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg == "-cp" || arg == "-classpath") {
                i += 2
                continue
            }
            if (blocked.any { arg.startsWith(it) }) {
                i++
                continue
            }
            out.add(arg)
            i++
        }
        return out
    }

    fun buildClasspathString(jars: List<File>): String =
        jars.joinToString(File.pathSeparator) { it.absolutePath }

    fun detectArch(): String = System.getProperty("os.arch") ?: "aarch64"

    fun offlineAccount(name: String): Account = Account(
        id = AccountUuid.fromOfflineName(name),
        type = AccountType.Offline,
        displayName = name,
        uuid = AccountUuid.fromOfflineName(name),
        accessToken = "0"
    )
}