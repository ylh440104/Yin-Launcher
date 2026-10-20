package com.mcbox.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val type: AccountType,
    val displayName: String,
    val uuid: String,
    @SerialName("accessToken") val accessToken: String = "",
    @SerialName("refreshToken") val refreshToken: String = "",
    @SerialName("expiresAt") val expiresAt: Long = 0L,
    @SerialName("authServer") val authServer: String = "",
    @SerialName("skinModel") val skinModel: String = "default"
) {
    val isExpired: Boolean
        get() = type == AccountType.Microsoft && expiresAt > 0 && System.currentTimeMillis() >= expiresAt - 60_000L

    fun offlineUuid(name: String): String = AccountUuid.fromOfflineName(name)
}

@Serializable
enum class AccountType {
    Offline,
    Microsoft,
    Authlib
}

object AccountUuid {
    fun fromOfflineName(name: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x30).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val sb = StringBuilder()
        for (i in digest.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }

    fun undashed(uuid: String): String = uuid.replace("-", "")
}