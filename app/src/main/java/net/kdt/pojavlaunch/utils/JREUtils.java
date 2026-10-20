package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.view.Surface;

import net.kdt.pojavlaunch.Logger;

import java.io.File;

public class JREUtils {
    public static String LD_LIBRARY_PATH = "";
    public static String jvmLibraryPath = "";

    private static boolean logStarted = false;

    public static void beginLog(File logFile) {
        if (logStarted) return;
        try {
            logFile.getParentFile().mkdirs();
            if (logFile.exists()) logFile.delete();
            logFile.createNewFile();
            Logger.begin(logFile.getAbsolutePath());
            logStarted = true;
        } catch (Throwable ignored) {
        }
    }

    public static void appendLog(String line) {
        try {
            Logger.appendToLog(line + "\n");
        } catch (Throwable ignored) {
        }
    }

    public static native int chdir(String path);

    public static native boolean dlopen(String libPath);

    public static native void setLdLibraryPath(String ldLibraryPath);

    public static native void setupBridgeWindow(Object surface);

    public static native void releaseBridgeWindow();

    public static native void initializeHooks();

    public static native void setupExitMethod(Context context);

    public static native void setupBridgeSurfaceAWT(Surface surface);

    public static native int executeBinary(String[] args, String[] envp, String cwd);

    public static native int[] renderAWTScreenFrame();
}