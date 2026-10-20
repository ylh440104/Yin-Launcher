package com.mcbox.launcher.game

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object InputBridge {

    private var clipboard: ClipboardManager? = null
    private var grabListener: ((Boolean) -> Unit)? = null

    fun attach(context: Context) {
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    fun setGrabListener(listener: ((Boolean) -> Unit)?) {
        grabListener = listener
    }

    fun handleClipboard(type: Int, copy: String?): String? {
        val cm = clipboard ?: return null
        return when (type) {
            2000 -> {
                if (copy != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("mcbox", copy))
                }
                null
            }
            2001 -> {
                if (!cm.hasPrimaryClip()) return ""
                val clip = cm.primaryClip ?: return ""
                if (clip.itemCount == 0) return ""
                clip.getItemAt(0).text?.toString() ?: ""
            }
            2002 -> null
            else -> null
        }
    }

    fun onGrabChanged(grabbing: Boolean) {
        grabListener?.invoke(grabbing)
    }
}