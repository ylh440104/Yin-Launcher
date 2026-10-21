package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;

public final class MainActivity {
    private static final String TAG = "McBoxMainBridge";
    private static volatile Context context;
    private static volatile String pendingClipboard;
    private static volatile boolean clipboardReady;

    private MainActivity() {
    }

    public static void attach(Context ctx) {
        context = ctx.getApplicationContext();
    }

    public static Context appContext() {
        return context;
    }

    public static void openLink(String link) {
        if (link == null || link.isEmpty()) return;
        Context ctx = context;
        if (ctx == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "openLink failed", t);
        }
    }

    public static void openPath(String path) {
        if (path == null || path.isEmpty()) return;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            openLink(path);
            return;
        }
        Context ctx = context;
        if (ctx == null) return;
        try {
            if (!new File(path).exists()) return;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setDataAndType(Uri.fromFile(new File(path)), "*/*");
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "openPath failed", t);
        }
    }

    public static void querySystemClipboard() {
        Context ctx = context;
        if (ctx == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null) return;
            pendingClipboard = text.toString();
            clipboardReady = true;
            AWTInputBridge.nativeClipboardReceived(pendingClipboard, "text/plain");
        } catch (Throwable t) {
            Log.w(TAG, "querySystemClipboard failed", t);
        }
    }

    public static void putClipboardData(String data, String mimeTypeSub) {
        Context ctx = context;
        if (ctx == null || data == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("mcbox", data));
            pendingClipboard = data;
            clipboardReady = true;
        } catch (Throwable t) {
            Log.w(TAG, "putClipboardData failed", t);
        }
    }
}