package com.mcbox.core

import java.io.File

class GamePaths(val root: File) {
    val versions: File get() = File(root, "versions")
    val libraries: File get() = File(root, "libraries")
    val assets: File get() = File(root, "assets")
    val indexes: File get() = File(assets, "indexes")
    val objects: File get() = File(assets, "objects")
    val instances: File get() = File(root, "instances")
    val runtimes: File get() = File(root, "runtimes")
    val natives: File get() = File(root, "natives")
    val logs: File get() = File(root, "logs")
    val temp: File get() = File(root, "temp")
    val lwjgl: File get() = File(root, "lwjgl3")
    val cacio: File get() = File(root, "caciocavallo17")

    fun versionDir(id: String): File = File(versions, id)
    fun versionJson(id: String): File = File(versionDir(id), "$id.json")
    fun versionJar(id: String): File = File(versionDir(id), "$id.jar")
    fun indexFile(id: String): File = File(indexes, "$id.json")
    fun nativeDir(id: String): File = File(natives, id)
    fun runtimeDir(name: String): File = File(runtimes, name)
    fun logFile(name: String): File = File(logs, name)

    fun objectPath(hash: String): File = File(objects, hash.substring(0, 2) + "/" + hash)

    fun libraryPath(relative: String): File = File(libraries, relative)

    fun ensure(): GamePaths {
        listOf(root, versions, libraries, assets, indexes, objects, instances, runtimes, natives, logs, temp)
            .forEach { if (!it.exists()) it.mkdirs() }
        return this
    }
}