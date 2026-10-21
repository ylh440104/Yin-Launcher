package com.mcbox.launcher.game

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mcbox.launcher.R
import com.mcbox.launcher.runtime.JavaLauncher
import net.kdt.pojavlaunch.utils.JREUtils
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
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
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
        val width = surfaceView.width.coerceAtLeast(1)
        val height = surfaceView.height.coerceAtLeast(1)
        if (launched) return
        launched = true
        JREUtils.setupBridgeWindow(holder.surface)
        statusView.visibility = View.GONE
        GameBridge.start(this, intent, holder, width, height)
    }

    override fun onSurfaceDestroyed() {
    }

    fun appendStatus(text: String) {
        runOnUiThread {
            statusView.visibility = View.VISIBLE
            statusView.append("\n$text")
        }
    }

    fun onGameExit(code: Int) {
        runOnUiThread {
            statusView.visibility = View.VISIBLE
            statusView.text = "游戏已退出，代码 $code"
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        JREUtils.releaseBridgeWindow()
    }

    fun surfaceWidth() = CallbackBridge.windowWidth

    fun surfaceHeight() = CallbackBridge.windowHeight
}