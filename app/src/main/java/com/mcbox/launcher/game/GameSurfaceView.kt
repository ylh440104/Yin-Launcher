package com.mcbox.launcher.game

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.lwjgl.glfw.CallbackBridge

class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    interface Listener {
        fun onSurfaceReady(holder: SurfaceHolder)
        fun onSurfaceDestroyed()
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pointerIdDown = -1
    private var lastX = 0f
    private var lastY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private var moved = false
    private var clickScheduled = false

    private var cursorX = 0f
    private var cursorY = 0f

    private var lastTapTime = 0L

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        listener?.onSurfaceReady(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        cursorX = width / 2f
        cursorY = height / 2f
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        listener?.onSurfaceDestroyed()
    }

    fun applyWindowSize(width: Int, height: Int) {
        CallbackBridge.sendUpdateWindowSize(width, height)
        cursorX = width / 2f
        cursorY = height / 2f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerIdDown = event.getPointerId(0)
                initialX = event.x
                initialY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                requestFocus()
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.getPointerId(0) != pointerIdDown) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (kotlin.math.abs(event.x - initialX) > touchSlop || kotlin.math.abs(event.y - initialY) > touchSlop) {
                    moved = true
                }
                if (CallbackBridge.isGrabbing()) {
                    if (moved) {
                        cursorX = (cursorX + dx).coerceIn(0f, width.toFloat())
                        cursorY = (cursorY + dy).coerceIn(0f, height.toFloat())
                        CallbackBridge.sendCursorPos(cursorX, cursorY)
                    }
                } else {
                    if (moved) {
                        cursorX = event.x.coerceIn(0f, width.toFloat())
                        cursorY = event.y.coerceIn(0f, height.toFloat())
                        CallbackBridge.sendCursorPos(cursorX, cursorY)
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 250) {
                        CallbackBridge.sendMouseButton(0, true)
                        CallbackBridge.sendMouseButton(0, false)
                        CallbackBridge.sendMouseButton(0, true)
                        CallbackBridge.sendMouseButton(0, false)
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                        cursorX = event.x
                        cursorY = event.y
                        CallbackBridge.sendCursorPos(cursorX, cursorY)
                        CallbackBridge.sendMouseButton(0, true)
                        CallbackBridge.sendMouseButton(0, false)
                    }
                }
                pointerIdDown = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerIdDown = -1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    CallbackBridge.sendMouseButton(1, true)
                    CallbackBridge.sendMouseButton(1, false)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_CLASS_POINTER != 0) {
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                CallbackBridge.sendScroll(0.0, scrollY.toDouble())
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyMapping.hasMapping(keyCode)) {
            KeyMapping.sendKeyEvent(event)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyMapping.hasMapping(keyCode)) {
            KeyMapping.sendKeyEvent(event)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private val touchSlop: Float
        get() = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    fun sendChar(codepoint: Int) {
        CallbackBridge.sendKeycode(0, codepoint.toChar(), 0, 0, true)
    }
}