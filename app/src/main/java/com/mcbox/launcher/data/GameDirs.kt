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

    fun browseRoot(): File = Environment.getExternalStorageDirectory()

    fun listDirs(parent: File): List<File> {
        val children = parent.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
    }

    fun relativeLabel(root: File, dir: File): String {
        val rootPath = root.absolutePath
        val dirPath = dir.absolutePath
        return if (dirPath.startsWith(rootPath)) {
            dirPath.removePrefix(rootPath).ifEmpty { "/" }
        } else dirPath
    }

    fun normalize(input: String, context: Context): File {
        val raw = input.trim()
        if (raw.isEmpty()) return internalDefault(context)
        val direct = File(raw)
        if (direct.isAbsolute) return direct
        return File(Environment.getExternalStorageDirectory(), raw)
    }

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