package net.kdt.pojavlaunch

import dalvik.annotation.optimization.CriticalNative

object CriticalNativeTest {

    @JvmStatic
    fun invokeTest() {
        testCriticalNative(1, 2)
    }

    @JvmStatic
    @CriticalNative
    external fun testCriticalNative(a: Int, b: Int)
}