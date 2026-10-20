package net.kdt.pojavlaunch;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;

public class ExitActivity extends Activity {
    public static final String EXTRA_EXIT_CODE = "exit_code";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = getIntent() != null ? getIntent().getIntExtra(EXTRA_EXIT_CODE, 0) : 0;
        Process.killProcess(Process.myPid());
        System.exit(code);
    }
}