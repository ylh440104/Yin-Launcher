package net.kdt.pojavlaunch;

public class CriticalNativeTest {
    private static int sResult = -1;

    public static void invokeTest() {
        sResult = testCriticalNative(1, 2);
    }

    public static int result() {
        return sResult;
    }

    public static native int testCriticalNative(int a, int b);
}