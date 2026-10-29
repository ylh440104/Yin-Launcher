package com.oracle.dalvik;

/**
 * 由 libpojavexec.so 实现的 JVM 启动入口。
 *
 * 实现（jre_launcher.c）：dlopen(libjli) -> dlsym("JLI_Launch") -> 调用。
 * 这是绕开 Android SELinux exec 限制、在进程内启动 JVM 的唯一可行路径。
 */
public final class VMLauncher {
    private VMLauncher() {
    }

    public static native int launchJVM(String[] args);
}
