package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

public class ExitActivity extends Activity {
    private static final String TAG = "McBoxExit";
    public static final String EXTRA_EXIT_CODE = "exit_code";
    public static final String EXTRA_SHOW_MESSAGE = "show_message";

    public static void showExitMessage(Context context, int code, boolean showMessage) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, ExitActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra(EXTRA_EXIT_CODE, code);
            intent.putExtra(EXTRA_SHOW_MESSAGE, showMessage);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "showExitMessage failed, killing instead", t);
            Process.killProcess(Process.myPid());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = 0;
        if (getIntent() != null) {
            code = getIntent().getIntExtra(EXTRA_EXIT_CODE, 0);
        }
        Process.killProcess(Process.myPid());
        System.exit(code);
    }
}