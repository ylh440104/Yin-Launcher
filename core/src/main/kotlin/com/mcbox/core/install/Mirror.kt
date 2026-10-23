package com.mcbox.core.install

data class Mirror(
    val id: String,
    val name: String,
    val manifestUrl: String,
    val assetsBase: String,
    val librariesBase: String,
    val metaBase: String,
    val description: String = ""
) {

    val isOfficial: Boolean get() = id == "official"

    fun rewrite(url: String): String {
        if (isOfficial || metaBase.isEmpty()) return url
        return when {
            url.startsWith("https://piston-meta.mojang.com") ->
                url.replace("https://piston-meta.mojang.com", metaBase)
            url.startsWith("https://launchermeta.mojang.com") ->
                url.replace("https://launchermeta.mojang.com", metaBase)
            url.startsWith("https://launcher.mojang.com") ->
                url.replace("https://launcher.mojang.com", metaBase)
            url.startsWith("https://libraries.minecraft.net") ->
                if (librariesBase.isEmpty()) url
                else url.replace("https://libraries.minecraft.net", librariesBase)
            url.startsWith("https://resources.download.minecraft.net") ->
                if (assetsBase.isEmpty()) url
                else url.replace("https://resources.download.minecraft.net", assetsBase)
            else -> url
        }
    }
}

object MirrorCatalog {

    const val OFFICIAL = "official"
    const val BMCLAPI = "bmclapi"
    const val MCIM = "mcim"
    const val CUSTOM = "custom"

    val presets: List<Mirror> = listOf(
        Mirror(
            id = OFFICIAL,
            name = "官方",
            manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            assetsBase = "https://resources.download.minecraft.net",
            librariesBase = "https://libraries.minecraft.net",
            metaBase = "",
            description = "Mojang 原始源 海外速度快"
        ),
        Mirror(
            id = BMCLAPI,
            name = "BMCLAPI",
            manifestUrl = "https://mcapiajvk.infrlo.com/mc/game/version_manifest_v2.json",
            assetsBase = "https://mcapiajvk.infrlo.com/assets",
            librariesBase = "https://mcapiajvk.infrlo.com/maven",
            metaBase = "https://mcapiajvk.infrlo.com",
            description = "自建镜像"
        ),
        Mirror(
            id = MCIM,
            name = "MCIM",
            manifestUrl = "https://launcher.mcimirror.top/mc/game/version_manifest_v2.json",
            assetsBase = "https://launcher.mcimirror.top/assets",
            librariesBase = "https://launcher.mcimirror.top/maven",
            metaBase = "https://launcher.mcimirror.top",
            description = "社区镜像 备用线路"
        )
    )

    fun byId(id: String, customBase: String = ""): Mirror {
        if (id == CUSTOM) {
            val base = customBase.trim().trimEnd('/')
            if (base.isEmpty()) return presets.first()
            return Mirror(
                id = CUSTOM,
                name = "自定义",
                manifestUrl = base + "/mc/game/version_manifest_v2.json",
                assetsBase = base + "/assets",
                librariesBase = base + "/maven",
                metaBase = base,
                description = base
            )
        }
        return presets.firstOrNull { it.id == id } ?: presets.first()
    }

    fun all(customBase: String = ""): List<Mirror> {
        val list = presets.toMutableList()
        if (customBase.isNotBlank()) list.add(byId(CUSTOM, customBase)) else list.add(
            Mirror(
                id = CUSTOM,
                name = "自定义",
                manifestUrl = "",
                assetsBase = "",
                librariesBase = "",
                metaBase = "",
                description = "手动填写镜像地址"
            )
        )
        return list
    }
}