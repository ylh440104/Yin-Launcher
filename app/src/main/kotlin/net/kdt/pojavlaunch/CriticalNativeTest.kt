package net.kdt.pojavlaunch

import dalvik.annotation.optimization.CriticalNative

class CriticalNativeTest {

    companion object {
        @JvmStatic
        @CriticalNative
        external fun testCriticalNative(arg0: Int, arg1: Int)

        @JvmStatic
        fun invokeTest() {
            testCriticalNative(0, 0)
        }
    }
}