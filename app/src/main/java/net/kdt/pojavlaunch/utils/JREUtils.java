package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.Surface;

import net.kdt.pojavlaunch.Logger;

import java.io.File;
import java.util.ArrayList;

public class JREUtils {
    private static final String TAG = "McBoxJRE";

    public static String LD_LIBRARY_PATH = "";
    public static String jvmLibraryPath = "";
    public static String DIRNAME_HOME_JRE = "lib";

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

    public static String findInLdLibPath(String libName) {
        if (Os.getenv("LD_LIBRARY_PATH") == null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            } catch (ErrnoException e) {
                e.printStackTrace();
            }
            return libName;
        }
        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> returnValue = new ArrayList<>();
        File[] list = path.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    returnValue.add(f);
                } else if (f.isDirectory()) {
                    returnValue.addAll(locateLibs(f));
                }
            }
        }
        return returnValue;
    }

    public static void relocateLibPath(String jreHome) {
        String libName = "lib64";
        StringBuilder ldLibraryPath = new StringBuilder();
        ldLibraryPath.append(jreHome)
                .append("/").append(DIRNAME_HOME_JRE)
                .append("/jli:").append(jreHome).append("/").append(DIRNAME_HOME_JRE)
                .append(":");
        ldLibraryPath.append("/system/").append(libName).append(":")
                .append("/vendor/").append(libName).append(":")
                .append("/vendor/").append(libName).append("/hw:")
                .append(NATIVE_LIB_DIR());
        LD_LIBRARY_PATH = ldLibraryPath.toString();
    }

    public static String NATIVE_LIB_DIR() {
        try {
            return com.mcbox.launcher.McBoxApp.getAppNativeLibDir();
        } catch (Throwable t) {
            return "/data/app";
        }
    }

    public static void initJavaRuntime(String jreHome) {
        dlopen(findInLdLibPath("libjli.so"));
        if (!dlopen("libjvm.so")) {
            Log.w("DynamicLoader", "Failed to load with no path, trying with full path");
            dlopen(jvmLibraryPath + "/libjvm.so");
        }
        dlopen(findInLdLibPath("libverify.so"));
        dlopen(findInLdLibPath("libjava.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        dlopen(findInLdLibPath("libfreetype.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
        for (File f : locateLibs(new File(jreHome, DIRNAME_HOME_JRE))) {
            dlopen(f.getAbsolutePath());
        }
        dlopen(NATIVE_LIB_DIR() + "/libopenal.so");
    }

    private static boolean logStarted2;

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

    public static boolean dlopenDirect(String libPath) {
        final String value = libPath;
        final boolean[] ok = new boolean[]{false};
        if (!ensureLibraries()) return false;
        try {
            ok[0] = dlopen(value);
        } catch (Throwable t) {
            Log.w(TAG, "dlopen failed " + value, t);
        }
        return ok[0];
    }

    public static boolean safeDlopen(String path) {
        final String value = path;
        final boolean[] ok = new boolean[]{false};
        if (!ensureLibraries()) return false;
        try {
            ok[0] = dlopen(value);
            if (!ok[0]) lastDlopenError = "dlopen-returned-false:" + value;
        } catch (Throwable t) {
            lastDlopenError = t.getClass().getSimpleName() + ":" + t.getMessage() + ":" + value;
            Log.w(TAG, "dlopen failed " + value, t);
        }
        return ok[0];
    }

    public static volatile String lastDlopenError = "";

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