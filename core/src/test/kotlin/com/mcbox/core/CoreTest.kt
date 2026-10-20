package com.mcbox.core

import com.mcbox.core.install.MirrorCatalog
import com.mcbox.core.install.VersionInstaller
import com.mcbox.core.launch.ArgumentBuilder
import com.mcbox.core.launch.LaunchConfig
import com.mcbox.core.launch.VersionResolver
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import com.mcbox.core.model.VersionJson
import com.mcbox.core.model.VersionManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineUuidTest {

    @Test
    fun offlineUuidIsStableAndDashFormatted() {
        val uuid = AccountUuid.fromOfflineName("Notch")
        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", uuid)
        assertEquals(uuid, AccountUuid.fromOfflineName("Notch"))
        assertEquals(36, uuid.length)
        assertEquals(32, AccountUuid.undashed(uuid).length)
    }
}

class RuleEvaluatorTest {

    @Test
    fun noRulesAllowsLibrary() {
        assertTrue(RuleEvaluator.isAllowed(emptyList(), PlatformInfo.android()))
    }

    @Test
    fun linuxRuleAllowsAndroid() {
        val rules = listOf(
            com.mcbox.core.model.Rule(action = "allow", os = com.mcbox.core.model.Rule.OsRule(name = "linux"))
        )
        assertTrue(RuleEvaluator.isAllowed(rules, PlatformInfo.android()))
    }

    @Test
    fun windowsOnlyIsRejected() {
        val rules = listOf(
            com.mcbox.core.model.Rule(action = "allow", os = com.mcbox.core.model.Rule.OsRule(name = "windows"))
        )
        assertTrue(!RuleEvaluator.isAllowed(rules, PlatformInfo.android()))
    }

    @Test
    fun disallowOverridesAllow() {
        val rules = listOf(
            com.mcbox.core.model.Rule(action = "allow"),
            com.mcbox.core.model.Rule(action = "disallow", os = com.mcbox.core.model.Rule.OsRule(name = "android"))
        )
        assertTrue(!RuleEvaluator.isAllowed(rules, PlatformInfo.android()))
    }
}

class ArgumentBuilderTest {

