package com.mcbox.core.auth

import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.net.Endpoints
import com.mcbox.core.net.Http
import com.mcbox.core.util.JsonCompact
import com.mcbox.core.util.int
import com.mcbox.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

data class DeviceCodeInfo(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,
    val message: String
)

class MicrosoftAuthException(message: String, val xerr: String? = null) : IOException(message)

class MicrosoftAuth(
    private val http: Http = Http(),
    private val clientId: String = Endpoints.MOJANG_CLIENT_ID
) {

    fun requestDeviceCode(): DeviceCodeInfo {
        val endpoints = listOf(Endpoints.MS_DEVICE_CODE, Endpoints.MS_DEVICE_CODE_COMMON)
        var lastError: Throwable? = null
        for (endpoint in endpoints) {
            val resp = try {
                http.postForm(endpoint, mapOf("client_id" to clientId, "scope" to Endpoints.MS_SCOPE))
            } catch (e: IOException) {
                lastError = MicrosoftAuthException(friendlyError(e.message.orEmpty()))
                continue
            }
            val obj = runCatching { JsonCompact.parseToJsonElement(resp).jsonObject }.getOrNull()
                ?: continue
            val userCode = obj.str("user_code")
            val deviceCode = obj.str("device_code")
            if (userCode == null || deviceCode == null) {
                lastError = MicrosoftAuthException(friendlyError(resp))
                continue
            }
            val uri = obj.str("verification_uri") ?: obj.str("verification_url") ?: "https://microsoft.com/link"
            tokenEndpoint = if (endpoint == Endpoints.MS_DEVICE_CODE) Endpoints.MS_TOKEN else Endpoints.MS_TOKEN_COMMON
            return DeviceCodeInfo(
                userCode = userCode,
                deviceCode = deviceCode,
                verificationUri = uri,
                expiresIn = obj.int("expires_in", 900),
                interval = obj.int("interval", 5),
                message = obj.str("message").orEmpty()
            )
        }
        throw lastError as? MicrosoftAuthException
            ?: MicrosoftAuthException(lastError?.message ?: "微软登录失败")
    }

    @Volatile
    private var tokenEndpoint: String = Endpoints.MS_TOKEN

    private fun friendlyError(raw: String): String = when {
        raw.contains("unauthorized_client") || raw.contains("700016") ->
            "微软登录不可用：应用未获授权（AADSTS700016）。到设置里换一个 client id"
        raw.contains("invalid_client") || raw.contains("7000215") ->
            "微软登录不可用：client id 无效"
        raw.contains("65004") || raw.contains("consent_required") ->
            "微软登录需要在网页上同意授权后再试一次"
        raw.contains("7000222") || raw.contains("7000218") ->
            "该 client id 未开启公共客户端流，去 Azure 应用身份验证里打开「允许公共客户端流」"
        raw.contains("HTTP 400") -> "微软登录请求被拒绝：$raw"
        raw.contains("UnknownHost") || raw.contains("timeout") || raw.contains("timed out") ->
            "网络不可用，检查网络后重试"
        else -> raw.ifBlank { "微软登录失败" }
    }

    fun probe(): String {
        val info = requestDeviceCode()
        return "可用 代码 ${info.userCode} 到期 ${info.expiresIn}s"
    }

    fun pollForToken(info: DeviceCodeInfo, onWait: (Int) -> Unit = {}): TokenResponse {
        var waited = 0
        var interval = maxOf(1, info.interval)
        val deadline = System.currentTimeMillis() + info.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L)
            waited += interval
            onWait(waited)
            val resp = try {
                http.postForm(
                    tokenEndpoint,
                    mapOf(
                        "client_id" to clientId,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code" to info.deviceCode
                    )
                )
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                if (msg.contains("authorization_pending")) continue
                if (msg.contains("slow_down")) {
                    interval = minOf(interval + 5, 60)
                    continue
                }
                if (msg.contains("authorization_declined")) throw MicrosoftAuthException("用户拒绝了授权")
                if (msg.contains("expired_token")) throw MicrosoftAuthException("设备码已过期")
                throw MicrosoftAuthException(msg)
            }
            return parseToken(resp)
        }
        throw MicrosoftAuthException("设备码登录超时")
    }

    fun refresh(refreshToken: String): TokenResponse {
        val resp = http.postForm(
            tokenEndpoint,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "scope" to Endpoints.MS_SCOPE
            )
        )
        return parseToken(resp)
    }

    private fun parseToken(resp: String): TokenResponse {
        val obj = JsonCompact.parseToJsonElement(resp).jsonObject
        val access = obj.str("access_token") ?: throw MicrosoftAuthException("no access_token")
        val refresh = obj.str("refresh_token").orEmpty()
        val expiresIn = obj.int("expires_in", 3600)
        return TokenResponse(access, refresh, System.currentTimeMillis() + expiresIn * 1000L)
    }

    data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    fun authenticateXboxLive(msAccessToken: String): XblResult {
        val body = """
            {
              "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": "d=$msAccessToken"},
              "RelyingParty": "http://auth.xboxlive.com",
              "TokenType": "JWT"
            }
        """.trimIndent()
        val resp = http.postJson(
            Endpoints.XBL_AUTH,
            body,
            mapOf("Accept" to "application/json", "x-xbl-contract-version" to "1")
        )
        val obj = JsonCompact.parseToJsonElement(resp).jsonObject
        val token = obj.str("Token") ?: throw MicrosoftAuthException("no XBL Token")
        val uhs = obj["DisplayClaims"]?.jsonObject?.get("xui")?.let {
            (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject?.str("uhs")
        } ?: throw MicrosoftAuthException("no XBL uhs")
        return XblResult(token, uhs)
    }

    fun authorizeXsts(xblToken: String): XstsResult {
        val body = """
            {
              "Properties": {"SandboxId": "RETAIL", "UserTokens": ["$xblToken"]},
              "RelyingParty": "rp://api.minecraftservices.com/",
              "TokenType": "JWT"
            }
        """.trimIndent()
        val resp = try {
            http.postJson(
                Endpoints.XSTS_AUTH,
                body,
                mapOf("Accept" to "application/json", "x-xbl-contract-version" to "1")
            )
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            val xerr = when {
                msg.contains("2148916233") -> "该微软账户没有 Xbox 档案，请先在 Xbox 官网创建"
                msg.contains("2148916235") -> "该账户所在地区不支持 Xbox Live"
                msg.contains("2148916238") -> "未成年账户需要家长同意"
                else -> null
            }
            throw MicrosoftAuthException(xerr ?: msg, xerr)
        }
        val obj = JsonCompact.parseToJsonElement(resp).jsonObject
        val token = obj.str("Token") ?: throw MicrosoftAuthException("no XSTS Token")
        val uhs = obj["DisplayClaims"]?.jsonObject?.get("xui")?.let {
            (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject?.str("uhs")
        } ?: throw MicrosoftAuthException("no XSTS uhs")
        return XstsResult(token, uhs)
    }

    fun loginWithXbox(uhs: String, xstsToken: String): MinecraftToken {
        val body = """{"identityToken":"XBL3.0 x=$uhs;$xstsToken"}"""
        val resp = try {
            http.postJson(Endpoints.MC_AUTH, body, mapOf("Accept" to "application/json"))
        } catch (e: IOException) {
            val raw = e.message.orEmpty()
            throw MicrosoftAuthException(mapMinecraftAuthError(raw))
        }
        val obj = JsonCompact.parseToJsonElement(resp).jsonObject
        val access = obj.str("access_token") ?: throw MicrosoftAuthException("Minecraft 未返回 token")
        val expiresIn = obj.int("expires_in", 86400)
        return MinecraftToken(access, System.currentTimeMillis() + expiresIn * 1000L)
    }

    private fun mapMinecraftAuthError(raw: String): String = when {
        raw.contains("Invalid app registration") ->
            "Mojang 拒绝了这个 client id：应用未在 Minecraft 侧登记，需要单独申请白名单。这不是填错 id，设备码阶段能通过也是正常的。可先到设置换用内置 id 试试"
        raw.contains("HTTP 403") ->
            "Minecraft 接口返回 403，通常是 client id 未获 Mojang 授权"
        raw.contains("HTTP 401") ->
            "Xbox 令牌无效，请重新登录一次"
        raw.contains("HTTP 429") ->
            "请求太频繁，等一会儿再试"
        raw.contains("ineligible") || raw.contains("does not own") ->
            "该账号没有购买 Minecraft Java 版"
        raw.contains("UnknownHost") || raw.contains("timeout") || raw.contains("timed out") ->
            "网络不可用，检查网络后重试"
        else -> raw.ifBlank { "Minecraft 登录失败" }
    }

    fun fetchProfile(mcAccessToken: String): McProfile {
        val resp = http.getText(Endpoints.MC_PROFILE, mapOf("Authorization" to "Bearer $mcAccessToken"))
        val obj = JsonCompact.parseToJsonElement(resp).jsonObject
        val id = obj.str("id") ?: throw MicrosoftAuthException("no profile id，该账户可能未购买 Minecraft")
        val name = obj.str("name") ?: throw MicrosoftAuthException("no profile name")
        val skins = obj["skins"] as? kotlinx.serialization.json.JsonArray
        val model = skins?.firstOrNull()?.jsonObject?.str("variant") ?: "CLASSIC"
        return McProfile(id, name, model)
    }
    fun checkEntitlement(mcAccessToken: String): Boolean {
        val resp = try {
            http.getText(
                "https://api.minecraftservices.com/entitlements/mcstore",
                mapOf("Authorization" to "Bearer $mcAccessToken")
            )
        } catch (e: IOException) {
            return false
        }
        val obj = JsonCompact.parseToJsonElement(resp).jsonObject
        val itemsArray = obj["items"] as? kotlinx.serialization.json.JsonArray
        return itemsArray != null && itemsArray.isNotEmpty()
    }

    fun fullLogin(
        onDeviceCode: (DeviceCodeInfo) -> Unit,
        onStatus: (String) -> Unit = {}
    ): Account {
        onStatus("requesting device code")
        val info = requestDeviceCode()
        onDeviceCode(info)
        onStatus("waiting for authorization")
        val token = pollForToken(info)
        onStatus("authenticating with Xbox Live")
        val xbl = authenticateXboxLive(token.accessToken)
        onStatus("authorizing XSTS")
        val xsts = authorizeXsts(xbl.token)
        onStatus("logging into Minecraft")
        val mc = loginWithXbox(xsts.uhs, xsts.token)
        onStatus("fetching profile")
        val profile = fetchProfile(mc.accessToken)
        return Account(
            id = profile.id,
            type = AccountType.Microsoft,
            displayName = profile.name,
            uuid = dashUuid(profile.id),
            accessToken = mc.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = mc.expiresAt,
            authServer = "",
            skinModel = profile.skinModel.lowercase()
        )
    }

    fun refreshAccount(account: Account): Account {
        if (account.type != AccountType.Microsoft) return account
        if (account.refreshToken.isEmpty()) return account
        val token = refresh(account.refreshToken)
        val xbl = authenticateXboxLive(token.accessToken)
        val xsts = authorizeXsts(xbl.token)
        val mc = loginWithXbox(xsts.uhs, xsts.token)
        val profile = fetchProfile(mc.accessToken)
        return account.copy(
            id = profile.id,
            displayName = profile.name,
            uuid = dashUuid(profile.id),
            accessToken = mc.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = mc.expiresAt,
            skinModel = profile.skinModel.lowercase()
        )
    }

    companion object {
        fun dashUuid(raw: String): String {
            val clean = raw.replace("-", "")
            if (clean.length != 32) return raw
            return buildString {
                append(clean, 0, 8).append('-')
                append(clean, 8, 12).append('-')
                append(clean, 12, 16).append('-')
                append(clean, 16, 20).append('-')
                append(clean, 20, 32)
            }
        }
    }
}

data class XblResult(val token: String, val uhs: String)
data class XstsResult(val token: String, val uhs: String)
data class MinecraftToken(val accessToken: String, val expiresAt: Long)
data class McProfile(val id: String, val name: String, val skinModel: String)