package com.mcbox.core.install

import com.mcbox.core.GamePaths
import com.mcbox.core.PlatformInfo
import com.mcbox.core.RuleEvaluator
import com.mcbox.core.launch.VersionResolver
import com.mcbox.core.model.VersionJson
import com.mcbox.core.model.VersionManifest
import com.mcbox.core.net.Endpoints
import com.mcbox.core.net.Hashes
import com.mcbox.core.net.Http
import com.mcbox.core.util.JsonCompact
import com.mcbox.core.util.JsonPretty
import com.mcbox.core.util.bool
import com.mcbox.core.util.obj
import com.mcbox.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class DownloadSource {
    Official,
    Mirror,
    Auto;

    fun manifestUrl(): String = when (this) {
        Official -> Endpoints.MOJANG_MANIFEST
        Mirror -> Endpoints.BMCL_MANIFEST
        Auto -> Endpoints.BMCL_MANIFEST
    }

    fun rewrite(url: String): String = when (this) {
        Official -> url
        Mirror, Auto -> when {
            url.startsWith(Endpoints.MOJANG_RESOURCES) -> url.replace(Endpoints.MOJANG_RESOURCES, Endpoints.BMCL_RESOURCES)
            url.startsWith("https://libraries.minecraft.net") -> url.replace("https://libraries.minecraft.net", Endpoints.BMCL_LIBRARIES)
            url.startsWith("https://piston-meta.mojang.com") -> url.replace("https://piston-meta.mojang.com", "https://bmclapi2.bangbang93.com")
            url.startsWith("https://launchermeta.mojang.com") -> url.replace("https://launchermeta.mojang.com", "https://bmclapi2.bangbang93.com")
            url.startsWith("https://launcher.mojang.com") -> url.replace("https://launcher.mojang.com", "https://bmclapi2.bangbang93.com")
            else -> url
        }
    }
}

