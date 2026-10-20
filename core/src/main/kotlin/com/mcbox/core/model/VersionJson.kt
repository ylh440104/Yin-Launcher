package com.mcbox.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VersionManifest(
    val latest: Latest = Latest(),
    val versions: List<VersionSummary> = emptyList()
) {
    @Serializable
    data class Latest(
        val release: String = "",
        val snapshot: String = ""
    )

    @Serializable
    data class VersionSummary(
        val id: String,
        val type: String = "release",
        val url: String,
        @SerialName("releaseTime") val releaseTime: String = "",
        @SerialName("time") val time: String = ""
    )
}

@Serializable
data class VersionJson(
    val id: String = "",
    val type: String = "release",
    @SerialName("mainClass") val mainClass: String = "",
    @SerialName("inheritsFrom") val inheritsFrom: String? = null,
    val assets: String = "",
    @SerialName("assetIndex") val assetIndex: AssetIndex? = null,
    val downloads: VersionDownloads? = null,
    val libraries: List<Library> = emptyList(),
    val arguments: Arguments? = null,
    @SerialName("minecraftArguments") val minecraftArguments: String? = null,
    @SerialName("javaVersion") val javaVersion: JavaVersion? = null,
    val logging: Logging? = null,
    @SerialName("complianceLevel") val complianceLevel: Int = 0,
    @SerialName("minimumLauncherVersion") val minimumLauncherVersion: Int = 0
) {
    @Serializable
    data class AssetIndex(
        val id: String = "",
        val sha1: String = "",
        val size: Long = 0,
        @SerialName("totalSize") val totalSize: Long = 0,
        val url: String = ""
    )

    @Serializable
    data class VersionDownloads(
        val client: Artifact? = null,
        val server: Artifact? = null
    )

    @Serializable
    data class Artifact(
        val path: String = "",
        val sha1: String = "",
        val size: Long = 0,
        val url: String = ""
    )

    @Serializable
    data class JavaVersion(
        val component: String = "",
        val majorVersion: Int = 8
    )

    @Serializable
    data class Logging(
        val client: LoggingClient? = null
    ) {
        @Serializable
        data class LoggingClient(
            val argument: String = "",
            val file: Artifact = Artifact(),
            val type: String = ""
        )
    }
}

@Serializable
data class Library(
    val name: String = "",
    val downloads: LibraryDownloads? = null,
    val rules: List<Rule> = emptyList(),
    val natives: Map<String, String> = emptyMap(),
    val extract: Extract? = null,
    val url: String? = null,
    @SerialName("clientreq") val clientReq: Boolean = true
) {
    val group: String get() = name.substringBefore(':', "")
    val artifactName: String get() = name.split(':').getOrNull(1) ?: ""
    val version: String get() = name.split(':').getOrNull(2) ?: ""

    fun mavenPath(): String {
        val parts = name.split(':')
        if (parts.size < 3) return name
        val classifier = parts.getOrNull(3)
        val fileName = buildString {
            append(parts[1]).append('-').append(parts[2])
            if (classifier != null && classifier.isNotEmpty()) append('-').append(classifier)
            append(".jar")
        }
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + fileName
    }

    fun artifactPath(): String =
        downloads?.artifact?.path?.takeIf { it.isNotEmpty() } ?: mavenPath()
}

@Serializable
data class LibraryDownloads(
    val artifact: VersionJson.Artifact? = null,
    val classifiers: Map<String, VersionJson.Artifact> = emptyMap()
)

@Serializable
data class Extract(
    val exclude: List<String> = emptyList()
)

@Serializable
data class Rule(
    val action: String = "allow",
    val os: OsRule? = null,
    val features: Map<String, Boolean> = emptyMap()
) {
    @Serializable
    data class OsRule(
        val name: String? = null,
        val version: String? = null,
        val arch: String? = null
    )
}

@Serializable
data class Arguments(
    val game: List<JsonElement> = emptyList(),
    val jvm: List<JsonElement> = emptyList()
)
