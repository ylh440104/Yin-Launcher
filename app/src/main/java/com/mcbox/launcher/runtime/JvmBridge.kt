package com.mcbox.launcher.runtime

/**
 * 进程内 JVM 创建桥。
 *
 * 为什么不用 JLI_Launch：libjli 的 JLI_Launch 会 execve(<java.home>/bin/java)，
 * 而 Android 的 untrusted_app 域禁止 execve app_data_file（error=13），
 * 无论把 java 放哪里都会失败。
 *
 * 这里直接 dlopen(libjvm.so) + JNI_CreateJavaVM，全程不 exec，
 * 然后通过 JNI 调 mainClass.main(String[])。
 */
object JvmBridge {
    init {
        System.loadLibrary("jvm_bridge")
    }

    /** @return 0 成功；负数为错误码 */
    external fun nativeCreateVm(libjvmPath: String, jvmOptions: Array<String>): Int

    /** @return 0 成功；负数为错误码 */
    external fun nativeCallMain(mainClassName: String, mainArgs: Array<String>): Int
}
