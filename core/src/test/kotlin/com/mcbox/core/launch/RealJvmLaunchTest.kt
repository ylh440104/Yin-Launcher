package com.mcbox.core.launch

import com.mcbox.core.GamePaths
import com.mcbox.core.PlatformInfo
import com.mcbox.core.install.MirrorCatalog
import com.mcbox.core.install.VersionInstaller
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import com.mcbox.core.model.VersionJson
import com.mcbox.core.util.JsonCompact
import com.mcbox.core.util.JsonPretty
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealJvmLaunchTest {

    private fun sampleVersion(): VersionJson = JsonCompact.decodeFromString(
        VersionJson.serializer(),
        """
        {
          "id": "mcbox-selftest",
          "mainClass": "com.mcbox.selftest.Bootstrap",
          "assets": "legacy",
          "arguments": {
            "jvm": ["-Djava.library.path=${'$'}{natives_directory}", "-cp", "${'$'}{classpath}"],
            "game": ["--username", "${'$'}{auth_player_name}", "--version", "${'$'}{version_name}", "--gameDir", "${'$'}{game_directory}"]
          },
          "libraries": []
        }
        """.trimIndent()
    )

    @Test
    fun realJvmStartsWithGeneratedArguments() {
        val temp = File(System.getProperty("java.io.tmpdir"), "mcbox-jvm-test")
        temp.deleteRecursively()
        temp.mkdirs()

        val classesDir = File(temp, "classes")
        classesDir.mkdirs()
        val source = File(temp, "Bootstrap.java")
        source.writeText(
            """
            package com.mcbox.selftest;

            import java.io.FileWriter;
            import java.util.Arrays;

            public class Bootstrap {
                public static void main(String[] args) {
                    try {
                        FileWriter fw = new FileWriter(System.getProperty("mcbox.out", "result.txt"));
                        fw.write("args=" + Arrays.toString(args) + "\n");
                        fw.write("user=" + System.getProperty("user.home") + "\n");
                        fw.write("lib=" + System.getProperty("java.library.path") + "\n");
                        fw.write("cp=" + System.getProperty("java.class.path") + "\n");
                        fw.write("maxmem=" + Runtime.getRuntime().maxMemory() + "\n");
                        fw.write("ok=true\n");
                        fw.close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            """.trimIndent()
        )

        val javac = File(System.getProperty("java.home"), "bin/javac").absolutePath
        val compile = ProcessBuilder(
            javac, "-encoding", "UTF-8", "-d", classesDir.absolutePath, source.absolutePath
        ).redirectErrorStream(true).start()
        val compileOut = compile.inputStream.bufferedReader().readText()
        assertTrue(compile.waitFor(120, TimeUnit.SECONDS), "javac hung")
        assertEquals(0, compile.exitValue(), "javac failed: $compileOut")
        assertTrue(File(classesDir, "com/mcbox/selftest/Bootstrap.class").exists(), "class not produced")

        val gameDir = File(temp, "instance")
        gameDir.mkdirs()
        val nativesDir = File(temp, "natives")
        nativesDir.mkdirs()

        val account = Account(
            id = AccountUuid.fromOfflineName("Tester"),
            type = AccountType.Offline,
            displayName = "Tester",
            uuid = AccountUuid.fromOfflineName("Tester"),
            accessToken = "0"
        )

        val config = LaunchConfig(
            versionId = "mcbox-selftest",
            versionJson = sampleVersion(),
            gameDir = gameDir,
            assetsDir = File(temp, "assets"),
            librariesDir = File(temp, "libs"),
            nativesDir = nativesDir,
            classpath = listOf(classesDir),
            account = account,
            maxMemoryMb = 256,
            minMemoryMb = 128,
            javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        )

        val command = LaunchPlanner.plan(config)
        val problems = LaunchPlanner.validate(command, config)
        assertTrue(problems.isEmpty(), "validation problems: $problems")
        assertTrue(command.classpathEntries().contains(classesDir.absolutePath))
        assertEquals(256, command.memoryMb())

        val resultFile = File(temp, "result.txt")
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val commandLine = command.toCommandLine().toMutableList()
        commandLine.add(1, "-Dmcbox.out=${resultFile.absolutePath}")
        val pb = ProcessBuilder(commandLine)
        pb.directory(gameDir)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "jvm hung")
        assertEquals(0, process.exitValue(), "jvm exit ${process.exitValue()}: $output")

        assertTrue(resultFile.exists(), "bootstrap did not produce output: $output")
        val text = resultFile.readText()
        assertTrue(text.contains("ok=true"), "bootstrap not completed: $text")
        assertTrue(text.contains("Tester"), "player name not passed: $text")
        assertTrue(text.contains("mcbox-selftest"), "version not passed: $text")
        assertTrue(text.contains(nativesDir.absolutePath), "natives path missing: $text")
        assertTrue(text.contains("user=${gameDir.absolutePath}"), "user.home not applied: $text")
        assertTrue(text.contains(classesDir.absolutePath), "classpath not applied: $text")
        assertTrue(text.contains("maxmem="), "max memory not reported: $text")

        temp.deleteRecursively()
    }

    @Test
    fun realVersionJsonProducesRunnablePlan() {
        val temp = File(System.getProperty("java.io.tmpdir"), "mcbox-plan-test")
        temp.deleteRecursively()
        val paths = GamePaths(temp).ensure()
        val installer = VersionInstaller(paths, mirrors = MirrorCatalog.presets)
        val manifest = installer.fetchManifest()
        val target = manifest.versions.firstOrNull { it.id == "1.20.1" }
            ?: manifest.versions.first { it.type == "release" }
        val json = installer.fetchVersionJson(target)

        val dir = paths.versionDir(target.id)
        dir.mkdirs()
        paths.versionJson(target.id).writeText(JsonPretty.encodeToString(VersionJson.serializer(), json))

        val resolver = VersionResolver(paths)
        val merged = resolver.loadMerged(target.id)
        val account = Account(
            id = AccountUuid.fromOfflineName("Planner"),
            type = AccountType.Offline,
            displayName = "Planner",
            uuid = AccountUuid.fromOfflineName("Planner"),
            accessToken = "0"
        )
        val gameDir = File(temp, "instance").also { it.mkdirs() }
        val config = LaunchConfig(
            versionId = target.id,
            versionJson = merged,
            gameDir = gameDir,
            assetsDir = paths.assets,
            librariesDir = paths.libraries,
            nativesDir = paths.nativeDir(target.id),
            classpath = listOf(File(temp, "libs/fake.jar")),
            account = account,
            maxMemoryMb = 1024,
            minMemoryMb = 512,
            javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        )

        val command = LaunchPlanner.plan(config)
        assertTrue(command.jvmArgs.any { it == "-Xmx1024M" })
        assertTrue(command.jvmArgs.any { it.startsWith("-Djava.library.path=") })
        assertTrue(command.mainClass == merged.mainClass && command.mainClass.isNotEmpty())
        assertTrue(command.gameArgs.contains("Planner"))
        assertTrue(command.gameArgs.none { it.contains("\${") })
        assertTrue(command.jvmArgs.none { it.contains("\${") })
        assertTrue(command.classpathEntries().any { it.endsWith("fake.jar") })
        temp.deleteRecursively()
    }
}