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
        // 注意：不要调用 setLdLibraryPath。Pojav 的实现在 libdl.so 里 dlsym
        // android_update_LD_LIBRARY_PATH 后无条件调用，Android 13 的 app
        // namespace 拿不到该私有符号，会抛 UnsatisfiedLinkError。
        // libjli.so 已随 APK 打进 nativeLibraryDir，与 libjavaexec.so 同目录，
        // linker 按 SONAME 同目录解析即可，不需要改 LD_LIBRARY_PATH。
        // 走 libpojavexec.so 里的 VMLauncher.launchJVM：
        //   jre_launcher.c: dlopen(libjli.so) -> dlsym("JLI_Launch") -> 调用
        // 这是 Pojav 原版真正使用的入口，也是绕开 SELinux exec 限制的正解。
        // 先把 libjli.so 载入进程（libjavaexec.so 依赖它，同目录已打包）。
        try {
            System.loadLibrary("pojavexec")
            onLog("[launcher] libpojavexec.so loaded")
        } catch (t: Throwable) {
            onLog("[launcher] loadLibrary pojavexec failed: ${t.message}")
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir

        // 关键：JLI_Launch 之前必须把 JRE 的核心 so 都载入进程（libjli 的 JLI_Launch
        // 会在已加载的库中查找 libjvm）。Pojav 的 initJavaRuntime 做的就是这件事。
        // 顺序至关重要：libjava/libzip/libnio 等都 DT_NEEDED libjvm.so，
        // 而 libjvm.so 位于 lib/server/，不在 linker 的默认搜索路径里。
        // 必须先把 libjvm.so 载入进程，后面那些才能解析成功。
        val needLibs = ArrayList<String>()
        needLibs += File(nativeDir, "libjli.so").absolutePath
        val serverJvm = File(serverDir, "libjvm.so")
        if (serverJvm.exists()) needLibs += serverJvm.absolutePath
        for (n in listOf("libjava.so", "libverify.so", "libzip.so", "libnet.so",
                         "libnio.so", "libjimage.so", "libjsig.so")) {
            val f = File(libDir, n)
            if (f.exists()) needLibs += f.absolutePath
        }

        val dlopen = jreUtils.getMethod("dlopen", String::class.java)
        for (p in needLibs) {
            try {
                val ok = dlopen.invoke(null, p) as Boolean
                onLog("[launcher] dlopen ${File(p).name} = $ok")
            } catch (t: Throwable) {
                onLog("[launcher] dlopen ${File(p).name} threw: ${t.message}")
            }
        }

        val vmArgs = ArrayList<String>(argv.size + 2)
        vmArgs += "java"                 // argv[0] 按 C 标准是程序名
        vmArgs += "-Djava.home=${execHome.absolutePath}"
        vmArgs += args.drop(1)

        // JLI_Launch 依赖这些环境变量定位 JRE 与 native 库
        val fullLdPath = libPath + ":" + libDir.absolutePath + ":" + serverDir.absolutePath +
                ":" + nativeDir + ":/system/lib64:/vendor/lib64"
        try {
            Os.setenv("LD_LIBRARY_PATH", fullLdPath, true)
            Os.setenv("JAVA_HOME", execHome.absolutePath, true)
            Os.setenv("HOME", paths.root.absolutePath, true)
            Os.setenv("TMPDIR", context.cacheDir.absolutePath, true)
            onLog("[launcher] env set: JAVA_HOME=${execHome.absolutePath}")
        } catch (t: Throwable) {
            onLog("[launcher] setenv failed: ${t.message}")
        }

        // JLI_Launch 的输出走 native stdout/stderr，必须重定向到 logcat 的 jrelog
        // 标签，否则 JVM 为什么退出完全看不到（Pojav 的既有机制）。
        try {
            jreUtils.getMethod("redirectAndPrintJRELog").invoke(null)
            onLog("[launcher] JRE log redirect started")
        } catch (t: Throwable) {
            onLog("[launcher] redirectAndPrintJRELog failed: ${t.message}")
        }

        // 双保险：把 native 的 fd 1/2 直接 dup2 到文件，JVM 的一切输出都会落盘。
        val consoleFile = File(paths.root, "jvm-console.txt")
        try {
            consoleFile.parentFile?.mkdirs()
            val fd = ParcelFileDescriptor.open(
                consoleFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_TRUNCATE
            )
            Os.dup2(fd.fileDescriptor, 1)
            Os.dup2(fd.fileDescriptor, 2)
            fd.close()
            onLog("[launcher] stdout/stderr -> ${consoleFile.absolutePath}")
        } catch (t: Throwable) {
            onLog("[launcher] dup2 failed: ${t.message}")
        }

        // 不再用 JLI_Launch：它内部必定 execve(<java.home>/bin/java)，
        // 而 untrusted_app 域禁止 exec app_data_dir（error=13）。
        // 改为 dlopen(libjvm.so) + JNI_CreateJavaVM，全程无 exec。
        val libjvm = File(serverDir, "libjvm.so")
        require(libjvm.exists()) { "libjvm.so 不存在: ${libjvm.absolutePath}" }
        onLog("[launcher] libjvm = ${libjvm.absolutePath}")

        // 工作目录必须在游戏目录（JVM 与 MC 都按 cwd 解析相对路径）
        try {
            val rc = jreUtils.getMethod("chdir", String::class.java)
                .invoke(null, paths.root.absolutePath) as Int
            onLog("[launcher] chdir ${paths.root.absolutePath} = $rc")
        } catch (t: Throwable) {
            onLog("[launcher] chdir failed: ${t.message}")
        }

        // -Djava.home 让 JVM 找到 modules/legal；-Djava.class.path 即玩家 classpath。
        val jvmOptions = ArrayList<String>()
        jvmOptions += "-Djava.home=${execHome.absolutePath}"
        jvmOptions += "-Djava.class.path=" + classpath.joinToString(":") { it.absolutePath }
        jvmOptions += "-Dsun.boot.library.path=${libDir.absolutePath}"
        jvmOptions += "-Djava.library.path=$libPath"
        jvmOptions += "-Xmx${memoryMb}M"
        jvmOptions += "-Xss1m"
        jvmOptions += "-Dfile.encoding=UTF-8"
        jvmOptions += "-Duser.home=${paths.root.absolutePath}"
        jvmOptions += "-Duser.dir=${paths.root.absolutePath}"
        jvmOptions += "-Dstdout.encoding=UTF-8"
        jvmOptions += "-Dstderr.encoding=UTF-8"
        // 版本 json 里的 jvm 参数原样带上（Fabric 的 -DFabricMcEmu 等）
        for (a in args.drop(1)) {
            if (a.startsWith("-D") || a.startsWith("-X") || a.startsWith("-XX:")) {
                if (a.startsWith("-Djava.class.path")) continue
                jvmOptions += a
            }
        }

        onLog("[launcher] creating JVM, options=${jvmOptions.size}")
        var vmRc = -999
        try {
            vmRc = JvmBridge.nativeCreateVm(libjvm.absolutePath, jvmOptions.toTypedArray())
        } catch (t: Throwable) {
            var c: Throwable? = t
            while (c?.cause != null) c = c.cause
            onLog("[launcher] nativeCreateVm threw: ${t.javaClass.simpleName}: ${t.message}")
            onLog("[launcher] root cause: ${c?.javaClass?.name}: ${c?.message}")
            c?.stackTrace?.take(8)?.forEach { onLog("[launcher]   at $it") }
        }
        onLog("[launcher] JNI_CreateJavaVM rc=$vmRc")
        if (vmRc != 0) return Result(vmRc, "JNI_CreateJavaVM failed: $vmRc")

        // 游戏参数：直接用前面构建好的 args（已含 username/uuid/assets 等真实值），
        // 只剔除 JVM 选项，保留 main 之后的游戏参数。
        val mainIdx = args.indexOf(version.mainClass)
        val gameArgs = if (mainIdx >= 0) ArrayList(args.drop(mainIdx + 1)) else ArrayList()

        onLog("[launcher] calling ${version.mainClass}.main, gameArgs=${gameArgs.size}")
        val mainRc = try {
            JvmBridge.nativeCallMain(
                version.mainClass,
                gameArgs.toTypedArray(),
                classpath.map { it.absolutePath }.toTypedArray()
            )
        } catch (t: Throwable) {
            var c: Throwable? = t
            while (c?.cause != null) c = c.cause
            onLog("[launcher] nativeCallMain threw: ${t.javaClass.simpleName}: ${t.message}")
            onLog("[launcher] root cause: ${c?.javaClass?.name}: ${c?.message}")
            c?.stackTrace?.take(10)?.forEach { onLog("[launcher]   at $it") }
            -1
        }
        onLog("[launcher] main returned $mainRc")
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