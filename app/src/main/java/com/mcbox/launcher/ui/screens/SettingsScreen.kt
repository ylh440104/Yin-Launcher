package com.mcbox.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    var source by remember { mutableStateOf(settings.downloadSource) }
    var runtimes by remember { mutableStateOf<List<String>>(emptyList()) }
    var installing by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }

    val paths = remember { GamePaths(File(settings.gameRoot)).ensure() }
    val runtimeManager = remember { RuntimeManager(context, paths.runtimes) }

    fun refreshRuntimes() {
        runtimes = runtimeManager.listInstalled().map { "Java ${it.javaMajor}" }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refreshRuntimes() }

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
            SettingCard("内存") {
                Text("${memory.toInt()} MB", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
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
                    listOf("opengles3" to "GL4ES", "opengles3_ltw" to "LTW", "virgl" to "VirGL").forEach { (key, label) ->
                        FilterChip(
                            selected = renderer == key,
                            onClick = {
                                renderer = key
                                settings.renderer = key
                                settings.rendererLibrary = when (key) {
                                    "opengles3_ltw" -> "libltw.so"
                                    "virgl" -> "libltw.so"
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
            SettingCard("下载源") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto" to "自动", "Official" to "官方", "Mirror" to "镜像").forEach { (key, label) ->
                        FilterChip(
                            selected = source == key,
                            onClick = {
                                source = key
                                settings.downloadSource = key
                            },
                            label = { Text(label) }
                        )
                    }
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
                }
                if (installLog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(installLog, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            SettingCard("游戏目录") {
                Text(
                    settings.gameRoot,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}