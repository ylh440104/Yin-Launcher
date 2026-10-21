package net.kdt.pojavlaunch;

public class Logger {
    static {
        System.loadLibrary("pojavexec");
    }

    public static native void appendToLog(String text);

    public static native void begin(String logFilePath);

    public interface eventLogListener {
        void onEventLogged(String text);
    }

    public static native void setLogListener(eventLogListener logListener);
}