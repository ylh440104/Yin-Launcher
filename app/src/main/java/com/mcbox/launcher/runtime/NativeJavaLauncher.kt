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

        // Android 10+ 对 app 私有数据目录（app_data_file）禁止 exec，
        // 直接 ProcessBuilder 会得到 EACCES(error=13)。
        // 这里探测一次：不可执行时把整个 JRE 迁移到 app 的 codeCacheDir
        // （其 SELinux 标签允许执行），并以迁移后的路径启动。
        val execHome = ensureExecutableJre(context, jreHome, onLog)
        val execJava = File(execHome, "bin/java")

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

        val pb = ProcessBuilder(args)
        pb.directory(instanceDir)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", libPath)
            put("HOME", paths.root.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            // 让 LWJGL/glfw 走 Android 的 EGL 实现
            put("LIBGL_ES", "2")
        }

        val process = pb.start()
        val sb = StringBuilder()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                sb.append(line).append('\n')
                onLog(line)
            }
        }
        val exit = process.waitFor()
        return Result(exit, sb.toString())
    }

    /**
     * 返回一个「java 二进制可以被本 app 进程执行」的 JRE 根目录。
     *
     * 背景：Android 10+ 给 app 私有数据目录打了 app_data_file 标签，
     * SELinux 禁止从该标签执行文件，ProcessBuilder 会抛 EACCES(error=13)。
     * 而 codeCacheDir 的标签允许执行。所以先探测原目录，
     * 不可执行时把 JRE 复制到 codeCacheDir 下再返回。
     */
    private fun ensureExecutableJre(
        context: Context,
        jreHome: File,
        onLog: (String) -> Unit
    ): File {
        if (canExecuteDirectly(jreHome, onLog)) return jreHome

        val target = File(context.codeCacheDir, "jre-exec")
        val stamp = File(target, ".stamp")
        val want = "${jreHome.absolutePath}:${jreHome.lastModified()}"
        if (stamp.exists() && stamp.readText().trim() == want && File(target, "bin/java").exists()) {
            onLog("[launcher] reuse executable JRE at ${target.absolutePath}")
            return target
        }

        onLog("[launcher] app 数据目录不可执行，复制 JRE 到 ${target.absolutePath}")
        target.deleteRecursively()
        target.mkdirs()
        jreHome.copyRecursively(target, overwrite = true)
        // 恢复可执行位（copyRecursively 可能不保留）
        listOf("bin", "lib", "lib/server", "lib/jli").forEach { sub ->
            File(target, sub).listFiles()?.forEach { f ->
                if (f.isFile) f.setExecutable(true, false)
            }
        }
        stamp.writeText(want)
        onLog("[launcher] JRE 复制完成")
        return target
    }

    /** 用一次轻量的 exec 探测当前进程是否真的能执行 java。 */
    private fun canExecuteDirectly(jreHome: File, onLog: (String) -> Unit): Boolean {
        val java = File(jreHome, "bin/java")
        if (!java.exists()) return false
        if (!java.canExecute()) {
            onLog("[launcher] java 缺少执行位，尝试补上")
            java.setExecutable(true, false)
        }
        return try {
            val p = ProcessBuilder(java.absolutePath, "-version")
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        listOf(File(jreHome, "lib"), File(jreHome, "lib/server"))
                            .joinToString(":") { it.absolutePath }
                }
                .start()
            p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            onLog("[launcher] exec probe exit=$code")
            code == 0
        } catch (t: Throwable) {
            onLog("[launcher] exec probe failed: ${t.message}")
            false
        }
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