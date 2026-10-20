package com.mcbox.core.loader

import com.mcbox.core.net.Endpoints
import com.mcbox.core.net.Http
import com.mcbox.core.util.JsonCompact
import com.mcbox.core.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException

data class LoaderVersion(
    val loader: String,
    val version: String,
    val stable: Boolean = true,
    val gameVersion: String = ""
)

class FabricInstaller(private val http: Http = Http()) {

    fun listGameVersions(): List<String> {
        val text = http.getText("${Endpoints.FABRIC_META}/versions/game")
        val arr = JsonCompact.parseToJsonElement(text).jsonArray
        return arr.mapNotNull { it.jsonObject.str("version") }
    }

    fun listLoaderVersions(gameVersion: String): List<LoaderVersion> {
        val text = http.getText("${Endpoints.FABRIC_META}/versions/loader/$gameVersion")
        val arr = JsonCompact.parseToJsonElement(text).jsonArray
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val loader = obj["loader"]?.jsonObject ?: return@mapNotNull null
            val ver = loader.str("version") ?: return@mapNotNull null
            LoaderVersion(
                loader = "fabric",
                version = ver,
                stable = loader["stable"]?.toString()?.toBoolean() ?: true,
                gameVersion = gameVersion
            )
        }
    }

    fun latestLoader(gameVersion: String): String? =
        listLoaderVersions(gameVersion).firstOrNull { it.stable }?.version

    fun profileJson(gameVersion: String, loaderVersion: String): JsonObject {
        val url = "${Endpoints.FABRIC_META}/versions/loader/$gameVersion/$loaderVersion/profile/json"
        val text = http.getText(url)
        return JsonCompact.parseToJsonElement(text).jsonObject
    }

    fun saveProfileJson(gameVersion: String, loaderVersion: String, dir: java.io.File): java.io.File {
        val obj = profileJson(gameVersion, loaderVersion)
        val id = obj.str("id") ?: "fabric-loader-$loaderVersion-$gameVersion"
        val target = java.io.File(java.io.File(dir, id), "$id.json")
        target.parentFile?.mkdirs()
        target.writeText(obj.toString())
        return target
    }
}

class QuiltInstaller(private val http: Http = Http()) {

    fun listLoaderVersions(gameVersion: String): List<LoaderVersion> {
        val text = http.getText("${Endpoints.QUILT_META}/versions/loader/$gameVersion")
        val arr = JsonCompact.parseToJsonElement(text).jsonArray
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val loader = obj["loader"]?.jsonObject ?: return@mapNotNull null
            val ver = loader.str("version") ?: return@mapNotNull null
            LoaderVersion("quilt", ver, stable = true, gameVersion = gameVersion)
        }
    }

    fun profileJson(gameVersion: String, loaderVersion: String): JsonObject {
        val url = "${Endpoints.QUILT_META}/versions/loader/$gameVersion/$loaderVersion/profile/json"
        return JsonCompact.parseToJsonElement(http.getText(url)).jsonObject
    }

    fun saveProfileJson(gameVersion: String, loaderVersion: String, dir: java.io.File): java.io.File {
        val obj = profileJson(gameVersion, loaderVersion)
        val id = obj.str("id") ?: "quilt-loader-$loaderVersion-$gameVersion"
        val target = java.io.File(java.io.File(dir, id), "$id.json")
        target.parentFile?.mkdirs()
        target.writeText(obj.toString())
        return target
    }
}

class NeoForgeInstaller(private val http: Http = Http()) {

    fun listVersions(gameVersion: String): List<String> {
        val text = http.getText("${Endpoints.NEOFORGE_MAVEN}/net/neoforged/neoforge/maven-metadata.xml")
        val clean = text.replace(Regex("\\s+"), " ")
        val versions = Regex("<version>([^<]+)</version>").findAll(clean).map { it.groupValues[1] }.toList()
        return versions.filter { matchesGame(it, gameVersion) }
    }

    private fun matchesGame(neoforgeVersion: String, gameVersion: String): Boolean {
        val parts = gameVersion.split(".")
        if (parts.size < 2) return false
        val major = parts.getOrNull(1) ?: return false
        val minor = parts.getOrNull(2) ?: return false
        return neoforgeVersion.startsWith("$major.$minor.")
    }

    fun installerUrl(version: String): String =
        "${Endpoints.NEOFORGE_MAVEN}/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"
}

class ForgeInstaller(private val http: Http = Http()) {

    fun listVersions(gameVersion: String): List<String> {
        val url = "${Endpoints.FORGE_MAVEN}/net/minecraftforge/forge/maven-metadata.xml"
        val text = http.getText(url)
        val clean = text.replace(Regex("\\s+"), " ")
        return Regex("<version>([^<]+)</version>").findAll(clean)
            .map { it.groupValues[1] }
            .filter { it.startsWith("$gameVersion-") }
            .toList()
            .reversed()
    }

    fun installerUrl(fullVersion: String): String =
        "${Endpoints.FORGE_MAVEN}/net/minecraftforge/forge/$fullVersion/forge-$fullVersion-installer.jar"
}

object LoaderType {
    const val VANILLA = "vanilla"
    const val FABRIC = "fabric"
    const val QUILT = "quilt"
    const val FORGE = "forge"
    const val NEOFORGE = "neoforge"
    const val OPTIFINE = "optifine"
}