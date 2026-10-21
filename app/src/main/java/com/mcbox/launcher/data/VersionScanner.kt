package com.mcbox.launcher.data

import com.mcbox.core.GamePaths
import com.mcbox.core.launch.VersionResolver
import com.mcbox.core.util.JsonCompact
import com.mcbox.core.util.bool
import com.mcbox.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

data class DetectedVersion(
    val id: String,
    val gameVersion: String,
    val loader: String,
    val loaderVersion: String,
    val javaMajor: Int,
    val hasJar: Boolean,
    val hasJson: Boolean,
    val missingLibraries: Int,
    val sizeBytes: Long
) {
    val isComplete: Boolean get() = hasJson && (hasJar || loader != "vanilla") && missingLibraries == 0
}

object VersionScanner {

    fun scan(paths: GamePaths, platformArch: String = "aarch64"): List<DetectedVersion> {
        val versionsDir = paths.versions
        if (!versionsDir.exists()) return emptyList()
        val resolver = VersionResolver(paths)
        val out = mutableListOf<DetectedVersion>()

        versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { dir ->
                val id = dir.name
                val json = File(dir, "$id.json")
                val jar = File(dir, "$id.jar")
                if (!json.exists()) {
                    out.add(
                        DetectedVersion(
                            id = id,
                            gameVersion = id,
                            loader = "unknown",
                            loaderVersion = "",
                            javaMajor = 17,
                            hasJar = jar.exists(),
                            hasJson = false,
                            missingLibraries = -1,
                            sizeBytes = dirSize(dir)
                        )
                    )
                    return@forEach
                }

                val root = runCatching {
                    JsonCompact.parseToJsonElement(json.readText()).jsonObject
                }.getOrNull()

                val declaredId = root?.str("id") ?: id
                val inheritsFrom = root?.str("inheritsFrom")
                val mainClass = root?.str("mainClass").orEmpty()
                val loader = detectLoader(declaredId, inheritsFrom, mainClass)
                val gameVersion = when (loader) {
                    "fabric", "quilt" -> inheritsFrom ?: declaredId
                    "forge", "neoforge" -> inheritsFrom ?: declaredId.substringBefore('-')
                    else -> declaredId
                }
                val loaderVersion = detectLoaderVersion(loader, declaredId, gameVersion)

                val javaMajor = runCatching {
                    resolver.requiredJavaMajor(resolver.loadMerged(id))
                }.getOrElse { 17 }

                val missing = runCatching {
                    val merged = resolver.loadMerged(id)
                    val cp = resolver.buildClasspath(merged, com.mcbox.core.PlatformInfo.android())
                    if (cp.isEmpty()) 0 else {
                        val mergedLibs = resolver.applicableLibraries(
                            merged,
                            com.mcbox.core.PlatformInfo.android()
                        ).count { lib -> !resolver.isReplacedLwjgl(lib) }
                        (mergedLibs - cp.size).coerceAtLeast(0)
                    }
                }.getOrElse { -1 }

                out.add(
                    DetectedVersion(
                        id = id,
                        gameVersion = gameVersion,
                        loader = loader,
                        loaderVersion = loaderVersion,
                        javaMajor = javaMajor,
                        hasJar = jar.exists(),
                        hasJson = true,
                        missingLibraries = missing,
                        sizeBytes = dirSize(dir)
                    )
                )
            }
        return out
    }

    fun detectLoader(declaredId: String, inheritsFrom: String?, mainClass: String): String {
        val lower = declaredId.lowercase()
        val klass = mainClass.lowercase()
        return when {
            lower.startsWith("fabric-loader") || klass.contains("fabricmc") -> "fabric"
            lower.startsWith("quilt-loader") || klass.contains("quiltmc") -> "quilt"
            lower.startsWith("neoforge") || klass.contains("neoforged") -> "neoforge"
            lower.startsWith("forge") || klass.contains("forgeclient") || klass.contains("fmlclient") -> "forge"
            lower.startsWith("optifine") || klass.contains("optifine") -> "optifine"
            inheritsFrom != null && inheritsFrom.isNotEmpty() -> "vanilla"
            else -> "vanilla"
        }
    }

    fun detectLoaderVersion(loader: String, declaredId: String, gameVersion: String): String {
        return when (loader) {
            "fabric" -> declaredId.removePrefix("fabric-loader-").substringBefore("-$gameVersion").ifEmpty { "" }
            "quilt" -> declaredId.removePrefix("quilt-loader-").substringBefore("-$gameVersion").ifEmpty { "" }
            "forge", "neoforge" -> declaredId.removePrefix("$gameVersion-").ifEmpty { declaredId }
            else -> ""
        }
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().maxDepth(6).forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    fun syncToInstances(
        store: InstanceStore,
        detected: List<DetectedVersion>
    ): Int {
        val existing = store.all().toMutableList()
        var added = 0
        for (version in detected) {
            if (!version.hasJson) continue
            val already = existing.any { it.versionId == version.id }
            if (already) continue
            existing.add(
                Instance(
                    id = "inst-" + version.id.replace(Regex("[^A-Za-z0-9_.-]"), "_"),
                    name = version.id,
                    versionId = version.id,
                    loader = version.loader,
                    loaderVersion = version.loaderVersion,
                    javaMajor = version.javaMajor
                )
            )
            added++
        }
        if (added > 0) store.save(existing)
        return added
    }
}