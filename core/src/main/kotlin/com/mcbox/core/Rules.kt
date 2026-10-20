package com.mcbox.core

import com.mcbox.core.model.Rule

class PlatformInfo(
    val osName: String = "android",
    val osVersion: String = "",
    val arch: String = "aarch64",
    val features: Map<String, Boolean> = emptyMap()
) {
    companion object {
        fun android(features: Map<String, Boolean> = emptyMap()): PlatformInfo {
            val abi = System.getProperty("os.arch") ?: "aarch64"
            return PlatformInfo(
                osName = "android",
                osVersion = System.getProperty("os.version") ?: "",
                arch = abi,
                features = features
            )
        }
    }
}

object RuleEvaluator {

    fun isAllowed(rules: List<Rule>, platform: PlatformInfo): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (!matches(rule, platform)) continue
            allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun matches(rule: Rule, platform: PlatformInfo): Boolean {
        val os = rule.os
        if (os != null) {
            if (os.name != null && !osNameMatches(os.name, platform.osName)) return false
            if (os.arch != null && !archMatches(os.arch, platform.arch)) return false
            if (os.version != null && os.version.isNotEmpty() && !Regex(os.version).containsMatchIn(platform.osVersion)) return false
        }
        for ((key, expected) in rule.features) {
            val actual = platform.features[key] ?: false
            if (actual != expected) return false
        }
        return true
    }

    private fun osNameMatches(ruleName: String, actual: String): Boolean {
        if (ruleName.equals(actual, ignoreCase = true)) return true
        if (ruleName == "linux" && actual == "android") return true
        if (ruleName == "osx" && (actual == "macos" || actual == "osx")) return true
        return false
    }

    private fun archMatches(ruleArch: String, actual: String): Boolean {
        if (ruleArch == actual) return true
        val ruleX86 = ruleArch == "x86" || ruleArch == "i386"
        val actualX86 = actual.startsWith("x86") || actual == "i386" || actual == "i686"
        if (ruleX86 && actualX86) return true
        val ruleArm = ruleArch == "arm" || ruleArch == "arm32"
        val actualArm = actual.startsWith("arm") && !actual.startsWith("aarch64")
        if (ruleArm && actualArm) return true
        val actualArm64 = actual == "aarch64" || actual == "arm64"
        if ((ruleArch == "aarch64" || ruleArch == "arm64") && actualArm64) return true
        return false
    }
}