package net.kdt.pojavlaunch.prefs;

import android.content.Context;
import android.content.SharedPreferences;

public class LauncherPreferences {
    public static final String PREF_KEY_CURRENT_PROFILE = "currentProfile";
    public static SharedPreferences DEFAULT_PREF;
    public static String PREF_RENDERER = "opengles2";
    public static boolean PREF_IGNORE_NOTCH = false;
    public static int PREF_NOTCH_SIZE = 0;
    public static float PREF_BUTTONSIZE = 100f;
    public static float PREF_MOUSESCALE = 1f;
    public static int PREF_LONGPRESS_TRIGGER = 300;
    public static String PREF_DEFAULTCTRL_PATH = "";
    public static String PREF_CUSTOM_JAVA_ARGS = "";
    public static boolean PREF_FORCE_ENGLISH = false;
    public static int PREF_RAM_ALLOCATION = 2048;
    public static boolean PREF_DUMP_SHADERS = false;
    public static boolean PREF_VSYNC_IN_ZINK = false;
    public static boolean PREF_ZINK_PREFER_SYSTEM_DRIVER = false;
    public static boolean PREF_FORCE_VSYNC = true;
    public static boolean PREF_BIG_CORE_AFFINITY = false;
    public static boolean PREF_ARC_CAPES = false;
    public static float PREF_SCALE_FACTOR = 1.0f;

    public static void loadPreferences(Context ctx) {
        DEFAULT_PREF = ctx.getSharedPreferences("pref", Context.MODE_PRIVATE);
        PREF_RAM_ALLOCATION = DEFAULT_PREF.getInt("allocation", 2048);
        PREF_SCALE_FACTOR = DEFAULT_PREF.getFloat("scaleFactor", 1.0f);
        PREF_CUSTOM_JAVA_ARGS = DEFAULT_PREF.getString("javaArgs", "");
        PREF_BIG_CORE_AFFINITY = DEFAULT_PREF.getBoolean("bigCore", false);
    }
}