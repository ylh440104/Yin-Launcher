package com.mcbox.launcher.game

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mcbox.launcher.R
import org.lwjgl.glfw.CallbackBridge

class GameActivity : AppCompatActivity(), GameSurfaceView.Listener {

    companion object {
        const val EXTRA_INSTANCE = "instance"
        const val EXTRA_JAVA_MAJOR = "java_major"
        const val EXTRA_MEMORY = "memory"
        const val EXTRA_RENDERER = "renderer"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_SERVER = "server"
    }

    private lateinit var surfaceView: GameSurfaceView
    private lateinit var statusView: TextView
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupNativeEnv()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        hideSystemBars()

        InputBridge.attach(this)

        val root = FrameLayout(this)
        surfaceView = GameSurfaceView(this)
        surfaceView.listener = this
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(24, 24, 24, 24)
            text = "正在启动"
            visibility = View.GONE
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        LoadingOverlay.attach(this, root)
        setContentView(root)
    }

    private fun setupNativeEnv() {
        val metrics = resources.displayMetrics
        val root = com.mcbox.launcher.data.SettingsStore(this).paths().root
        val env = mapOf(
            "POJAV_NATIVEDIR" to applicationInfo.nativeLibraryDir,
            "POJAV_RENDERER" to "opengles3",
            "LIBGL_ES" to "3",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NOERROR" to "1",
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NORMALIZE" to "1",
            "AWTSTUB_WIDTH" to metrics.widthPixels.toString(),
            "AWTSTUB_HEIGHT" to metrics.heightPixels.toString(),
            "MESA_GLSL_CACHE_DIR" to java.io.File(root, "cache").apply { mkdirs() }.absolutePath
        )
        for ((key, value) in env) {
            try {
                android.system.Os.setenv(key, value, true)
            } catch (t: Throwable) {
                android.util.Log.w("McBox", "setenv $key failed: ${t.message}")
            }
        }
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onSurfaceReady(holder: SurfaceHolder) {
        if (launched) return
        launched = true
        val frame = holder.surfaceFrame
        val width = frame.width().takeIf { it > 1 } ?: surfaceView.width.coerceAtLeast(1)
        val height = frame.height().takeIf { it > 1 } ?: surfaceView.height.coerceAtLeast(1)
        statusView.visibility = View.GONE
        try {
            CallbackBridge.sendUpdateWindowSize(width, height)
        } catch (t: Throwable) {
            android.util.Log.w("McBox", "sendUpdateWindowSize failed: ${t.message}")
        }
        setupBridgeWindow(holder.surface)
        GameBridge.start(this, intent, holder, width, height)
    }

    private fun setupBridgeWindow(surface: android.view.Surface?) {
        if (surface == null) return
        try {
            val cls = Class.forName("net.kdt.pojavlaunch.utils.JREUtils")
            val method = cls.getMethod("safeSetupBridgeWindow", Any::class.java)
            val ok = method.invoke(null, surface)
            android.util.Log.i("McBox", "setupBridgeWindow -> $ok")
        } catch (t: Throwable) {
            android.util.Log.w("McBox", "setupBridgeWindow failed: ${t.message}")
        }
    }

    override fun onSurfaceChanged(holder: SurfaceHolder, width: Int, height: Int) {
        try {
            CallbackBridge.sendUpdateWindowSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        } catch (t: Throwable) {
            android.util.Log.w("McBox", "sendUpdateWindowSize failed: ${t.message}")
        }
    }

    override fun onSurfaceDestroyed() {
    }

    fun appendStatus(text: String) {
        runOnUiThread {
            LoadingOverlay.setText(text)
            statusView.append("\n$text")
        }
    }
    fun onGameExit(code: Int) {
        runOnUiThread {
            val consoleText = try {
                val console = java.io.File(cacheDir, "jvm-console.txt")
                if (console.exists()) "\n=== JVM CONSOLE ====\n" + console.readText().takeLast(1500) else ""
            } catch (t: Throwable) { "" }
            val logTail = try {
                val logsDir = java.io.File(getExternalFilesDir(null), "mcbox/logs")
                logsDir.listFiles()?.maxByOrNull { it.name }?.let { f ->
                    f.readText().lines().takeLast(12).joinToString("\n")
                }
            } catch (t: Throwable) {
                t.message
            }
            LoadingOverlay.showError("游戏已退出，代码 $code", (logTail ?: "") + consoleText)
        }
    }
    fun onGameReady() {
        runOnUiThread {
            LoadingOverlay.hide()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun surfaceWidth() = CallbackBridge.windowWidth

    fun surfaceHeight() = CallbackBridge.windowHeight
}