package com.mcbox.core.launch

import com.mcbox.core.PlatformInfo
import com.mcbox.core.RuleEvaluator
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import com.mcbox.core.model.Library
import com.mcbox.core.model.VersionJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

data class LaunchConfig(
    val versionId: String,
    val versionJson: VersionJson,
    val gameDir: File,
    val assetsDir: File,
    val librariesDir: File,
    val nativesDir: File,
    val classpath: List<File>,
    val account: Account,
    val maxMemoryMb: Int = 2048,
    val minMemoryMb: Int = 0,
    val width: Int = 1280,
    val height: Int = 720,
    val javaMajor: Int = 17,
    val extraJvmArgs: List<String> = emptyList(),
    val extraGameArgs: List<String> = emptyList(),
    val serverAddress: String? = null,
    val port: Int? = null,
    val demoMode: Boolean = false,
    val launchTarget: String? = null,
    val userType: String = "legacy",
    val versionType: String = "release",
    val customJvmLibraryPath: String? = null,
    val authUuid: String = "",
    val authAccessToken: String = "",
    val userProperties: String = "{}",
    val clientId: String = "",
    val xuid: String = "",
    val quickPlayPath: String? = null,
    val javaExecutable: String = "java"
)

class ArgumentBuilder(
    private val platform: PlatformInfo = PlatformInfo.android()
) {

    private val placeholderRegex = Regex("\\$\\{([^}]+)}")

    fun buildJvmArgs(config: LaunchConfig): List<String> {
        val args = mutableListOf<String>()
        val json = config.versionJson
        val classpathString = config.classpath.joinToString(File.pathSeparator) { it.absolutePath }

        val nativesDir = config.nativesDir.absolutePath
        val libraryDir = config.librariesDir.absolutePath
        val gameDir = config.gameDir.absolutePath
        val assetsDir = config.assetsDir.absolutePath

        val values = mutableMapOf(
            "natives_directory" to nativesDir,
            "launcher_name" to "mcbox",
            "launcher_version" to "1.0.0",
            "classpath" to classpathString,
            "classpath_separator" to File.pathSeparator,
            "library_directory" to libraryDir,
            "version_name" to config.versionId,
            "game_directory" to gameDir,
            "assets_root" to assetsDir,
            "assets_index_name" to (json.assetIndex?.id ?: json.assets),
            "auth_player_name" to config.account.displayName,
            "auth_uuid" to AccountUuid.undashed(config.account.uuid),
            "auth_access_token" to config.account.accessToken.ifEmpty { "0" },
            "auth_session" to "token:" + config.account.accessToken.ifEmpty { "0" } + ":" + AccountUuid.undashed(config.account.uuid),
            "user_type" to config.userType,
            "version_type" to config.versionType,
            "user_properties" to config.userProperties,
            "clientid" to config.clientId,
            "auth_xuid" to config.xuid,
            "resolution_width" to config.width.toString(),
            "resolution_height" to config.height.toString(),
            "game_assets" to assetsDir,
            "library_directory_natives" to nativesDir
        )

        val jvmArgsList = json.arguments?.jvm
        if (jvmArgsList != null && jvmArgsList.isNotEmpty()) {
            args.addAll(expandArgumentList(jvmArgsList, values))
        } else {
            args.add("-Djava.library.path=" + nativesDir)
            args.add("-cp")
            args.add(classpathString)
        }

        if (!args.any { it.startsWith("-Djava.library.path") }) {
            args.add("-Djava.library.path=" + nativesDir)
        }
        return args
    }

    fun buildGameArgs(config: LaunchConfig): List<String> {
        val json = config.versionJson
        val args = mutableListOf<String>()
        val values = mutableMapOf(
            "auth_player_name" to config.account.displayName,
            "version_name" to config.versionId,
            "game_directory" to config.gameDir.absolutePath,
            "assets_root" to config.assetsDir.absolutePath,
            "assets_index_name" to (json.assetIndex?.id ?: json.assets),
            "auth_uuid" to AccountUuid.undashed(config.account.uuid),
            "auth_access_token" to config.account.accessToken.ifEmpty { "0" },
            "auth_session" to "token:" + config.account.accessToken.ifEmpty { "0" } + ":" + AccountUuid.undashed(config.account.uuid),
            "user_type" to config.userType,
            "version_type" to config.versionType,
            "user_properties" to config.userProperties,
            "clientid" to config.clientId,
            "auth_xuid" to config.xuid,
            "resolution_width" to config.width.toString(),
            "resolution_height" to config.height.toString()
        )

        val gameArgs = json.arguments?.game
        if (gameArgs != null && gameArgs.isNotEmpty()) {
            args.addAll(expandArgumentList(gameArgs, values))
        } else {
            val legacy = json.minecraftArguments
            if (!legacy.isNullOrEmpty()) {
                args.addAll(legacy.split(" ").filter { it.isNotBlank() })
            }
        }

        val port = config.port
        if (config.serverAddress != null && config.serverAddress.isNotEmpty()) {
            args.add("--server")
            args.add(config.serverAddress)
            if (port != null) {
                args.add("--port")
                args.add(port.toString())
            }
        }

        if (config.demoMode) args.add("--demo")

        if (config.quickPlayPath != null) {
            args.add("--quickPlayPath")
            args.add(config.quickPlayPath)
        }

        val target = config.launchTarget
        if (target != null && target.isNotEmpty()) {
            args.add("--quickPlaySingleplayer")
            args.add(target)
        }
        return args
    }

    private fun expandArgumentList(list: List<JsonElement>, values: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        for (element in list) {
            when (element) {
                is JsonPrimitive -> {
                    if (element.isString) {
                        out.add(applyPlaceholders(element.content, values))
                    }
                }
                is JsonObject -> {
                    if (isRuleSatisfied(element, platform)) {
                        val value = element["value"]
                        when (value) {
                            is JsonPrimitive -> out.add(applyPlaceholders(value.content, values))
                            is JsonArray -> value.forEach { item ->
                                if (item is JsonPrimitive && item.isString) {
                                    out.add(applyPlaceholders(item.content, values))
                                }
                            }
                            else -> {}
                        }
                    }
                }
                else -> {}
            }
        }
        return out
    }

    private fun isRuleSatisfied(obj: JsonObject, platform: PlatformInfo): Boolean {
        val rulesElement = obj["rules"] as? JsonArray ?: return true
        val rules = rulesElement.mapNotNull { item ->
            val ruleObj = item as? JsonObject ?: return@mapNotNull null
            val action = (ruleObj["action"] as? JsonPrimitive)?.contentOrNull ?: "allow"
            val osObj = ruleObj["os"] as? JsonObject
            val featuresObj = ruleObj["features"] as? JsonObject
            com.mcbox.core.model.Rule(
                action = action,
                os = osObj?.let {
                    com.mcbox.core.model.Rule.OsRule(
                        name = (it["name"] as? JsonPrimitive)?.contentOrNull,
                        version = (it["version"] as? JsonPrimitive)?.contentOrNull,
                        arch = (it["arch"] as? JsonPrimitive)?.contentOrNull
                    )
                },
                features = featuresObj?.mapValues { (_, v) ->
                    ((v as? JsonPrimitive)?.contentOrNull ?: "false").toBoolean()
                } ?: emptyMap()
            )
        }
        return RuleEvaluator.isAllowed(rules, platform)
    }

    private fun applyPlaceholders(input: String, values: Map<String, String>): String {
        if (!input.contains("\${")) return input
        return placeholderRegex.replace(input) { match ->
            val key = match.groupValues[1]
            values[key] ?: match.value
        }
    }

    fun mainClass(config: LaunchConfig): String = config.versionJson.mainClass
}