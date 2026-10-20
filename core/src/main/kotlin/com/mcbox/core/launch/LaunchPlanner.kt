package com.mcbox.core.launch

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

class LaunchCommand(
    val executable: String,
    val jvmArgs: List<String>,
    val mainClass: String,
    val gameArgs: List<String>
) {
    fun toCommandLine(): List<String> {
        val out = mutableListOf<String>()
        out.add(executable)
        out.addAll(jvmArgs)
        out.add(mainClass)
        out.addAll(gameArgs)
        return out
    }

    fun render(quote: Boolean = false): String {
        val parts = toCommandLine()
        return if (!quote) parts.joinToString(" ")
        else parts.joinToString(" ") { if (it.contains(' ') || it.isEmpty()) "\"$it\"" else it }
    }

    fun classpathEntries(): List<String> {
        val result = mutableListOf<String>()
        for (arg in jvmArgs) {
            if (arg.startsWith("-Djava.class.path=")) {
                result.addAll(arg.removePrefix("-Djava.class.path=").split(File.pathSeparator))
            }
        }
        val idx = jvmArgs.indexOfFirst { it == "-cp" || it == "-classpath" }
        if (idx >= 0 && idx + 1 < jvmArgs.size) {
            result.addAll(jvmArgs[idx + 1].split(File.pathSeparator))
        }
        return result.filter { it.isNotEmpty() }
    }

    fun hasMainClass(): Boolean = mainClass.isNotBlank()

    fun memoryMb(): Int? {
        val arg = jvmArgs.firstOrNull { it.startsWith("-Xmx") } ?: return null
        return arg.removePrefix("-Xmx").removeSuffix("M").removeSuffix("m").toIntOrNull()
    }
}

object LaunchPlanner {

    fun plan(config: LaunchConfig, builder: ArgumentBuilder = ArgumentBuilder()): LaunchCommand {
        val jvm = mutableListOf<String>()
        jvm.add("-Xms${config.minMemoryMb.coerceAtLeast(256)}M")
        jvm.add("-Xmx${config.maxMemoryMb}M")
        jvm.add("-Dorg.lwjgl.opengl.libname=${config.customJvmLibraryPath ?: "libgl4es_114.so"}")
        jvm.add("-Djava.library.path=${config.nativesDir.absolutePath}")
        jvm.add("-Duser.home=${config.gameDir.absolutePath}")
        jvm.add("-Dminecraft.launcher.brand=mcbox")
        jvm.addAll(config.extraJvmArgs)
        jvm.add("-cp")
        jvm.add(config.classpath.joinToString(File.pathSeparator) { it.absolutePath })
        jvm.addAll(builder.buildJvmArgs(config).filterNot {
            it.startsWith("-Djava.library.path") || it == "-cp" || it == "-classpath"
        }.filterNot { it.contains(config.classpath.joinToString(File.pathSeparator)) })

        val main = config.versionJson.mainClass.ifEmpty { "net.minecraft.client.main.Main" }
        val game = builder.buildGameArgs(config) + config.extraGameArgs
        return LaunchCommand(config.javaExecutable, jvm, main, game)
    }

    fun validate(command: LaunchCommand, config: LaunchConfig): List<String> {
        val problems = mutableListOf<String>()
        if (!command.hasMainClass()) problems.add("main class is empty")
        if (command.classpathEntries().isEmpty()) problems.add("classpath is empty")
        if (command.jvmArgs.none { it.startsWith("-Djava.library.path") }) problems.add("missing java.library.path")
        if (command.jvmArgs.none { it.startsWith("-Xmx") }) problems.add("missing -Xmx")
        if (command.gameArgs.none { it == config.account.displayName }) problems.add("player name not in game args")
        if (command.gameArgs.any { it.contains("\${") }) problems.add("unresolved placeholder in game args")
        if (command.jvmArgs.any { it.contains("\${") }) problems.add("unresolved placeholder in jvm args")
        if (!config.gameDir.exists()) problems.add("game directory missing")
        return problems
    }
}