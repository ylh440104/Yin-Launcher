package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import net.kdt.pojavlaunch.Logger;

import java.io.File;

public class JREUtils {
    private static final String TAG = "McBoxJRE";

    public static String LD_LIBRARY_PATH = "";
    public static String jvmLibraryPath = "";

    private static boolean logStarted = false;
    private static volatile Boolean librariesReady = null;

    public static synchronized boolean ensureLibraries() {
        if (librariesReady != null) return librariesReady;
        try {
            System.loadLibrary("pojavexec");
        } catch (Throwable t) {
            Log.w(TAG, "pojavexec load failed", t);
            librariesReady = false;
            return false;
        }
        try {
            System.loadLibrary("pojavexec_awt");
        } catch (Throwable t) {
            Log.w(TAG, "pojavexec_awt load failed", t);
        }
        try {
            System.loadLibrary("exithook");
        } catch (Throwable t) {
            Log.w(TAG, "exithook load failed", t);
        }
        try {
            System.loadLibrary("lwjgl");
        } catch (Throwable t) {
            Log.w(TAG, "lwjgl load failed", t);
        }
        librariesReady = true;
        return true;
    }

    private static boolean call(String name, Runnable body) {
        if (!ensureLibraries()) return false;
        try {
            body.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, name + " failed", t);
            return false;
        }
    }

    public static void beginLog(File logFile) {
        if (logStarted) return;
        try {
            File parent = logFile.getParentFile();
            if (parent != null) parent.mkdirs();
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

    public static boolean safeSetLdLibraryPath(String path) {
        final String value = path;
        return call("setLdLibraryPath", new Runnable() {
            @Override
            public void run() {
                setLdLibraryPath(value);
            }
        });
    }

    public static boolean safeChdir(String path) {
        final String value = path;
        return call("chdir", new Runnable() {
            @Override
            public void run() {
                chdir(value);
            }
        });
    }

    public static volatile String lastDlopenError = "";

    public static boolean safeDlopen(String path) {
        final String value = path;
        final boolean[] ok = new boolean[] { false };
        if (!ensureLibraries()) { lastDlopenError = "libs-not-ready"; return false; }
        try {
            ok[0] = dlopen(value);
            if (!ok[0]) lastDlopenError = "dlopen-returned-false:" + value;
        } catch (Throwable t) {
            lastDlopenError = t.getClass().getSimpleName() + ":" + t.getMessage() + ":" + value;
            Log.w(TAG, "dlopen failed " + value, t);
        }
        return ok[0];
    }

    public static boolean safeSetupBridgeWindow(Object surface) {
        final Object value = surface;
        return call("setupBridgeWindow", new Runnable() {
            @Override
            public void run() {
                setupBridgeWindow(value);
            }
        });
    }

    public static boolean safeReleaseBridgeWindow() {
        return call("releaseBridgeWindow", new Runnable() {
            @Override
            public void run() {
                releaseBridgeWindow();
            }
        });
    }

    public static boolean safeInitializeHooks() {
        if (!ensureLibraries()) return false;
        try {
            initializeHooks();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "initializeHooks unavailable", t);
            return false;
        }
    }

    public static boolean safeSetupExitMethod(Context context) {
        final Context value = context;
        return call("setupExitMethod", new Runnable() {
            @Override
            public void run() {
                setupExitMethod(value);
            }
        });
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