package com.mcbox.launcher.runtime

import android.content.Context
import com.oracle.dalvik.VMLauncher
import net.kdt.pojavlaunch.AWTInputBridge
import net.kdt.pojavlaunch.CriticalNativeTest
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge

data class NativeCheckResult(
    val libsLoaded: Boolean,
    val inputBridgeOk: Boolean,
    val callbackBridgeOk: Boolean,
    val jreUtilsOk: Boolean,
    val loggerOk: Boolean,
    val message: String
) {
    val allOk: Boolean get() = libsLoaded && inputBridgeOk && callbackBridgeOk && jreUtilsOk && loggerOk
}

object NativeSelfCheck {

    fun run(context: Context, nativeLibDir: String): NativeCheckResult {
        val messages = mutableListOf<String>()

        val libsLoaded = try {
            System.loadLibrary("pojavexec")
            System.loadLibrary("pojavexec_awt")
            System.loadLibrary("lwjgl")
            System.loadLibrary("lwjgl_opengl")
            System.loadLibrary("openal")
            true
        } catch (t: Throwable) {
            messages.add("加载原生库失败: ${t.message}")
            false
        }

        val inputOk = try {
            AWTInputBridge.nativeSendData(AWTInputBridge.EVENT_TYPE_CURSOR_POS, 0, 0, 0, 0)
            true
        } catch (t: Throwable) {
            messages.add("输入桥失败: ${t.javaClass.simpleName}")
            false
        }

        val callbackOk = try {
            CallbackBridge.sendUpdateWindowSize(1, 1)
            CallbackBridge.nativeSetInputReady(false)
            true
        } catch (t: Throwable) {
            messages.add("回调桥失败: ${t.javaClass.simpleName}")
            false
        }

        val jreOk = try {
            JREUtils.setLdLibraryPath(nativeLibDir)
            true
        } catch (t: Throwable) {
            messages.add("JRE 桥失败: ${t.javaClass.simpleName}")
            false
        }

        val loggerOk = try {
            CriticalNativeTest.invokeTest()
            Logger.appendToLog("")
            true
        } catch (t: Throwable) {
            messages.add("日志桥失败: ${t.javaClass.simpleName}")
            false
        }

        val vmLibOk = try {
            VMLauncher::class.java.getMethod("launchJVM", Array<String>::class.java)
            true
        } catch (t: Throwable) {
            messages.add("VMLauncher 缺失")
            false
        }

        val text = if (messages.isEmpty()) "原生库自检通过" else messages.joinToString(" | ")
        return NativeCheckResult(
            libsLoaded = libsLoaded && vmLibOk,
            inputBridgeOk = inputOk,
            callbackBridgeOk = callbackOk,
            jreUtilsOk = jreOk,
            loggerOk = loggerOk,
            message = text
        )
    }
}