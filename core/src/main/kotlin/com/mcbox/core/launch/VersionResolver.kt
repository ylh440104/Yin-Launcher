package com.mcbox.core.launch

import com.mcbox.core.GamePaths
import com.mcbox.core.PlatformInfo
import com.mcbox.core.RuleEvaluator
import com.mcbox.core.model.Arguments
import com.mcbox.core.model.Library
import com.mcbox.core.model.VersionJson
import com.mcbox.core.util.JsonCompact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

class VersionResolver(private val paths: GamePaths) {

    fun loadVersion(id: String): VersionJson {
        val file = paths.versionJson(id)
        require(file.exists()) { "version json not found: ${file.absolutePath}" }
        return JsonCompact.decodeFromString(VersionJson.serializer(), file.readText())
    }

    fun loadMerged(id: String): VersionJson {
        val merged = mutableMapOf<String, VersionJson>()
        var current: VersionJson? = loadVersion(id)
        var depth = 0
        while (current != null && depth < 16) {
            merged[current.id.ifEmpty { id }] = current
            val parent = current.inheritsFrom
            current = if (parent != null && paths.versionJson(parent).exists()) loadVersion(parent) else null
            depth++
        }
        val chain = merged.values.toList().reversed()
        return merge(chain, id)
    }

    private fun merge(chain: List<VersionJson>, targetId: String): VersionJson {
        var result = VersionJson()
        val libraries = LinkedHashMap<String, Library>()
        val jvmArgs = mutableListOf<kotlinx.serialization.json.JsonElement>()
        val gameArgs = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var legacyArgs: String? = null
        var logging: VersionJson.Logging? = null
        var assetIndex: VersionJson.AssetIndex? = null
        var assets = ""
        var javaVersion: VersionJson.JavaVersion? = null
        var downloads: VersionJson.VersionDownloads? = null
        var mainClass = ""
        var type = "release"

        for (v in chain) {
            if (v.mainClass.isNotEmpty()) mainClass = v.mainClass
            if (v.type.isNotEmpty()) type = v.type
            if (v.assets.isNotEmpty()) assets = v.assets
            if (v.assetIndex != null) assetIndex = v.assetIndex
            if (v.downloads != null) downloads = v.downloads
            if (v.javaVersion != null) javaVersion = v.javaVersion
            if (v.logging != null) logging = v.logging
            if (!v.minecraftArguments.isNullOrEmpty()) legacyArgs = v.minecraftArguments
            v.arguments?.jvm?.let { jvmArgs.addAll(it) }
            v.arguments?.game?.let { gameArgs.addAll(it) }
            for (lib in v.libraries) {
                libraries[libKey(lib)] = lib
            }
        }
        result = result.copy(
            id = targetId,
            type = type,
            mainClass = mainClass,
            assets = assets,
            assetIndex = assetIndex,
            downloads = downloads,
            javaVersion = javaVersion,
            logging = logging,
            libraries = libraries.values.toList(),
            arguments = Arguments(game = gameArgs, jvm = jvmArgs),
            minecraftArguments = legacyArgs
        )
        return result
    }

    private fun libKey(lib: Library): String {
        val parts = lib.name.split(':')
        return if (parts.size >= 3) parts[0] + ":" + parts[1] + ":" + parts.getOrElse(3) { "" }
        else lib.name
    }

    fun applicableLibraries(version: VersionJson, platform: PlatformInfo): List<Library> =
        version.libraries.filter { RuleEvaluator.isAllowed(it.rules, platform) }

    fun buildClasspath(version: VersionJson, platform: PlatformInfo, includeLwjgl: Boolean = false): List<File> {
        val out = mutableListOf<File>()
        for (lib in applicableLibraries(version, platform)) {
            if (!includeLwjgl && isReplacedLwjgl(lib)) continue
            val artifact = lib.downloads?.artifact
            if (artifact == null && lib.natives.isNotEmpty()) continue
            val rel = lib.artifactPath()
            if (rel.isEmpty()) continue
            val file = paths.libraryPath(rel)
            if (file.exists()) out.add(file)
        }
        return out
    }

    fun nativeLibraries(version: VersionJson, platform: PlatformInfo): List<Pair<Library, String>> {
        val out = mutableListOf<Pair<Library, String>>()
        for (lib in applicableLibraries(version, platform)) {
            if (lib.natives.isEmpty()) continue
            val classifierTemplate = lib.natives.entries.firstOrNull {
                it.key == "linux" || it.key == "android"
            }?.value ?: continue
            val classifier = classifierTemplate.replace("\${arch}", archSuffix(platform.arch))
            out.add(lib to classifier)
        }
        return out
    }

    fun archSuffix(arch: String): String = when {
        arch == "aarch64" || arch == "arm64" -> "64"
        arch.startsWith("arm") -> "32"
        arch == "x86_64" || arch == "amd64" -> "64"
        else -> "32"
    }

    fun isReplacedLwjgl(lib: Library): Boolean {
        val group = lib.group
        return group == "org.lwjgl" || group.startsWith("org.lwjgl.")
    }

    fun requiredJavaMajor(version: VersionJson): Int {
        version.javaVersion?.majorVersion?.let { if (it > 0) return it }
        return when {
            version.id.startsWith("1.") -> {
                val minor = version.id.removePrefix("1.").takeWhile { it.isDigit() }.toIntOrNull() ?: 8
                if (minor >= 18) 17 else if (minor >= 17) 16 else 8
            }
            else -> 17
        }
    }

    fun assetIndexId(version: VersionJson): String =
        version.assetIndex?.id ?: version.assets.ifEmpty { "legacy" }
}

fun VersionJson.jvmArgList(): JsonArray =
    JsonArray(arguments?.jvm ?: emptyList())

fun VersionJson.gameArgList(): JsonArray =
    JsonArray(arguments?.game ?: emptyList())

fun VersionJson.hasModernArgs(): Boolean =
    arguments != null && ((arguments?.game?.isNotEmpty() == true) || (arguments?.jvm?.isNotEmpty() == true))

fun VersionJson.rawObject(): JsonObject = JsonObject(emptyMap())