data class InstallProgress(
    val stage: String,
    val current: Long = 0,
    val total: Long = 0,
    val detail: String = ""
) {
    val fraction: Float
        get() = when {
            total > 0 -> (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

interface ProgressListener {
    fun onProgress(progress: InstallProgress)
    fun onLog(line: String) {}
}

class VersionInstaller(
    private val paths: GamePaths,
    private val http: Http = Http(),
    private val source: DownloadSource = DownloadSource.Official,
    private val platform: PlatformInfo = PlatformInfo.android()
) {

    fun fetchManifest(): VersionManifest {
        val text = http.getText(source.manifestUrl())
        return JsonCompact.decodeFromString(VersionManifest.serializer(), text)
    }

    fun fetchVersionJson(summary: VersionManifest.VersionSummary): VersionJson {
        val url = source.rewrite(summary.url)
        val text = http.getText(url)
        return JsonCompact.decodeFromString(VersionJson.serializer(), text)
    }

    fun install(
        versionId: String,
        versionUrl: String,
        listener: ProgressListener,
        includeAssets: Boolean = true
    ): File {
        paths.ensure()
        listener.onLog("install $versionId")
        val raw = http.getText(source.rewrite(versionUrl))
        val version = JsonCompact.decodeFromString(VersionJson.serializer(), raw)
        val dir = paths.versionDir(versionId)
        dir.mkdirs()
        val jsonFile = paths.versionJson(versionId)
        jsonFile.writeText(JsonPretty.encodeToString(VersionJson.serializer(), version))
        listener.onLog("saved version json")
        downloadLibraries(version, listener)
        downloadClient(version, versionId, listener)
        if (includeAssets) downloadAssets(version, listener)
        return jsonFile
    }

    fun downloadLibraries(version: VersionJson, listener: ProgressListener) {
        val libs = version.libraries.filter { RuleEvaluator.isAllowed(it.rules, platform) }
        val tasks = mutableListOf<Triple<String, File, String?>>()
        for (lib in libs) {
            val artifact = lib.downloads?.artifact
            if (lib.natives.isNotEmpty()) {
                for ((os, template) in lib.natives) {
                    if (os != "linux" && os != "android") continue
                    val classifier = template.replace("\${arch}", if (platform.arch.contains("64")) "64" else "32")
                    val artifactCls = lib.downloads?.classifiers?.get(classifier) ?: continue
                    if (artifactCls.path.isEmpty() || artifactCls.url.isEmpty()) continue
                    val target = paths.libraryPath(artifactCls.path)
                    tasks.add(Triple(artifactCls.url, target, artifactCls.sha1))
                }
                continue
            }
            if (artifact == null) continue
            if (artifact.url.isEmpty()) continue
            val rel = artifact.path.ifEmpty { lib.mavenPath() }
            val target = paths.libraryPath(rel)
            tasks.add(Triple(artifact.url, target, artifact.sha1))
        }
        runDownloadBatch("libraries", tasks, listener)
    }

    fun downloadClient(version: VersionJson, versionId: String, listener: ProgressListener) {
        val client = version.downloads?.client ?: return
        if (client.url.isEmpty()) return
        val target = paths.versionJar(versionId)
        listener.onProgress(InstallProgress("client", 0, 1, "game jar"))
        http.download(source.rewrite(client.url), target, client.sha1.ifEmpty { null })
        listener.onProgress(InstallProgress("client", 1, 1, "game jar"))
    }

    fun downloadAssets(version: VersionJson, listener: ProgressListener) {
        val index = version.assetIndex ?: return
        if (index.url.isEmpty()) return
        val indexFile = paths.indexFile(index.id)
        if (!indexFile.exists() || (index.sha1.isNotEmpty() && Hashes.sha1(indexFile) != index.sha1)) {
            http.download(source.rewrite(index.url), indexFile, index.sha1.ifEmpty { null })
        }
        val root = JsonCompact.parseToJsonElement(indexFile.readText()).jsonObject
        val objects = root.obj("objects") ?: return
        val tasks = mutableListOf<Triple<String, File, String?>>()
        for ((name, value) in objects) {
            val obj = value as? JsonObject ?: continue
            val hash = obj.str("hash") ?: continue
            val target = paths.objectPath(hash)
            if (target.exists()) continue
            val url = Endpoints.MOJANG_RESOURCES + "/" + hash.substring(0, 2) + "/" + hash
            tasks.add(Triple(url, target, hash))
        }
        runDownloadBatch("assets", tasks, listener)
    }

    fun downloadLoggingConfig(version: VersionJson, listener: ProgressListener) {
        val client = version.logging?.client ?: return
        val art = client.file
        if (art.url.isEmpty()) return
        val target = File(paths.root, "logging/" + File(art.path).name)
        http.download(source.rewrite(art.url), target, art.sha1.ifEmpty { null })
    }

    private fun runDownloadBatch(stage: String, tasks: List<Triple<String, File, String?>>, listener: ProgressListener) {
        val pending = tasks.filter { (_, file, sha) ->
            if (!file.exists()) return@filter true
            if (sha != null && sha.isNotEmpty()) {
                file.length() == 0L || Hashes.sha1(file) != sha
            } else false
        }
        val total = pending.size.toLong()
        listener.onProgress(InstallProgress(stage, 0, total, "files=${pending.size}"))
        if (pending.isEmpty()) return
        val done = AtomicLong(0)
        val progressLock = Any()
        val threads = minOf(8, maxOf(1, Runtime.getRuntime().availableProcessors()))
        val chunks = pending.chunked(maxOf(1, (pending.size + threads - 1) / threads))
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = chunks.map { chunk ->
            Thread {
                for ((url, target, sha) in chunk) {
                    try {
                        downloadWithFallback(url, target, sha?.ifEmpty { null })
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        val current = done.incrementAndGet()
                        synchronized(progressLock) {
                            listener.onProgress(InstallProgress(stage, current, total, target.name))
                        }
                    }
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        if (errors.isNotEmpty()) {
            listener.onLog("download errors: " + errors.take(3).joinToString("; ") { it.message ?: it.javaClass.simpleName })
        }
    }

    private fun downloadWithFallback(url: String, target: File, sha: String?) {
        try {
            http.download(source.rewrite(url), target, sha)
        } catch (t: Throwable) {
            if (source == DownloadSource.Official) throw t
            http.download(url, target, sha)
        }
    }
}