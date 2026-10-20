package com.mcbox.core.auth

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
    fun invalidClientIdIsReportedWithKnownCode() {
        val auth = MicrosoftAuth(clientId = "00000000-0000-0000-0000-000000000000")
        val failure = runCatching { auth.requestDeviceCode() }.exceptionOrNull()
        assertTrue(failure != null, "invalid client id unexpectedly accepted")
        val text = failure!!.message.orEmpty()
        assertTrue(text.isNotEmpty(), "empty error message")
        assertTrue(
            text.contains("无效") || text.contains("不可用") || text.contains("700016") || text.contains("7000215"),
            "error not mapped to friendly text: $text"
        )
    }

    @Test
    fun clientIdIsReusableForRefreshFlowShape() {
        val auth = MicrosoftAuth()
        val failure = runCatching { auth.refresh("invalid-refresh-token") }.exceptionOrNull()
        assertTrue(failure != null, "invalid refresh token unexpectedly accepted")
        val text = failure!!.message.orEmpty()
        assertTrue(text.isNotEmpty(), "empty refresh error")
        assertTrue(
            text.contains("HTTP 400") || text.contains("invalid") || text.contains("AADSTS"),
            "unexpected refresh error: $text"
        )
    }
}