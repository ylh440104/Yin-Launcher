package com.mcbox.launcher.data

import android.content.Context
import android.os.Environment
import java.io.File

object GameDirs {

    fun internalDefault(context: Context): File =
        File(context.getExternalFilesDir(null), "mcbox").also { it.mkdirs() }

    fun publicDefault(): File = File(Environment.getExternalStorageDirectory(), "MCBox").also { it.mkdirs() }

    fun candidates(context: Context): List<Pair<String, File>> = listOf(
        "公共目录 MCBox" to publicDefault(),
        "应用专属目录" to internalDefault(context),
        "Android/data" to File(Environment.getExternalStorageDirectory(), "Android/data/com.mcbox.launcher/files/mcbox")
    )

    fun isUsable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val probe = File(dir, ".mcbox_probe")
            probe.writeText("ok")
            val ok = probe.exists()
            probe.delete()
            ok
        } catch (t: Throwable) {
            false
        }
    }

    fun isGameRoot(dir: File): Boolean =
        File(dir, "versions").exists() || File(dir, "libraries").exists()
}