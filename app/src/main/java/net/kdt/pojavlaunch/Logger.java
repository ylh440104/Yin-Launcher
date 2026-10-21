package net.kdt.pojavlaunch;

public class Logger {
    static {
        try {
            System.loadLibrary("pojavexec");
        } catch (Throwable ignored) {
        }
    }

    public static native void appendToLog(String text);

    public static native void begin(String logFilePath);

    public interface eventLogListener {
        void onEventLogged(String text);
    }

    public static native void setLogListener(eventLogListener logListener);
}