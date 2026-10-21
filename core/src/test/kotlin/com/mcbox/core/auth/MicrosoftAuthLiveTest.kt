package com.mcbox.core.auth

import com.mcbox.core.net.Endpoints
import com.mcbox.core.net.Http
import kotlin.test.Test
import kotlin.test.assertTrue

class MicrosoftAuthLiveTest {

    @Test
    fun builtinClientIdIsAcceptedByDeviceCodeEndpoint() {
        val auth = MicrosoftAuth()
        val info = try {
            auth.requestDeviceCode()
        } catch (t: Throwable) {
            throw AssertionError("device code request rejected: ${t.message}", t)
        }
        assertTrue(info.userCode.isNotEmpty(), "user code empty")
        assertTrue(info.deviceCode.isNotEmpty(), "device code empty")
        assertTrue(info.verificationUri.startsWith("https://"), "bad verification uri ${info.verificationUri}")
        assertTrue(info.expiresIn > 0, "bad expiry ${info.expiresIn}")
        assertTrue(info.interval > 0, "bad interval ${info.interval}")
    }

    @Test
    fun invalidClientIdIsReportedWithFriendlyText() {
        val auth = MicrosoftAuth(clientId = "00000000-0000-0000-0000-000000000000")
        val failure = runCatching { auth.requestDeviceCode() }.exceptionOrNull()
        assertTrue(failure != null, "invalid client id unexpectedly accepted")
        val text = failure!!.message.orEmpty()
        assertTrue(text.isNotEmpty(), "empty error message")
        assertTrue(
            text.contains("无效") || text.contains("不可用") || text.contains("700016") ||
                text.contains("7000215") || text.contains("7000222") || text.contains("公共客户端"),
            "error not mapped to friendly text: $text"
        )
    }

    @Test
    fun refreshWithBadTokenReportsError() {
        val auth = MicrosoftAuth()
        val failure = runCatching { auth.refresh("invalid-refresh-token") }.exceptionOrNull()
        assertTrue(failure != null, "invalid refresh token unexpectedly accepted")
        val text = failure!!.message.orEmpty()
        assertTrue(text.isNotEmpty(), "empty refresh error")
    }

    @Test
    fun minecraftApiDistinguishesBadTokenFromUnregisteredApp() {
        val body = """{"identityToken":"XBL3.0 x=dummy;dummy"}"""
        val failure = runCatching {
            Http().postJson(
                Endpoints.MC_AUTH,
                body,
                mapOf("Accept" to "application/json")
            )
        }.exceptionOrNull()
        assertTrue(failure != null, "invalid xbox token unexpectedly accepted")
        val text = failure!!.message.orEmpty()
        assertTrue(
            text.contains("401") || text.contains("UNAUTHORIZED"),
            "expected 401 for bad token, got: $text"
        )
        assertTrue(
            !text.contains("Invalid app registration"),
            "bad token must not be reported as app registration issue: $text"
        )
    }
}