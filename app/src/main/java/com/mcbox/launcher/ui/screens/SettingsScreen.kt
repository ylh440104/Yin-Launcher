package com.mcbox.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcbox.core.GamePaths
import com.mcbox.core.auth.MicrosoftAuth
import com.mcbox.core.install.MirrorCatalog
import com.mcbox.launcher.data.GameDirs
import com.mcbox.launcher.data.SettingsStore
import com.mcbox.launcher.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    var memory by remember { mutableStateOf(settings.memoryMb.toFloat()) }
    var renderer by remember { mutableStateOf(settings.renderer) }
    var zink by remember { mutableStateOf(settings.zink) }
    var vsync by remember { mutableStateOf(settings.vsync) }
    var bigCore by remember { mutableStateOf(settings.bigCore) }
    var mirrorId by remember { mutableStateOf(settings.downloadSource) }
    var customMirror by remember { mutableStateOf(settings.customMirrorBase) }
    var msClientId by remember { mutableStateOf(settings.msClientId) }
    var msProbe by remember { mutableStateOf("") }
    var nativeReport by remember { mutableStateOf("") }
    var gameRoot by remember { mutableStateOf(settings.gameRoot) }
    var runtimes by remember { mutableStateOf<List<String>>(emptyList()) }
    var installing by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }
    var showDirDialog by remember { mutableStateOf(false) }
    var dirInput by remember { mutableStateOf(settings.gameRoot) }

    val paths = remember(gameRoot) { settings.paths() }
    val runtimeManager = remember(context, gameRoot) { RuntimeManager(context, paths.runtimes) }

    fun refreshRuntimes() {
        runtimes = runtimeManager.listInstalled().map { "Java ${it.javaMajor}" }
    }

    LaunchedEffect(gameRoot) { refreshRuntimes() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "设置",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            SettingCard("游戏目录") {
                Text(
                    gameRoot,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dirInput = gameRoot; showDirDialog = true }) {
                        Text("更改目录")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (GameDirs.isUsable(File(gameRoot))) "可写" else "不可写，请换目录",
                    fontSize = 11.sp,
                    color = if (GameDirs.isUsable(File(gameRoot)))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            SettingCard("下载镜像") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MirrorCatalog.all(customMirror).forEach { mirror ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mirrorId = mirror.id
                                    settings.downloadSource = mirror.id
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                mirror.name,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 6.dp)
                            )
                            if (mirrorId == mirror.id) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (mirrorId == MirrorCatalog.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customMirror,
                        onValueChange = {
                            customMirror = it
                            settings.customMirrorBase = it
                        },
                        label = { Text("镜像根地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            SettingCard("微软登录") {
                OutlinedTextField(
                    value = msClientId,
                    onValueChange = {
                        msClientId = it
                        settings.msClientId = it.trim()
                    },
                    label = { Text("client id") },
                    placeholder = { Text("留空用内置 b2468fd2-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "内置 id 若被微软拒绝 700016 可换自己的 Azure 应用 id",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                msProbe = "检测中"
                                msProbe = try {
                                    withContext(Dispatchers.IO) {
                                        val id = settings.msClientId.trim()
                                        val auth = if (id.isEmpty()) MicrosoftAuth() else MicrosoftAuth(clientId = id)
                                        auth.probe()
                                    }
                                } catch (t: Throwable) {
                                    "失败 ${t.message}"
                                }
                            }
                        }
                    ) { Text("检测 id") }
                }
                if (msProbe.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        msProbe,
                        fontSize = 11.sp,
                        color = if (msProbe.startsWith("可用")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            SettingCard("内存") {
                Text(
                    "${memory.toInt()} MB",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = memory,
                    onValueChange = { memory = it },
                    onValueChangeFinished = { settings.memoryMb = memory.toInt() },
                    valueRange = 512f..8192f,
                    steps = 30
                )
            }
        }

        item {
            SettingCard("渲染器") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("opengles3" to "GL4ES", "opengles3_ltw" to "LTW").forEach { (key, label) ->
                        FilterChip(
                            selected = renderer == key,
                            onClick = {
                                renderer = key
                                settings.renderer = key
                                settings.rendererLibrary = when (key) {
                                    "opengles3_ltw" -> "libltw.so"
                                    else -> "libgl4es_114.so"
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Zink (Vulkan)", zink) {
                    zink = it
                    settings.zink = it
                }
                ToggleRow("垂直同步", vsync) {
                    vsync = it
                    settings.vsync = it
                }
                ToggleRow("大核优先", bigCore) {
                    bigCore = it
                    settings.bigCore = it
                }
            }
        }

        item {
            SettingCard("Java 运行时") {
                Text(
                    if (runtimes.isEmpty()) "未安装" else runtimes.joinToString(", "),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                installing = true
                                installLog = "解压 Java 21"
                                try {
                                    withContext(Dispatchers.IO) {
                                        runtimeManager.installFromAssets(
                                            name = "jre-21",
                                            universalAsset = "runtime/jre21/universal.tar.xz",
                                            binAsset = "runtime/jre21/bin-arm64.tar.xz"
                                        ) { stage, pct ->
                                            installLog = "$stage $pct%"
                                        }
                                    }
                                    installLog = "安装完成"
                                    refreshRuntimes()
                                } catch (t: Throwable) {
                                    installLog = "失败: ${t.message}"
                                }
                                installing = false
                            }
                        },
                        enabled = !installing
                    ) { Text("安装 Java 21") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                installing = true
                                installLog = "解压 Java 8"
                                try {
                                    withContext(Dispatchers.IO) {
                                        runtimeManager.installFromAssets(
                                            name = "jre-8",
                                            universalAsset = "runtime/jre8/universal.tar.xz",
                                            binAsset = "runtime/jre8/bin-arm64.tar.xz"
                                        ) { stage, pct ->
                                            installLog = "$stage $pct%"
                                        }
                                    }
                                    installLog = "安装完成"
                                    refreshRuntimes()
                                } catch (t: Throwable) {
                                    installLog = "失败: ${t.message}"
                                }
                                installing = false
                            }
                        },
                        enabled = !installing
                    ) { Text("安装 Java 8") }
                }
                if (installLog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        installLog,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SettingCard("原生库自检") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            nativeReport = "检测中"
                            nativeReport = withContext(Dispatchers.IO) {
                                val result = com.mcbox.launcher.runtime.NativeSelfCheck.run(
                                    context,
                                    context.applicationInfo.nativeLibraryDir
                                )
                                buildString {
                                    append("库 ")
                                    append(if (result.libsLoaded) "OK" else "FAIL")
                                    append(" 输入 ")
                                    append(if (result.inputBridgeOk) "OK" else "FAIL")
                                    append(" 回调 ")
                                    append(if (result.callbackBridgeOk) "OK" else "FAIL")
                                    append(" JRE ")
                                    append(if (result.jreUtilsOk) "OK" else "FAIL")
                                    append(" 日志 ")
                                    append(if (result.loggerOk) "OK" else "FAIL")
                                    append('\n')
                                    append(result.message)
                                }
                            }
                        }
                    }) { Text("运行自检") }
                    var jvmReport by remember { mutableStateOf("") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            jvmReport = "JVM 启动测试中..."
                            jvmReport = withContext(Dispatchers.IO) {
                                try {
                                    Class.forName("net.kdt.pojavlaunch.utils.JREUtils").getMethod("ensureLoaded").invoke(null)
                                    val rm = com.mcbox.launcher.runtime.RuntimeManager(context, java.io.File(context.filesDir, "mcbox/runtimes"))
                                    val rt = rm.findForMajor(21) ?: rm.findForMajor(17)
                                        ?: return@withContext "未找到 JRE，请先安装"
                                    val zc = Class.forName("net.kdt.pojavlaunch.utils.ZalithUtils")
                                    fun setF(name: String, value: Any?) {
                                        val f = zc.getDeclaredField(name)
                                        f.isAccessible = true
                                        f.set(null, value)
                                    }
                                    setF("gameHome", context.filesDir.absolutePath + "/mcbox")
                                    setF("cacheDir", context.cacheDir)
                                    setF("dataDir", java.io.File(context.filesDir.parent))
                                    val m = zc.getMethod("launchWithUtils", android.content.Context::class.java, String::class.java, String::class.java, String::class.java, List::class.java, String::class.java, Runnable::class.java)
                                    val th = Thread {
                                        try { m.invoke(null, context, "arm64-v8a", rt.home.absolutePath, context.filesDir.absolutePath + "/mcbox", listOf("-version"), "", null) } catch (t: Throwable) { android.util.Log.w("JvmTest", "probe failed", t) }
n                                    }
                                    th.name = "jvm-probe"
                                    th.start()
                                    "JVM -version 测试已启动，10 秒后看 files/mcbox/jvm-console.txt"
                                } catch (t: Throwable) {
                                    "测试失败: " + t.stackTraceToString()
                                }
                            }
                        }
                    }) { Text("JVM 测试") }
                }
                if (nativeReport.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        nativeReport,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SettingCard("关于") {
                Text("MCBox 1.0.0", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "安卓版 Minecraft Java 启动器",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDirDialog) {
        DirectoryPickerDialog(
            initial = gameRoot,
            onDismiss = { showDirDialog = false },
            onPick = { picked ->
                if (GameDirs.isUsable(picked)) {
                    settings.gameRoot = picked.absolutePath
                    gameRoot = picked.absolutePath
                    dirInput = picked.absolutePath
                    showDirDialog = false
                } else {
                    installLog = "目录不可写: ${picked.absolutePath}"
                }
            }
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}