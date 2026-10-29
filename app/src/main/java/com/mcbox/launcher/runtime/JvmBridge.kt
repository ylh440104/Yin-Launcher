package com.mcbox.launcher.runtime

/**
 * 进程内 JVM 启动桥。
 *
 * 为什么不用 JLI_Launch：libjli 的 JLI_Launch 会 execve(<java.home>/bin/java)，
 * 而 Android 的 untrusted_app 域禁止 execve app_data_file（error=13）。
 *
 * 这里直接 dlopen(libjvm.so) + JNI_CreateJavaVM，并用 URLClassLoader 加载
 * classpath（纯 JNI 创建 VM 不认 -cp），全程不 exec。
 *
 * 整个流程跑在 native 侧新建的专用线程上：CreateJavaVM 和 main 必须同线程，
 * 放在 ART 工作线程上会导致 libjvm 内部空指针崩溃。
 */
object JvmBridge {
    init {
        System.loadLibrary("jvm_bridge")
    }

    /**
     * 一次性完成建 VM 并调用 main。
     *
     * @return intArrayOf(vmRc, mainRc)，0 表示成功
     */
    external fun nativeLaunch(
        libjvmPath: String,
        jvmOptions: Array<String>,
        mainClassName: String,
        mainArgs: Array<String>,
        classpath: Array<String>,
        nativeLibDir: String
    ): IntArray?
}