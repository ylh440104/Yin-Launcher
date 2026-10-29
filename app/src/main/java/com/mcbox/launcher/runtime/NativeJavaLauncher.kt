package com.mcbox.launcher.runtime

import android.os.ParcelFileDescriptor
import android.system.Os
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

        // JRE 的 bin/java 与 libjli.so 都依赖 libz.so，但 Android 版 JRE 不含它
        // （原版由 linker namespace 从 /system/lib64 解析；走 dlopen 时解析不到）。
        // 这里把系统库补进 JRE 的 lib 目录，让同一 namespace 内可以解析。
        ensureRuntimeLibs(libDir, onLog)

        // Android 版 LWJGL（Pojav 构建，aarch64 + EGL 桥接）必须排在官方 lwjgl 之前，
        // 否则会用 linux/x64 的 liblwjgl.so，在 arm64 设备上直接 UnsatisfiedLinkError。
        val lwjglOverride = prepareLwjglOverride(context, paths, onLog)
        val classpath = buildClasspath(paths, version, versionId, lwjglOverride)
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
            ?.filterNot { it.contains("$" + "{") }
            ?.forEach { args += it }

        onLog("[launcher] java = ${execJava.absolutePath}")
        onLog("[launcher] main = ${version.mainClass}")
        onLog("[launcher] LD_LIBRARY_PATH = $libPath")

        // 不再用 JLI_Launch：它内部必定 execve(<java.home>/bin/java)，
        // 而 untrusted_app 域禁止 exec app_data_dir（error=13）。
        // 改为 dlopen(libjvm.so) + JNI_CreateJavaVM，全程无 exec。
        val libjvm = File(serverDir, "libjvm.so")
        require(libjvm.exists()) { "libjvm.so 不存在: ${libjvm.absolutePath}" }
        onLog("[launcher] libjvm = ${libjvm.absolutePath}")

        // 工作目录必须在游戏目录（JVM 与 MC 都按 cwd 解析相对路径）
        try {
            val rc = Class.forName("net.kdt.pojavlaunch.utils.JREUtils")
                .getMethod("chdir", String::class.java)
                .invoke(null, paths.root.absolutePath) as Int
            onLog("[launcher] chdir ${paths.root.absolutePath} = $rc")
        } catch (t: Throwable) {
            onLog("[launcher] chdir failed: ${t.message}")
        }

        val jvmOptions = ArrayList<String>()
        jvmOptions += "-Djava.home=${execHome.absolutePath}"
        // 走 JNI_CreateJavaVM 时没有 java launcher 代劳这些初始化
        jvmOptions += "-Dsun.java.command=${version.mainClass}"
        // Fabric 定位游戏 jar：显式给出，避免它猜 classpath 而失败
        // fabric 版本身没有 jar，真正的 MC jar 在 inheritsFrom 指向的原版目录
        val clientJar = listOfNotNull(
            paths.versionJar(versionId).takeIf { it.exists() },
            version.inheritsFrom?.let { paths.versionJar(it) }?.takeIf { it.exists() }
        ).firstOrNull()
        if (clientJar != null) {
            jvmOptions += "-Dfabric.gameJarPath=${clientJar.absolutePath}"
            onLog("[launcher] fabric.gameJarPath = ${clientJar.absolutePath}")
        }
        // Fabric 的 game dir（mods/config 都在这里）
        jvmOptions += "-Dfabric.gameDir=${instanceDir.absolutePath}"
        jvmOptions += "-Djava.launcher.path=${execHome.absolutePath}/bin/java"
        jvmOptions += "-Dsun.java.launcher=SUN_STANDARD"
        jvmOptions += "-Dos.arch=aarch64"
        jvmOptions += "-Djava.class.path=" + classpath.joinToString(":") { it.absolutePath }
        jvmOptions += "-Dsun.boot.library.path=${libDir.absolutePath}"
        jvmOptions += "-Djava.library.path=$libPath"
        // LWJGL 在 Android 上从 nativeLibraryDir 找 so；org.lwjgl.librarypath 必须包含它
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        jvmOptions += "-Dorg.lwjgl.librarypath=$nativeLibDir"
        // gl4es：OpenGL 2.x -> OpenGL ES 2/3 转译（Pojav 的默认渲染后端）
        jvmOptions += "-DLIBGL_ES=2"
        jvmOptions += "-Dorg.lwjgl.opengl.libname=libgl4es_114.so"
        onLog("[launcher] nativeLibDir = $nativeLibDir")
        jvmOptions += "-Xmx${memoryMb}M"
        // 不要限 -Xss：MethodHandle/MethodHandles.Lookup 在 fabric 里会深层调用，
        // 栈太小会触发 NoSuchMethodError(invokeVirtual/invokeSpecial) 链。
        jvmOptions += "-XX:+UseSerialGC"
        // 受限环境下 JIT 生成的方法句柄调用点可能解析失败（NoSuchMethodError
        // invokeVirtual/invokeSpecial 链），先只跑 C1 保证正确性。
        jvmOptions += "-Dfile.encoding=UTF-8"
        jvmOptions += "-Duser.home=${paths.root.absolutePath}"
        jvmOptions += "-Duser.dir=${paths.root.absolutePath}"
        jvmOptions += "-Dstdout.encoding=UTF-8"
        jvmOptions += "-Dstderr.encoding=UTF-8"
        // 版本 json 里的 JVM 参数（Fabric 的 -DFabricMcEmu 等）
        for (a in args.drop(1)) {
            if (a.startsWith("-D") || a.startsWith("-X") || a.startsWith("-XX:")) {
                if (a.startsWith("-Djava.class.path")) continue
                jvmOptions += a
            }
        }
        // JVM 自身日志写盘，排查崩溃不受 System.out 影响
        // 只保留轻量诊断标签：all=info 会打开 verification/dependencies 等重量级日志，
        // 在受限环境下频繁 deopt 会打乱 LambdaForm 自举（NoSuchMethodError 链）。
        jvmOptions += "-Xlog:exceptions=info,class+load=info,os+thread=info:file=${paths.root.absolutePath}/jvm-xlog.txt:time,level,tags"

        onLog("[launcher] creating JVM (dedicated thread), options=${jvmOptions.size}")
        val gameArgs2 = ArrayList(args.drop(args.indexOf(version.mainClass) + 1))
        val rcs = try {
            JvmBridge.nativeLaunch(
                libjvm.absolutePath,
                jvmOptions.toTypedArray(),
                version.mainClass,
                gameArgs2.toTypedArray(),
                classpath.map { it.absolutePath }.toTypedArray()
            )
        } catch (t: Throwable) {
            var c: Throwable? = t
            while (c?.cause != null) c = c.cause
            onLog("[launcher] nativeLaunch threw: ${t.javaClass.simpleName}: ${t.message}")
            onLog("[launcher] root cause: ${c?.javaClass?.name}: ${c?.message}")
            c?.stackTrace?.take(10)?.forEach { onLog("[launcher]   at $it") }
            null
        }
        val vmRc = rcs?.getOrNull(0) ?: -999
        val mainRc = rcs?.getOrNull(1) ?: -998
        onLog("[launcher] JNI_CreateJavaVM rc=$vmRc, main rc=$mainRc")
        return Result(mainRc, "")

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

    /**
     * 把 assets/lwjgl3/lwjgl-glfw-classes.jar（Pojav 的 Android 版 LWJGL）解压到
     * 可读目录，供 classpath 使用。
     *
     * 官方 LWJGL 的 glfw 走 X11/GLX，Android 上没有；Pojav 的这份改成 EGL +
     * 直接操控 Surface，且自带 aarch64 的 liblwjgl.so。
     */
    private fun prepareLwjglOverride(
        context: Context,
        paths: GamePaths,
        onLog: (String) -> Unit
    ): File? {
        val out = File(paths.root, "lwjgl3/lwjgl-glfw-classes.jar")
        return try {
            out.parentFile?.mkdirs()
            val need = !out.exists() || out.length() < 100_000L
            if (need) {
                context.assets.open("lwjgl3/lwjgl-glfw-classes.jar").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                onLog("[launcher] 已解压 Android LWJGL -> ${out.absolutePath} (${out.length()} 字节)")
            } else {
                onLog("[launcher] Android LWJGL 已就绪 (${out.length()} 字节)")
            }
            out
        } catch (t: Throwable) {
            onLog("[launcher] 解压 Android LWJGL 失败: ${t.message}")
            null
        }
    }

    /** 收集 classpath：libraries 目录中实际存在的 jar + 版本 jar。 */
    private fun buildClasspath(
        paths: GamePaths,
        version: VersionJson,
        versionId: String,
        lwjglOverride: File?
    ): List<File> {
        val result = LinkedHashSet<File>()
        // Android 版 LWJGL 优先
        if (lwjglOverride != null && lwjglOverride.exists()) result += lwjglOverride

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
                // 官方 lwjgl jar 全部剔除：Android 版已提供同名类
                .filterNot { it.absolutePath.contains("/org/lwjgl/") }
                .forEach { result += it }
        }

        // 3) 版本自带的 lwjgl3 替换 jar（若存在）
        val lwjgl = paths.lwjgl
        if (lwjgl.exists()) {
            lwjgl.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.forEach { result += it }
        }

        return result.toList()
    }
    /** 补齐 JRE 目录内缺失的系统依赖库（Android 版 JRE 不带 libz）。 */
    private fun ensureRuntimeLibs(libDir: File, onLog: (String) -> Unit) {
        val needed = listOf("libz.so")
        val systemDirs = listOf("/system/lib64", "/apex/com.android.runtime/lib64/bionic")
        for (name in needed) {
            val target = File(libDir, name)
            if (target.exists() && target.length() > 0) continue
            val src = systemDirs.map { File(it, name) }.firstOrNull { it.exists() }
            if (src == null) {
                onLog("[launcher] 警告: 系统缺少 $name，无法补齐")
                continue
            }
            try {
                src.copyTo(target, overwrite = true)
                onLog("[launcher] 已从 ${src.absolutePath} 补齐 $name")
            } catch (t: Throwable) {
                onLog("[launcher] 补齐 $name 失败: ${t.message}")
            }
        }
    }

}