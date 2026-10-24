package com.mcbox.launcher.game

import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.app.Activity

object LoadingOverlay {
    private var overlay: LinearLayout? = null
    private var spinner: ProgressBar? = null
    private var label: TextView? = null

    fun attach(activity: Activity, root: FrameLayout) {
        if (overlay != null) return
        val ctx = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE6000000.toInt())
            isClickable = true
            isFocusable = true
        }
        val bar = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateDrawable!!.setTint(0xFF7CB342.toInt())
        }
        val text = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 14f
            setPadding(0, 24, 0, 0)
            text = "正在加载"
        }
        val tip = TextView(ctx).apply {
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding(0, 8, 0, 0)
            text = "首次启动需要解压运行时和校验资源，可能需要几分钟"
        }
        box.addView(bar)
        box.addView(text)
        box.addView(tip)

        root.addView(
            box,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlay = box
        spinner = bar
        label = text
    }

    fun setText(text: String) {
        label?.let { l ->
            (l.context as? Activity)?.runOnUiThread {
                l.text = text
            }
        }
    }

    fun hide() {
        overlay?.let { o ->
            val fade = AlphaAnimation(1f, 0f).apply { duration = 300 }
            fade.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    o.visibility = View.GONE
                }
            })
            o.startAnimation(fade)
        }
        overlay = null
        spinner = null
        label = null
    }
}