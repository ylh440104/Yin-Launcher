package com.mcbox.core.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Endpoints {
    const val MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    const val MOJANG_RESOURCES = "https://resources.download.minecraft.net"
    const val MOJANG_LIBRARIES = "https://libraries.minecraft.net"

    const val BMCL_MANIFEST = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
    const val BMCL_RESOURCES = "https://bmclapi2.bangbang93.com/assets"
    const val BMCL_LIBRARIES = "https://bmclapi2.bangbang93.com/maven"
    const val BMCL_VERSION = "https://bmclapi2.bangbang93.com/version"

    const val MS_DEVICE_CODE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
    const val MS_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    const val MS_DEVICE_CODE_COMMON = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode"
    const val MS_TOKEN_COMMON = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    const val XBL_AUTH = "https://user.auth.xboxlive.com/user/authenticate"
    const val XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize"
    const val MC_AUTH = "https://api.minecraftservices.com/authentication/login_with_xbox"
    const val MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile"

    const val FABRIC_META = "https://meta.fabricmc.net/v2"
    const val QUILT_META = "https://meta.quiltmc.org/v3"
    const val FORGE_MAVEN = "https://maven.minecraftforge.net"
    const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"
    const val PAPERMAVEN_REPO = "https://repo.papermc.io/repository/maven-public"

    const val MOJANG_CLIENT_ID = "b2468fd2-7996-4f42-8857-5b65d834ef5c"
    const val MS_SCOPE = "XboxLive.signin offline_access"
}

class Http(private val client: OkHttpClient = defaultClient) {

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
        }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw IOException("empty body for $url")
        }
    }

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url: $text")
            return text
        }
    }

    fun postForm(url: String, form: Map<String, String>): String {
        val body = okhttp3.FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url: $text")
            return text
        }
    }

    fun openStream(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = client.newCall(builder.build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("HTTP ${resp.code} for $url")
        }
        return resp
    }

    fun download(url: String, target: File, sha1: String? = null): File {
        if (target.exists() && sha1 != null && target.length() > 0 && Hashes.sha1(target) == sha1) return target
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        openStream(url).use { resp ->
            val body = resp.body ?: throw IOException("empty body for $url")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (sha1 != null) {
            val actual = Hashes.sha1(tmp)
            if (!actual.equals(sha1, ignoreCase = true)) {
                tmp.delete()
                throw IOException("sha1 mismatch for $url expected $sha1 got $actual")
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }
}

object Hashes {
    fun sha1(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }
}