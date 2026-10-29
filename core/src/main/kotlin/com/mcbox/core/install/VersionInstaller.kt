package com.mcbox.core.install

import com.mcbox.core.GamePaths
import com.mcbox.core.PlatformInfo
import com.mcbox.core.RuleEvaluator
import com.mcbox.core.model.VersionJson
import com.mcbox.core.model.VersionManifest
import com.mcbox.core.net.Hashes
import com.mcbox.core.net.Http
import com.mcbox.core.util.JsonCompact
import com.mcbox.core.util.JsonPretty
import com.mcbox.core.util.obj
import com.mcbox.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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
    private val mirrors: List<Mirror> = listOf(MirrorCatalog.presets.first()),
    private val platform: PlatformInfo = PlatformInfo.android()
) {

    private val activeMirror: Mirror
        get() = mirrors.firstOrNull() ?: MirrorCatalog.presets.first()

    fun fetchManifest(): VersionManifest {
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            if (mirror.manifestUrl.isEmpty()) continue
            try {
                val text = http.getText(mirror.manifestUrl)
                return JsonCompact.decodeFromString(VersionManifest.serializer(), text)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("no mirror available")
    }

    fun fetchVersionJson(summary: VersionManifest.VersionSummary): VersionJson {
        val text = fetchTextWithFallback(summary.url)
        return JsonCompact.decodeFromString(VersionJson.serializer(), text)
    }

    private fun fetchTextWithFallback(url: String): String {
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            val candidate = mirror.rewrite(url)
            if (candidate.isEmpty()) continue
            try {
                return http.getText(candidate)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("cannot fetch $url")
    }

    fun install(
        versionId: String,
        versionUrl: String,
        listener: ProgressListener,
        includeAssets: Boolean = true
    ): File {
        paths.ensure()
        listener.onLog("install $versionId via ${activeMirror.name}")
        val raw = fetchTextWithFallback(versionUrl)
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

    /**
     * 从本地已存在的 version.json 补齐缺失的 libraries。
     *
     * 用于：用户之前安装的版本（尤其 fabric/forge）只写入了 json、库没下全，
     * 启动前自动把缺的构件补上，避免 ClassNotFoundException。
     */
    fun repairLibrariesFromLocalJson(versionId: String, listener: ProgressListener): Boolean {
        val jsonFile = paths.versionJson(versionId)
        if (!jsonFile.exists()) return false
        return try {
            val version = JsonCompact.decodeFromString(VersionJson.serializer(), jsonFile.readText())
            listener.onLog("repair libraries for $versionId")
            downloadLibraries(version, listener)
            true
        } catch (t: Throwable) {
            listener.onLog("repair libraries failed: ${t.message}")
            false
        }
    }

    fun downloadLibraries(version: VersionJson, listener: ProgressListener) {
        val libs = version.libraries.filter { RuleEvaluator.isAllowed(it.rules, platform) }
        val tasks = mutableListOf<Triple<String, File, String?>>()
        for (lib in libs) {
            if (lib.natives.isNotEmpty()) {
                for ((os, template) in lib.natives) {
                    if (os != "linux" && os != "android") continue
                    val classifier = template.replace("\${arch}", if (platform.arch.contains("64")) "64" else "32")
                    val artifactCls = lib.downloads?.classifiers?.get(classifier) ?: continue
                    if (artifactCls.path.isEmpty() || artifactCls.url.isEmpty()) continue
                    tasks.add(Triple(artifactCls.url, paths.libraryPath(artifactCls.path), artifactCls.sha1))
                }
                continue
            }
            val artifact = lib.downloads?.artifact
            // fabric/forge 等 loader 的 version.json 常只给 maven 根 url，没有 downloads 段，
            // 这类必须按 maven 布局自己拼出构件地址，否则整批库都会被跳过。
            if (artifact != null && artifact.url.isNotEmpty()) {
                val rel = artifact.path.ifEmpty { lib.mavenPath() }
                tasks.add(Triple(artifact.url, paths.libraryPath(rel), artifact.sha1))
            } else {
                val base = lib.url ?: continue
                if (base.isEmpty()) continue
                val rel = lib.mavenPath()
                val fixed = if (base.endsWith("/")) base else "$base/"
                tasks.add(Triple(fixed + rel, paths.libraryPath(rel), null))
            }
        }
        runDownloadBatch("libraries", tasks, listener)
    }

    fun downloadClient(version: VersionJson, versionId: String, listener: ProgressListener) {
        val client = version.downloads?.client ?: return
        if (client.url.isEmpty()) return
        val target = paths.versionJar(versionId)
        listener.onProgress(InstallProgress("client", 0, 1, "game jar"))
        downloadWithFallback(client.url, target, client.sha1.ifEmpty { null })
        listener.onProgress(InstallProgress("client", 1, 1, "game jar"))
    }

    fun downloadAssets(version: VersionJson, listener: ProgressListener) {
        val index = version.assetIndex ?: return
        if (index.url.isEmpty()) return
        val indexFile = paths.indexFile(index.id)
        if (!indexFile.exists() || (index.sha1.isNotEmpty() && Hashes.sha1(indexFile) != index.sha1)) {
            downloadWithFallback(index.url, indexFile, index.sha1.ifEmpty { null })
        }
        val root = JsonCompact.parseToJsonElement(indexFile.readText()).jsonObject
        val objects = root.obj("objects") ?: return
        val tasks = mutableListOf<Triple<String, File, String?>>()
        for ((_, value) in objects) {
            val obj = value as? JsonObject ?: continue
            val hash = obj.str("hash") ?: continue
            val target = paths.objectPath(hash)
            if (target.exists() && target.length() > 0) continue
            val url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash
            tasks.add(Triple(url, target, hash))
        }
        runDownloadBatch("assets", tasks, listener)
    }

    private fun runDownloadBatch(stage: String, tasks: List<Triple<String, File, String?>>, listener: ProgressListener) {
        val pending = tasks.filter { (_, file, sha) ->
            if (!file.exists() || file.length() == 0L) return@filter true
            if (sha != null && sha.isNotEmpty()) Hashes.sha1(file) != sha else false
        }
        val total = pending.size.toLong()
        listener.onProgress(InstallProgress(stage, 0, total, "files=${pending.size}"))
        if (pending.isEmpty()) return
        val done = AtomicLong(0)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = minOf(8, maxOf(1, Runtime.getRuntime().availableProcessors()))
        val chunkSize = maxOf(1, (pending.size + threads - 1) / threads)
        val workers = pending.chunked(chunkSize).map { chunk ->
            Thread {
                for ((url, target, sha) in chunk) {
                    try {
                        downloadWithFallback(url, target, sha?.ifEmpty { null })
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        listener.onProgress(InstallProgress(stage, done.incrementAndGet(), total, target.name))
                    }
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        if (errors.isNotEmpty()) {
            listener.onLog("failed ${errors.size} files: " + errors.take(2).joinToString("; ") { it.message ?: "" })
        }
    }

    private fun downloadWithFallback(url: String, target: File, sha: String?) {
        var lastError: Throwable? = null
        val tried = mutableSetOf<String>()
        for (mirror in mirrors) {
            val candidate = mirror.rewrite(url)
            if (candidate.isEmpty() || !tried.add(candidate)) continue
            try {
                http.download(candidate, target, sha)
                return
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (tried.isEmpty()) {
            http.download(url, target, sha)
            return
        }
        throw lastError ?: IllegalStateException("cannot download $url")
    }
}