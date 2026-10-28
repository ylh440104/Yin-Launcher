package com.kdt.mcgui;

import java.util.HashMap;
import java.util.Map;

public class ProgressLayout {
    public static final String UNPACK_RUNTIME = "unpack_runtime";
    public static final String DOWNLOAD_MINECRAFT = "download_minecraft";
    public static final String CHECKING_MODS = "checking_mods";
    public static final String EXTRACT_SINGLE_FILES = "extract_single_files";
    public static final String EXTRACT_COMPONENTS = "extract_components";

    private static final Map<String, Integer> progresses = new HashMap<>();

    public static void setProgress(String key, int progress) {
        progresses.put(key, progress);
    }

    public static void setProgress(String key, int progress, int resource, Object... message) {
        progresses.put(key, progress);
    }

    public static void setProgress(String key, int progress, String message) {
        progresses.put(key, progress);
    }

    public static void clearProgress(String key) {
        progresses.remove(key);
    }

    public static void submitProgress(String key, int progress, int resource, Object... message) {
        progresses.put(key, progress);
    }
}