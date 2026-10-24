package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import java.util.Locale;

public class Tools {
    public static String APP_NAME = "YinLauncher";
    public static String NATIVE_LIB_DIR;
    public static String DIR_DATA;
    public static java.io.File DIR_CACHE;
    public static String LOCAL_RENDERER = null;
    public static String DIR_GAME_HOME;
    public static String DIR_GAME_NEW;
    public static String DIR_ACCOUNT_NEW;
    public static String MULTIRT_HOME;
    public static String DIRNAME_HOME_JRE = "lib";
    public static DisplayMetricsBridge currentDisplayMetrics = new DisplayMetricsBridge();

    public static class DisplayMetricsBridge {
        public int widthPixels = 1280;
        public int heightPixels = 720;
        public void update(Context ctx) {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                Display d = wm.getDefaultDisplay();
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                d.getRealMetrics(dm);
                widthPixels = dm.widthPixels;
                heightPixels = dm.heightPixels;
            } catch (Throwable ignored) {
            }
        }
    }

    public static void init(Context ctx, String gameHome) {
        DIR_GAME_HOME = gameHome;
        DIR_GAME_NEW = gameHome + "/.minecraft";
        DIR_CACHE = ctx.getCacheDir();
        DIR_DATA = ctx.getFilesDir().getParent();
        MULTIRT_HOME = DIR_DATA + "/runtimes";
        DIR_ACCOUNT_NEW = DIR_DATA + "/accounts";
        NATIVE_LIB_DIR = ctx.getApplicationInfo().nativeLibraryDir;
        currentDisplayMetrics.update(ctx);
    }

    public static boolean isAndroid8OrHigher() {
        return Build.VERSION.SDK_INT >= 26;
    }

    public static void fullyExit() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public static int getDisplayFriendlyRes(int displaySideRes, float scaling) {
        displaySideRes *= scaling;
        if (displaySideRes % 2 != 0) displaySideRes--;
        return displaySideRes;
    }

    public static boolean deviceHasHangingLinker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return false;
        return Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("huawei");
    }
}