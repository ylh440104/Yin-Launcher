package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;

/**
 * Static bridge required by libpojavexec_awt.so.
 *
 * The native side resolves these by exact JNI signature (jni/awt_bridge.c):
 *   querySystemClipboard -> "()V"
 *   putClipboardData     -> "(Ljava/lang/String;Ljava/lang/String;)V"
 * Any signature drift makes ART abort the process on the first rendered frame.
 */
public class MainActivity extends Activity {
    private static final String TAG = "PojavBridge";

    private static ClipboardManager GLOBAL_CLIPBOARD;
    private static Context sContext;

    public static void attach(Context ctx) {
        sContext = ctx.getApplicationContext();
        GLOBAL_CLIPBOARD = (ClipboardManager) sContext.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Keep
    public static void openLink(String link) {
        final Context ctx = sContext;
        if (ctx == null) return;
        Tools.runOnUiThread(() -> {
            try {
                if (link.startsWith("file:")) {
                    int truncLength = link.startsWith("file://") ? 7 : 5;
                    String path = link.substring(truncLength);
                    Tools.openPath(ctx, new File(path), false);
                } else {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(link), "*/*");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                }
            } catch (Throwable th) {
                Log.w(TAG, "openLink failed", th);
            }
        });
    }

    @Keep
    public static void openPath(String path) {
        final Context ctx = sContext;
        if (ctx == null) return;
        Tools.runOnUiThread(() -> {
            try {
                Tools.openPath(ctx, new File(path), false);
            } catch (Throwable th) {
                Log.w(TAG, "openPath failed", th);
            }
        });
    }

    /** Must be ()V: the native side receives the value via nativeClipboardReceived. */
    @Keep
    public static void querySystemClipboard() {
        Tools.runOnUiThread(() -> {
            try {
                if (GLOBAL_CLIPBOARD == null) {
                    AWTInputBridge.nativeClipboardReceived(null, null);
                    return;
                }
                ClipData clipData = GLOBAL_CLIPBOARD.getPrimaryClip();
                if (clipData == null) {
                    AWTInputBridge.nativeClipboardReceived(null, null);
                    return;
                }
                CharSequence text = clipData.getItemAt(0).getText();
                if (text == null) {
                    AWTInputBridge.nativeClipboardReceived(null, null);
                    return;
                }
                AWTInputBridge.nativeClipboardReceived(text.toString(), "plain");
            } catch (Throwable th) {
                Log.w(TAG, "querySystemClipboard failed", th);
                try {
                    AWTInputBridge.nativeClipboardReceived(null, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** Must be (Ljava/lang/String;Ljava/lang/String;)V */
    @Keep
    public static void putClipboardData(String data, String mimeType) {
        Tools.runOnUiThread(() -> {
            try {
                if (GLOBAL_CLIPBOARD == null) return;
                ClipData clipData = null;
                if ("text/plain".equals(mimeType)) {
                    clipData = ClipData.newPlainText("AWT Paste", data);
                } else if ("text/html".equals(mimeType)) {
                    clipData = ClipData.newHtmlText("AWT Paste", data, data);
                }
                if (clipData != null) GLOBAL_CLIPBOARD.setPrimaryClip(clipData);
            } catch (Throwable th) {
                Log.w(TAG, "putClipboardData failed", th);
            }
        });
    }

    public static void toggleMouse(Context ctx) {
    }
}
