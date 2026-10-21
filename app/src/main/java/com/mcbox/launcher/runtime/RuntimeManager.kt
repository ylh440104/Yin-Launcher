package com.mcbox.launcher.runtime

import android.content.Context
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class RuntimeInfo(
    val name: String,
    val javaMajor: Int,
    val arch: String,
    val home: File
) {
    val binJava: File get() = File(home, "bin/java")
    private val serverLib: File get() = File(home, "lib/server/libjvm.so")
    private val clientLib: File get() = File(home, "lib/client/libjvm.so")
    private val flatLib: File get() = File(home, "lib/libjvm.so")
    val jvmDir: File
        get() = when {
            serverLib.exists() -> File(home, "lib/server")
            clientLib.exists() -> File(home, "lib/client")
            flatLib.exists() -> File(home, "lib")
            else -> File(home, "lib/server")
        }
    val jvmLibrary: File
        get() = File(jvmDir, "libjvm.so")
}

class RuntimeManager(private val context: Context, private val runtimesDir: File) {

    fun listInstalled(): List<RuntimeInfo> {
        if (!runtimesDir.exists()) return emptyList()
        return runtimesDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val release = File(dir, "release")
            if (!release.exists()) return@mapNotNull null
            val props = parseRelease(release)
            val version = props["JAVA_VERSION"]?.trim('"') ?: return@mapNotNull null
            val major = parseMajor(version)
            RuntimeInfo(
                name = dir.name,
                javaMajor = major,
                arch = props["OS_ARCH"]?.trim('"') ?: "aarch64",
                home = dir
            )
        }
    }

    fun findForMajor(major: Int): RuntimeInfo? {
        val installed = listInstalled()
        return installed.firstOrNull { it.javaMajor == major }
            ?: installed.filter { it.javaMajor >= major }.minByOrNull { it.javaMajor }
            ?: installed.maxByOrNull { it.javaMajor }
    }

    private fun parseMajor(version: String): Int {
        if (version.startsWith("1.")) {
            return version.removePrefix("1.").takeWhile { it.isDigit() }.toIntOrNull() ?: 8
        }
        return version.takeWhile { it.isDigit() }.toIntOrNull() ?: 17
    }

    private fun parseRelease(file: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        file.forEachLine { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return map
    }

    fun installFromAssets(
        name: String,
        universalAsset: String,
        binAsset: String,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): RuntimeInfo {
        val dest = File(runtimesDir, name)
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        onProgress("unpack universal", 0)
        context.assets.open(universalAsset).use { extractArchive(it, dest) }
        onProgress("unpack platform", 50)
        context.assets.open(binAsset).use { extractArchive(it, dest) }
        onProgress("finalize", 90)
        fixFreetype(dest)
        renameUnpack200(dest)
        onProgress("done", 100)
        val release = File(dest, "release")
        val props = parseRelease(release)
        return RuntimeInfo(
            name = name,
            javaMajor = parseMajor(props["JAVA_VERSION"]?.trim('"') ?: "17"),
            arch = props["OS_ARCH"]?.trim('"') ?: "aarch64",
            home = dest
        )
    }

    private fun fixFreetype(dest: File) {
        val candidates = listOf(
            File(dest, "lib/libfreetype.so.6"),
            File(dest, "lib/aarch64/libfreetype.so.6"),
            File(dest, "lib/arm/libfreetype.so.6")
        )
        for (src in candidates) {
            if (src.exists()) {
                val target = File(src.parentFile, "libfreetype.so")
                if (!target.exists() || target.length() != src.length()) {
                    src.copyTo(target, overwrite = true)
                }
            }
        }
    }

    private fun renameUnpack200(dest: File) {
        val bin = File(dest, "bin")
        if (!bin.exists()) return
        bin.listFiles()?.forEach { f ->
            if (f.isFile && f.canExecute()) f.setExecutable(true, false)
        }
        File(dest, "lib").listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so")) f.setReadable(true, false)
        }
    }

    private fun extractArchive(input: InputStream, dest: File) {
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        buffered.mark(8)
        val magic = ByteArray(6)
        val read = buffered.read(magic)
        buffered.reset()
        val stream: InputStream = if (read >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte()) {
            XZInputStream(buffered)
        } else if (read >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
        extractTar(stream, dest)
    }

    private fun extractTar(input: InputStream, dest: File) {
        val buffer = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        while (true) {
            if (!readFully(input, buffer, 512)) break
            if (buffer.all { it.toInt() == 0 }) break
            val name = readString(buffer, 0, 100)
            val prefix = readString(buffer, 345, 155)
            val sizeOctal = readString(buffer, 124, 12).trim().trimEnd('\u0000')
            val size = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLongOrNull(8) ?: 0L
            val typeFlag = buffer[156].toInt().toChar()
            val linkName = readString(buffer, 157, 100)
            val mode = readString(buffer, 100, 8).trim().toLongOrNull(8) ?: 420L

            var fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            if (pendingLongName != null) {
                fullName = pendingLongName
                pendingLongName = null
            }
            var link = linkName
            if (pendingLongLink != null) {
                link = pendingLongLink
                pendingLongLink = null
            }

            val blocks = ((size + 511) / 512) * 512

            when (typeFlag) {
                'L' -> {
                    val nameBytes = ByteArray(size.toInt())
                    if (!readFully(input, nameBytes, size.toInt())) break
                    pendingLongName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    skipFully(input, blocks - size)
                }
                'K' -> {
                    val linkBytes = ByteArray(size.toInt())
                    if (!readFully(input, linkBytes, size.toInt())) break
                    pendingLongLink = String(linkBytes, Charsets.UTF_8).trimEnd('\u0000')
                    skipFully(input, blocks - size)
                }
                'x', 'g' -> {
                    skipFully(input, blocks)
                }
                '5' -> {
                    val dir = File(dest, fullName)
                    dir.mkdirs()
                    skipFully(input, blocks)
                }
                '2' -> {
                    val target = File(dest, fullName)
                    target.parentFile?.mkdirs()
                    try {
                        if (target.exists()) target.delete()
                        java.nio.file.Files.createSymbolicLink(
                            target.toPath(),
                            File(dest, link).toPath()
                        )
                    } catch (t: Throwable) {
                        if (target.parentFile?.exists() == true) {
                            target.writeText("")
                        }
                    }
                    skipFully(input, blocks)
                }
                '0', '\u0000', '7' -> {
                    val target = File(dest, fullName)
                    if (fullName.endsWith("/")) {
                        target.mkdirs()
                        skipFully(input, blocks)
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            if (!copyFully(input, out, size)) return
                        }
                        skipFully(input, blocks - size)
                        try {
                            target.setExecutable((mode and 0b001001001L) != 0L, false)
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                else -> {
                    skipFully(input, blocks)
                }
            }
        }
    }

    private fun readString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buffer[end].toInt() != 0) end++
        return String(buffer, offset, end - offset, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun copyFully(input: InputStream, out: FileOutputStream, length: Long): Boolean {
        var remaining = length
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) return false
            out.write(buf, 0, n)
            remaining -= n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= n
            }
        }
    }
}