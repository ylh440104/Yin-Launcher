package com.mcbox.launcher.runtime

import android.content.Context
import com.mcbox.core.GamePaths
import com.mcbox.core.model.VersionJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 自研启动器：完全不依赖 PojavLauncher 的 JNI/native 链，而是用 ProcessBuilder
 * 直接拉起 JRE 里的 java 二进制。
 *
 * 之所以这样做，是因为在设备上实测发现：JRE 本身完全正常
 *   LD_LIBRARY_PATH=<jre>/lib:<jre>/lib/server ./bin/java -version  → 正常输出
 * 而在进程内注入（JLI_Launch）时，动态链接器找不到 libjli.so，导致 JVM 静默退出。
 * 子进程方式让 Android linker 按 LD_LIBRARY_PATH 正常工作，且崩溃不会带走启动器本身。
 */
object NativeJavaLauncher {

    data class Result(val exitCode: Int, val output: String)

    fun launch(
        context: Context,
        paths: GamePaths,
        version: VersionJson,
        versionId: String,
        instanceDir: File,
        jreHome: File,
        username: String,
        uuid: String,
        accessToken: String,
        memoryMb: Int,
        width: Int,
        height: Int,
        onLog: (String) -> Unit
    ): Result {
        val javaBin = File(jreHome, "bin/java")
        require(javaBin.exists()) { "java 二进制不存在: ${javaBin.absolutePath}" }

        // Android 的 SELinux 禁止从 app 私有数据目录（app_data_file）执行任何文件，
        // 只有 nativeLibraryDir（apk_data_file）允许 exec。
        // 因此 CI 会把 JRE 的 bin/java 以 libjavaexec.so 的名字打进 jniLibs，
        // 安装后它就位于 nativeLibraryDir 里，可直接被 ProcessBuilder 执行。
        val execJava = resolveExecutableJava(context, onLog)
        val execHome = jreHome

        val libDir = File(execHome, "lib")
        val serverDir = File(libDir, "server")
        val jliDir = File(libDir, "jli")

        val classpath = buildClasspath(paths, version, versionId)
        onLog("[launcher] classpath entries = ${classpath.size}")

        val nativesDir = paths.nativeDir(versionId)
        val libPath = listOf(
            nativesDir.absolutePath,
            libDir.absolutePath,
            serverDir.absolutePath,
            jliDir.absolutePath,
        ).filter { File(it).exists() }.joinToString(":")

        val args = mutableListOf<String>()
        args += execJava.absolutePath
        args += "-Xmx${memoryMb}M"
        args += "-Xms512M"
        args += "-Djava.library.path=$libPath"
        args += "-Dorg.lwjgl.librarypath=$libPath"
        args += "-Djna.tmpdir=${context.cacheDir.absolutePath}"
        args += "-Djava.io.tmpdir=${context.cacheDir.absolutePath}"
        args += "-Duser.home=${paths.root.absolutePath}"
        args += "-Duser.language=en"
        args += "-Duser.country=US"
        args += "-Dos.name=Linux"
        args += "-Dos.version=Android"
        args += "-Dminecraft.launcher.brand=mcbox"
        args += "-Dminecraft.launcher.version=1.0"
        args += "-Dlog4j2.formatMsgNoLookups=true"
        args += "-cp"
        args += classpath.joinToString(":") { it.absolutePath }
        args += version.mainClass

        // ---- 游戏参数 ----
        val assetIndex = version.assetIndex?.id ?: version.assets ?: "legacy"
        args += listOf(
            "--username", username,
            "--version", versionId,
            "--gameDir", instanceDir.absolutePath,
            "--assetsDir", paths.assets.absolutePath,
            "--assetIndex", assetIndex,
            "--uuid", uuid.replace("-", ""),
            "--accessToken", accessToken.ifEmpty { "0" },
            "--userType", "msa",
            "--versionType", "release",
            "--userProperties", "{}",
            "--width", width.toString(),
            "--height", height.toString(),
        )

        // 版本自带的 game 参数（新版 arguments.game 是混合数组：字符串 + 带 rules 的对象）。
        // 只取纯字符串项，并跳过 ${...} 占位符（已由上面的显式参数覆盖）。
        version.arguments?.game
            ?.mapNotNull { el ->
                runCatching {
                    el.jsonPrimitive.contentOrNull
                }.getOrNull()
            }
            ?.filterNot { it.contains("\${") }
            ?.forEach { args += it }

        onLog("[launcher] java = ${execJava.absolutePath}")
        onLog("[launcher] main = ${version.mainClass}")
        onLog("[launcher] LD_LIBRARY_PATH = $libPath")

        // 关键：Android 的 SELinux 禁止 execve app 目录里的任何文件（error=13），
        // 但允许 dlopen 一个 shared object。libpojavexec.so 的 executeBinary()
        // 会 dlopen(argv[0]) 再 dlsym("main") 后调用，等价于「进程内执行 java」。
        // 因此这里不走 ProcessBuilder，而是调用该 native 入口。
        val argv = ArrayList<String>(args.size)
        argv += execJava.absolutePath // argv[0] = 要 dlopen 的 java 二进制
        argv += args.drop(1)          // 其余 JVM/游戏参数原样传递

        onLog("[launcher] invoking executeBinary, argv=${argv.size}")

        // dlopen 走 linker namespace，必须先把 JRE 的 lib 目录塞进该 namespace，
        // 否则 libjavaexec.so 依赖的 libjli.so 解析不到。
        // Kotlin 与 Java 混编时无法直接引用 net.kdt.* 的静态方法，这里统一走反射。
        val jreUtils = Class.forName("net.kdt.pojavlaunch.utils.JREUtils")
        try {
            jreUtils.getMethod("setLdLibraryPath", String::class.java).invoke(null, libPath)
            onLog("[launcher] setLdLibraryPath ok")
        } catch (t: Throwable) {
            onLog("[launcher] setLdLibraryPath failed: ${t.message}")
        }
        try {
            android.system.Os.setenv("LD_LIBRARY_PATH", libPath, true)
        } catch (t: Throwable) {
            onLog("[launcher] setenv failed: ${t.message}")
        }
        val exit = try {
            jreUtils.getMethod("executeBinary", Array<String>::class.java)
                .invoke(null, argv.toTypedArray()) as Int
        } catch (t: Throwable) {
            onLog("[launcher] executeBinary threw: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
        return Result(exit, "")
    }

    /**
     * 找到本进程真正可以执行的 java 启动器。
     *
     * Android 的 SELinux 只允许从 nativeLibraryDir（apk_data_file 标签）执行文件，
     * app 私有数据目录（files/、code_cache/ 等）全部禁止 exec，直接跑会得到
     * error=13 Permission denied。CI 构建时已把 JRE 的 bin/java 以
     * libjavaexec.so 的名字打进 jniLibs，安装后即位于 nativeLibraryDir。
     */
    private fun resolveExecutableJava(context: Context, onLog: (String) -> Unit): File {
        val fromLibs = File(context.applicationInfo.nativeLibraryDir, "libjavaexec.so")
        if (fromLibs.exists()) {
            onLog("[launcher] java exec = ${fromLibs.absolutePath} (nativeLibraryDir)")
            return fromLibs
        }
        val fallback = File(context.filesDir, "runtimes/jre-21/bin/java")
        onLog("[launcher] 警告: 未找到 libjavaexec.so，回退 ${fallback.absolutePath}（大概率 error=13）")
        return fallback
    }

    /** 收集 classpath：libraries 目录中实际存在的 jar + 版本 jar。 */
    private fun buildClasspath(
        paths: GamePaths,
        version: VersionJson,
        versionId: String
    ): List<File> {
        val result = LinkedHashSet<File>()

        // 1) 版本主 jar
        val versionJar = paths.versionJar(versionId)
        if (versionJar.exists()) result += versionJar
        version.inheritsFrom?.let { parent ->
            val parentJar = paths.versionJar(parent)
            if (parentJar.exists()) result += parentJar
        }

        // 2) libraries 下的所有 jar（libraries 布局已由下载器按 maven 路径建好）
        val libRoot = paths.libraries
        if (libRoot.exists()) {
            libRoot.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".jar") }
                // 排除 natives 分类构件，它们应解压到 natives 目录
                .filterNot { it.name.contains("-natives-") }
                .forEach { result += it }
        }

        // 3) 版本自带的 lwjgl3 替换 jar（若存在）
        val lwjgl = paths.lwjgl
        if (lwjgl.exists()) {
            lwjgl.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.forEach { result += it }
        }

        return result.toList()
    }
}