package com.mcbox.launcher.data

import android.content.Context
import com.mcbox.core.model.Account
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class Instance(
    val id: String,
    val name: String,
    val versionId: String,
    val loader: String = "vanilla",
    val loaderVersion: String = "",
    val javaMajor: Int = 17,
    val accountId: String? = null,
    val memoryMb: Int = 0,
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val server: String? = null,
    @SerialName("createdAt") val createdAt: Long = System.currentTimeMillis()
)

class InstanceStore(context: Context) {

    private val file = File(context.filesDir, "instances.json")

    fun all(): List<Instance> {
        if (!file.exists()) return emptyList()
        return try {
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }.decodeFromString(ListSerializer(Instance.serializer()), file.readText())
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun save(list: List<Instance>) {
        file.parentFile?.mkdirs()
        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }
        file.writeText(json.encodeToString(ListSerializer(Instance.serializer()), list))
    }

    fun find(id: String): Instance? = all().firstOrNull { it.id == id }

    fun upsert(instance: Instance) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == instance.id }
        if (index >= 0) list[index] = instance else list.add(instance)
        save(list)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("mcbox_settings", Context.MODE_PRIVATE)
    private val accountFile = File(context.filesDir, "accounts.json")

    var gameRoot: String
        get() = prefs.getString("game_root", File(context.getExternalFilesDir(null), "mcbox").absolutePath)
            ?: File(context.getExternalFilesDir(null), "mcbox").absolutePath
        set(value) {
            prefs.edit().putString("game_root", value).apply()
        }

    var memoryMb: Int
        get() = prefs.getInt("memory_mb", defaultMemory())
        set(value) {
            prefs.edit().putInt("memory_mb", value).apply()
        }

    var renderer: String
        get() = prefs.getString("renderer", "opengles3") ?: "opengles3"
        set(value) {
            prefs.edit().putString("renderer", value).apply()
        }

    var rendererLibrary: String
        get() = prefs.getString("renderer_lib", "libgl4es_114.so") ?: "libgl4es_114.so"
        set(value) {
            prefs.edit().putString("renderer_lib", value).apply()
        }

    var glesVersion: String
        get() = prefs.getString("gles", "3") ?: "3"
        set(value) {
            prefs.edit().putString("gles", value).apply()
        }

    var zink: Boolean
        get() = prefs.getBoolean("zink", false)
        set(value) {
            prefs.edit().putBoolean("zink", value).apply()
        }

    var vsync: Boolean
        get() = prefs.getBoolean("vsync", false)
        set(value) {
            prefs.edit().putBoolean("vsync", value).apply()
        }

    var bigCore: Boolean
        get() = prefs.getBoolean("big_core", false)
        set(value) {
            prefs.edit().putBoolean("big_core", value).apply()
        }

    var playerName: String
        get() = prefs.getString("player_name", "Player") ?: "Player"
        set(value) {
            prefs.edit().putString("player_name", value).apply()
        }

    var downloadSource: String
        get() = prefs.getString("download_source", "Auto") ?: "Auto"
        set(value) {
            prefs.edit().putString("download_source", value).apply()
        }

    var javaMajorDefault: Int
        get() = prefs.getInt("java_major_default", 17)
        set(value) {
            prefs.edit().putInt("java_major_default", value).apply()
        }

    var activeAccountId: String
        get() = prefs.getString("active_account", "") ?: ""
        set(value) {
            prefs.edit().putString("active_account", value).apply()
        }

    fun defaultMemory(): Int {
        val max = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        return when {
            max >= 4096 -> 2048
            max >= 2048 -> 1536
            else -> 1024
        }
    }

    fun accounts(): List<Account> {
        if (!accountFile.exists()) return emptyList()
        return try {
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }.decodeFromString(ListSerializer(Account.serializer()), accountFile.readText())
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun saveAccounts(list: List<Account>) {
        accountFile.parentFile?.mkdirs()
        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }
        accountFile.writeText(json.encodeToString(ListSerializer(Account.serializer()), list))
    }

    fun addAccount(account: Account) {
        val list = accounts().toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        if (index >= 0) list[index] = account else list.add(account)
        saveAccounts(list)
        activeAccountId = account.id
    }

    fun removeAccount(id: String) {
        saveAccounts(accounts().filterNot { it.id == id })
        if (activeAccountId == id) activeAccountId = ""
    }

    fun activeAccount(): Account? {
        val id = activeAccountId
        return accounts().firstOrNull { it.id == id } ?: accounts().firstOrNull()
    }
}