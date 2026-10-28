package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

public class MainActivity {
    private static ClipboardManager GLOBAL_CLIPBOARD;
    private static Context appContext;

    public static void attach(Context ctx) {
        appContext = ctx.getApplicationContext();
        GLOBAL_CLIPBOARD = (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    public static void openLink(String link) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(link));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    public static String querySystemClipboard() {
        try {
            ClipData data = GLOBAL_CLIPBOARD.getPrimaryClip();
            if (data == null) return "";
            return data.getItemAt(0).getText().toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void putClipboardData(String data) {
        try {
            GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newPlainText("mcbox", data));
        } catch (Throwable t) {
            Log.w("Clipboard", "putClipboardData failed", t);
        }
    }

    public static void toggleMouse() {
    }
}