    private fun sampleVersion(): VersionJson {
        val jvmArgs = """
            [
              {"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
              "-Djava.library.path=${'$'}{natives_directory}",
              "-cp",
              "${'$'}{classpath}",
              "-Dminecraft.launcher.brand=${'$'}{launcher_name}"
            ]
        """.trimIndent()
        val gameArgs = """
            [
              "--username", "${'$'}{auth_player_name}",
              "--uuid", "${'$'}{auth_uuid}",
              "--accessToken", "${'$'}{auth_access_token}",
              "--gameDir", "${'$'}{game_directory}",
              "--assetsDir", "${'$'}{assets_root}",
              "--assetIndex", "${'$'}{assets_index_name}",
              "--version", "${'$'}{version_name}",
              {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}
            ]
        """.trimIndent()
        return com.mcbox.core.util.JsonCompact.decodeFromString(
            VersionJson.serializer(),
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "assetIndex": {"id":"5","sha1":"x","size":1,"totalSize":1,"url":"https://example.com/a.json"},
              "arguments": {"jvm": $jvmArgs, "game": $gameArgs},
              "libraries": []
            }
            """.trimIndent()
        )
    }

    @Test
    fun placeholdersAreReplaced() {
        val version = sampleVersion()
        val account = Account(
            id = AccountUuid.fromOfflineName("Steve"),
            type = AccountType.Offline,
            displayName = "Steve",
            uuid = AccountUuid.fromOfflineName("Steve"),
            accessToken = "0"
        )
        val config = LaunchConfig(
            versionId = "1.20.1",
            versionJson = version,
            gameDir = File("/tmp/game"),
            assetsDir = File("/tmp/assets"),
            librariesDir = File("/tmp/libs"),
            nativesDir = File("/tmp/natives"),
            classpath = listOf(File("/tmp/a.jar"), File("/tmp/b.jar")),
            account = account
        )
        val builder = ArgumentBuilder()
        val jvm = builder.buildJvmArgs(config)
        assertTrue(jvm.any { it.startsWith("-Djava.library.path=") })
        assertTrue(jvm.contains("-cp"))
        assertTrue(jvm.any { it.contains("a.jar") && it.contains("b.jar") })
        assertTrue(jvm.none { it == "-XstartOnFirstThread" })
        assertTrue(jvm.any { it == "-Dminecraft.launcher.brand=mcbox" })

        val game = builder.buildGameArgs(config)
        assertTrue(game.contains("Steve"))
        assertTrue(game.contains("1.20.1"))
        assertTrue(game.contains("/tmp/game"))
        assertTrue(game.contains("/tmp/assets"))
        assertTrue(game.contains("5"))
        assertTrue(!game.contains("--demo"))
    }

    @Test
    fun demoFlagIsIncludedWhenRequested() {
        val version = sampleVersion()
        val account = Account(
            id = "x",
            type = AccountType.Offline,
            displayName = "Alex",
            uuid = AccountUuid.fromOfflineName("Alex"),
            accessToken = "0"
        )
        val config = LaunchConfig(
            versionId = "1.20.1",
            versionJson = version,
            gameDir = File("/tmp/game"),
            assetsDir = File("/tmp/assets"),
            librariesDir = File("/tmp/libs"),
            nativesDir = File("/tmp/natives"),
            classpath = emptyList(),
            account = account,
            demoMode = true
        )
        val game = ArgumentBuilder().buildGameArgs(config)
        assertTrue(game.contains("--demo"))
    }
}

class LiveManifestTest {

    @Test
    fun downloadManifestAndResolveVersion() {
        val temp = File(System.getProperty("java.io.tmpdir"), "mcbox-live-test")
        temp.deleteRecursively()
        val paths = GamePaths(temp).ensure()
        val installer = VersionInstaller(paths, mirrors = MirrorCatalog.presets)

        val manifest: VersionManifest = installer.fetchManifest()
        assertTrue(manifest.versions.isNotEmpty(), "manifest empty")
        assertNotNull(manifest.latest.release)

        val target = manifest.versions.firstOrNull { it.id == "1.20.1" }
            ?: manifest.versions.first { it.type == "release" }
        val json = installer.fetchVersionJson(target)
        assertTrue(json.id.isNotEmpty())
        assertTrue(json.mainClass.isNotEmpty(), "mainClass empty for ${target.id}")
        assertTrue(json.libraries.isNotEmpty(), "libraries empty for ${target.id}")

        val dir = paths.versionDir(target.id)
        dir.mkdirs()
        paths.versionJson(target.id).writeText(
            com.mcbox.core.util.JsonPretty.encodeToString(VersionJson.serializer(), json)
        )

        val resolver = VersionResolver(paths)
        val merged = resolver.loadMerged(target.id)
        assertEquals(target.id, merged.id)
        val allowed = resolver.applicableLibraries(merged, PlatformInfo.android())
        assertTrue(allowed.isNotEmpty())

        val javaMajor = resolver.requiredJavaMajor(merged)
        assertTrue(javaMajor >= 8)

        val account = Account(
            id = AccountUuid.fromOfflineName("Tester"),
            type = AccountType.Offline,
            displayName = "Tester",
            uuid = AccountUuid.fromOfflineName("Tester"),
            accessToken = "0"
        )
        val config = LaunchConfig(
            versionId = target.id,
            versionJson = merged,
            gameDir = File(temp, "instance"),
            assetsDir = paths.assets,
            librariesDir = paths.libraries,
            nativesDir = paths.nativeDir(target.id),
            classpath = listOf(File(temp, "libs/fake.jar")),
            account = account,
            maxMemoryMb = 1024
        )
        val builder = ArgumentBuilder()
        val jvm = builder.buildJvmArgs(config)
        val game = builder.buildGameArgs(config)

        assertTrue(jvm.any { it.startsWith("-Djava.library.path=") }, "no library path")
        assertTrue(game.contains("Tester"), "player name missing")
        assertTrue(game.contains(target.id), "version missing")
        assertTrue(
            game.none { it.contains("\${") },
            "unresolved placeholder in game args: " + game.filter { it.contains("\${") }
        )
        assertTrue(
            jvm.none { it.contains("\${") },
            "unresolved placeholder in jvm args: " + jvm.filter { it.contains("\${") }
        )
        temp.deleteRecursively()
    }

    @Test
    fun assetsIndexIsReachable() {
        val temp = File(System.getProperty("java.io.tmpdir"), "mcbox-assets-test")
        temp.deleteRecursively()
        val paths = GamePaths(temp).ensure()
        val installer = VersionInstaller(paths, mirrors = MirrorCatalog.presets)
        val manifest = installer.fetchManifest()
        val target = manifest.versions.firstOrNull { it.id == "1.20.1" }
            ?: manifest.versions.first { it.type == "release" }
        val json = installer.fetchVersionJson(target)
        val index = json.assetIndex
        assertNotNull(index)
        assertTrue(index.url.isNotEmpty())
        val indexFile = paths.indexFile(index.id)
        installer.downloadAssets(json, object : com.mcbox.core.install.ProgressListener {
            override fun onProgress(progress: com.mcbox.core.install.InstallProgress) {}
        })
        assertTrue(indexFile.exists(), "asset index not downloaded")
        val text = indexFile.readText()
        assertTrue(text.contains("\"objects\""), "asset index malformed")
        temp.deleteRecursively()
    }
}