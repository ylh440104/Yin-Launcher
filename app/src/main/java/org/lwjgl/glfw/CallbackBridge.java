package org.lwjgl.glfw;

import com.mcbox.launcher.game.InputBridge;

import java.nio.ByteBuffer;

public class CallbackBridge {
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static final int EVENT_TYPE_CHAR = 1000;
    public static final int EVENT_TYPE_CHAR_MODS = 1001;
    public static final int EVENT_TYPE_CURSOR_ENTER = 1002;
    public static final int EVENT_TYPE_CURSOR_POS = 1003;
    public static final int EVENT_TYPE_FRAMEBUFFER_SIZE = 1004;
    public static final int EVENT_TYPE_KEY = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON = 1006;
    public static final int EVENT_TYPE_SCROLL = 1007;
    public static final int EVENT_TYPE_WINDOW_SIZE = 1008;

    public static final int ANDROID_TYPE_GRAB_STATE = 0;

    public static final boolean INPUT_DEBUG_ENABLED = Boolean.parseBoolean(
            System.getProperty("glfwstub.debugInput", "false"));

    public static volatile int windowWidth = 1280;
    public static volatile int windowHeight = 720;
    public static volatile int physicalWidth = 1280;
    public static volatile int physicalHeight = 720;
    public static float mouseX = 0f;
    public static float mouseY = 0f;
    public static volatile boolean holdingAlt, holdingCapslock, holdingCtrl, holdingNumlock, holdingShift;
    public static volatile boolean sGamepadDirectEnabled = false;

    private static volatile boolean grabbing = false;

    public static void sendData(int type, String data) {
        nativeSendData(false, type, data);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, 0, getCurrentMods(), true);
        sendKeyPress(keyCode, 0, getCurrentMods(), false);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeycode(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        if (keycode != 0) {
            nativeSendKey(keycode, scancode, isDown ? 1 : 0, modifiers);
        }
        if (isDown && keychar != '\u0000') {
            nativeSendCharMods(keychar, modifiers);
            nativeSendChar(keychar);
        }
    }

    public static void sendMouseButton(int button, boolean status) {
        nativeSendMouseButton(button, status ? 1 : 0, getCurrentMods());
    }

    public static void sendMouseKeycode(int button) {
        sendMouseButton(button, true);
        sendMouseButton(button, false);
    }

    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(x, y);
    }

    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y) {
        sendCursorPos(x, y);
        sendMouseButton(button, isDown);
    }

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        putMouseEventWithCoords(button, false, x, y);
    }

    public static void sendUpdateWindowSize(int width, int height) {
        windowWidth = width;
        windowHeight = height;
        physicalWidth = width;
        physicalHeight = height;
        nativeSendScreenSize(width, height);
    }

    public static int getCurrentMods() {
        int mods = 0;
        if (holdingAlt) mods |= 4;
        if (holdingCapslock) mods |= 16;
        if (holdingCtrl) mods |= 2;
        if (holdingNumlock) mods |= 32;
        if (holdingShift) mods |= 1;
        return mods;
    }

    public static boolean isGrabbing() {
        return grabbing;
    }

    public static void setGrabbing(boolean value) {
        grabbing = value;
    }

    public static void setModifiers(int keyCode, boolean isDown) {
        switch (keyCode) {
            case 340:
            case 344:
                holdingShift = isDown;
                break;
            case 341:
            case 345:
                holdingCtrl = isDown;
                break;
            case 342:
            case 346:
                holdingAlt = isDown;
                break;
            case 280:
                holdingCapslock = isDown;
                break;
            case 282:
                holdingNumlock = isDown;
                break;
            default:
                break;
        }
    }

    public static String accessAndroidClipboard(int type, String copy) {
        return InputBridge.INSTANCE.handleClipboard(type, copy);
    }

    public static void onGrabStateChanged(boolean grabbingNow) {
        grabbing = grabbingNow;
        InputBridge.INSTANCE.onGrabChanged(grabbingNow);
    }

    public static void onDirectInputEnable() {
        sGamepadDirectEnabled = true;
    }

    public static native void nativeSendData(boolean isAndroid, int type, String data);

    public static native boolean nativeSetInputReady(boolean ready);

    public static native String nativeClipboard(int action, byte[] copy);

    public static native void nativeSetGrabbing(boolean grab);

    public static native void nativeSetWindowAttrib(int attrib, int value);

    public static native ByteBuffer nativeCreateGamepadButtonBuffer();

    public static native ByteBuffer nativeCreateGamepadAxisBuffer();

    private static native boolean nativeEnableGamepadDirectInput();

    @dalvik.annotation.optimization.CriticalNative
    public static native boolean nativeSendChar(char codepoint);

    @dalvik.annotation.optimization.CriticalNative
    public static native boolean nativeSendCharMods(char codepoint, int mods);

    @dalvik.annotation.optimization.CriticalNative
    public static native void nativeSendKey(int key, int scancode, int action, int mods);

    @dalvik.annotation.optimization.CriticalNative
    public static native void nativeSendCursorPos(float x, float y);

    @dalvik.annotation.optimization.CriticalNative
    public static native void nativeSendMouseButton(int button, int action, int mods);

    @dalvik.annotation.optimization.CriticalNative
    public static native void nativeSendScroll(double xoffset, double yoffset);

    @dalvik.annotation.optimization.CriticalNative
    public static native void nativeSendScreenSize(int width, int height);

    @dalvik.annotation.optimization.CriticalNative
    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    static {
        try {
            System.loadLibrary("pojavexec");
        } catch (Throwable ignored) {
        }
    }
}