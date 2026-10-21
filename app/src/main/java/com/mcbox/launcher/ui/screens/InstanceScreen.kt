package com.mcbox.launcher.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.mcbox.launcher.data.Instance
import com.mcbox.launcher.data.InstanceStore
import com.mcbox.launcher.data.DetectedVersion
import com.mcbox.launcher.data.SettingsStore
import com.mcbox.launcher.data.VersionScanner
import com.mcbox.launcher.game.GameActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun InstanceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { InstanceStore(context) }
    val settings = remember { SettingsStore(context) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<Instance?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<List<DetectedVersion>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf("") }

    suspend fun reload() {
        instances = withContext(Dispatchers.IO) { store.all() }
        loading = false
    }

    suspend fun autoDetect() {
        scanning = true
        try {
            val found = withContext(Dispatchers.IO) {
                val paths = com.mcbox.core.GamePaths(File(settings.gameRoot)).ensure()
                VersionScanner.scan(paths)
            }
            detected = found
            val added = withContext(Dispatchers.IO) {
                VersionScanner.syncToInstances(store, found)
            }
            if (added > 0) {
                scanMessage = "新发现 $added 个版本"
                reload()
            } else if (found.isEmpty()) {
                scanMessage = "目录里没有已安装版本"
            } else {
                scanMessage = "已同步 ${found.size} 个版本"
            }
        } catch (t: Throwable) {
            scanMessage = "扫描失败: ${t.message}"
        }
        scanning = false
    }

    LaunchedEffect(settings.gameRoot) {
        reload()
        autoDetect()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("实例", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        when {
                            scanning -> "正在扫描目录"
                            scanMessage.isNotEmpty() -> scanMessage
                            else -> settings.activeAccount()?.displayName ?: "未登录"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { scope.launch { autoDetect() } }, enabled = !scanning) {
                    Text(if (scanning) "扫描中" else "重新扫描")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showCreate = true }) {
                    Text("新建")
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (instances.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有实例", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "去下载页装一个版本",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(instances, key = { it.id }) { instance ->
                        InstanceCard(
                            instance = instance,
                            onLaunch = {
                                val intent = Intent(context, GameActivity::class.java).apply {
                                    putExtra(GameActivity.EXTRA_INSTANCE, instance.id)
                                    putExtra(GameActivity.EXTRA_JAVA_MAJOR, instance.javaMajor)
                                    putExtra(
                                        GameActivity.EXTRA_MEMORY,
                                        if (instance.memoryMb > 0) instance.memoryMb else settings.memoryMb
                                    )
                                    putExtra(GameActivity.EXTRA_RENDERER, settings.renderer)
                                }
                                context.startActivity(intent)
                            },
                            onDelete = { deleteTarget = instance }
                        )
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除实例") },
            text = { Text("确定删除 ${target.name} ？存档和模组都会没") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.remove(target.id)
                        val paths = com.mcbox.core.GamePaths(File(settings.gameRoot)).ensure()
                        File(paths.instances, target.id).deleteRecursively()
                        deleteTarget = null
                        reload()
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    if (showCreate) {
        CreateInstanceDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, versionId, loader, loaderVersion ->
                scope.launch {
                    val javaMajor = withContext(Dispatchers.IO) {
                        val paths = com.mcbox.core.GamePaths(File(settings.gameRoot)).ensure()
                        val resolver = com.mcbox.core.launch.VersionResolver(paths)
                        try {
                            resolver.requiredJavaMajor(resolver.loadMerged(versionId))
                        } catch (t: Throwable) {
                            17
                        }
                    }
                    store.upsert(
                        Instance(
                            id = "inst-" + System.currentTimeMillis(),
                            name = name,
                            versionId = versionId,
                            loader = loader,
                            loaderVersion = loaderVersion,
                            javaMajor = javaMajor
                        )
                    )
                    showCreate = false
                    reload()
                }
            }
        )
    }
}

@Composable
private fun InstanceCard(
    instance: Instance,
    onLaunch: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    instance.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(instance.versionId)
                        if (instance.loader != "vanilla" && instance.loaderVersion.isNotEmpty()) {
                            append(" · ")
                            append(instance.loader)
                            append(' ')
                            append(instance.loaderVersion)
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Java ${instance.javaMajor}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onLaunch) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}