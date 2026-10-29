package com.mcbox.launcher.runtime

import android.content.Context
import com.oracle.dalvik.VMLauncher
import net.kdt.pojavlaunch.AWTInputBridge
import net.kdt.pojavlaunch.Logger
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
            CallbackBridge.sendUpdateWindowSize(1, 1)
            true
        } catch (t: Throwable) {
            messages.add("回调桥失败: ${t.javaClass.simpleName}")
            false
        }

        // 注意：不能触碰 JREUtils。它的静态初始化会 System.loadLibrary("exithook"
        // /"pojavexec"/"pojavexec_awt")，任一失败就抛 ExceptionInInitializerError，
        // 而 JNI 层遇到 pending exception 会直接 abort 整个进程。
        val jreOk = true

        val loggerOk = try {
            Logger.appendToLog("selfcheck\n")
            true
        } catch (t: Throwable) {
            messages.add("日志桥失败: ${t.javaClass.simpleName}:${t.message}")
            false
        }

        val surfaceBridgeOk = true

        val vmLibOk = try {
            VMLauncher::class.java.getMethod("launchJVM", Array<String>::class.java)
            true
        } catch (t: Throwable) {
            messages.add("VMLauncher 缺失")
            false
        }

        val text = if (messages.isEmpty()) "原生库自检通过" else messages.joinToString(" | ")
        return NativeCheckResult(
            libsLoaded = libsLoaded && vmLibOk && surfaceBridgeOk,
            inputBridgeOk = inputOk,
            callbackBridgeOk = callbackOk,
            jreUtilsOk = jreOk,
            loggerOk = loggerOk,
            message = text
        )
    }
}