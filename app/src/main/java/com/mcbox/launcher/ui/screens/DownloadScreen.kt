package com.mcbox.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcbox.core.GamePaths
import com.mcbox.core.install.DownloadSource
import com.mcbox.core.install.InstallProgress
import com.mcbox.core.install.ProgressListener
import com.mcbox.core.install.VersionInstaller
import com.mcbox.core.loader.FabricInstaller
import com.mcbox.core.loader.QuiltInstaller
import com.mcbox.core.model.VersionManifest
import com.mcbox.launcher.data.Instance
import com.mcbox.launcher.data.InstanceStore
import com.mcbox.launcher.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DownloadScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val instanceStore = remember { InstanceStore(context) }

    var tab by remember { mutableStateOf(0) }
    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<InstallProgress?>(null) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var loaderDialog by remember { mutableStateOf<VersionManifest.VersionSummary?>(null) }

    fun source(): DownloadSource = when (settings.downloadSource) {
        "Official" -> DownloadSource.Official
        "Mirror" -> DownloadSource.Mirror
        else -> DownloadSource.Auto
    }

    LaunchedEffect(Unit) {
        try {
            manifest = withContext(Dispatchers.IO) {
                VersionInstaller(GamePaths(File(settings.gameRoot)).ensure(), source = source()).fetchManifest()
            }
        } catch (t: Throwable) {
            error = t.message
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "下载",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(16.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索版本") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("正式版") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("快照") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("已安装") })
        }

        if (busy && progress != null) {
            val p = progress!!
            Column(Modifier.padding(16.dp)) {
                Text("${p.stage}  ${p.detail}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${p.current} / ${p.total}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (log.isNotEmpty()) {
                    Text(log, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("获取版本列表失败", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                manifest = withContext(Dispatchers.IO) {
                                    VersionInstaller(
                                        GamePaths(File(settings.gameRoot)).ensure(),
                                        source = source()
                                    ).fetchManifest()
                                }
                            } catch (t: Throwable) {
                                error = t.message
                            }
                            loading = false
                        }
                    }) { Text("重试") }
                }
            }
            else -> {
                val all = manifest?.versions.orEmpty()
                val filtered = when (tab) {
                    0 -> all.filter { it.type == "release" }
                    1 -> all.filter { it.type == "snapshot" }
                    else -> emptyList()
                }.filter { query.isBlank() || it.id.contains(query, ignoreCase = true) }

                if (tab == 2) {
                    InstalledList(instanceStore, settings)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { summary ->
                            VersionRow(
                                version = summary,
                                enabled = !busy,
                                onClick = { loaderDialog = summary }
                            )
                        }
                    }
                }
            }
        }
    }

    if (loaderDialog != null && manifest != null) {
        val summary = loaderDialog!!
        InstallDialog(
            summary = summary,
            onDismiss = { loaderDialog = null },
            onInstall = { loader, loaderVersion, versionName ->
                loaderDialog = null
                scope.launch {
                    busy = true
                    progress = InstallProgress("准备", 0, 0, "")
                    try {
                        withContext(Dispatchers.IO) {
                            installVersion(
                                settings = settings,
                                summary = summary,
                                loader = loader,
                                loaderVersion = loaderVersion,
                                versionName = versionName,
                                instanceStore = instanceStore,
                                source = source(),
                                onProgress = { p -> progress = p },
                                onLog = { log = it }
                            )
                        }
                    } catch (t: Throwable) {
                        log = "失败: ${t.message}"
                    }
                    busy = false
                    progress = null
                }
            }
        )
    }
}

@Composable
private fun VersionRow(
    version: VersionManifest.VersionSummary,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(version.id, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    version.type,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun InstalledList(store: InstanceStore, settings: SettingsStore) {
    val context = LocalContext.current
    val paths = remember { GamePaths(File(settings.gameRoot)).ensure() }
    val versions = remember {
        paths.versions.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()?.reversed().orEmpty()
    }
    if (versions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有已安装版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(versions) { id ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(id, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun InstallDialog(
    summary: VersionManifest.VersionSummary,
    onDismiss: () -> Unit,
    onInstall: (loader: String, loaderVersion: String, versionName: String) -> Unit
) {
    var loader by remember { mutableStateOf("vanilla") }
    var loaderVersion by remember { mutableStateOf("") }
    var versionName by remember { mutableStateOf(summary.id) }
    var loaderVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingLoaders by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loader, summary.id) {
        if (loader == "fabric") {
            loadingLoaders = true
            loaderVersions = try {
                withContext(Dispatchers.IO) {
                    FabricInstaller().listLoaderVersions(summary.id).map { it.version }
                }
            } catch (t: Throwable) {
                emptyList()
            }
            loaderVersion = loaderVersions.firstOrNull() ?: ""
            loadingLoaders = false
        } else if (loader == "quilt") {
            loadingLoaders = true
            loaderVersions = try {
                withContext(Dispatchers.IO) {
                    QuiltInstaller().listLoaderVersions(summary.id).map { it.version }
                }
            } catch (t: Throwable) {
                emptyList()
            }
            loaderVersion = loaderVersions.firstOrNull() ?: ""
            loadingLoaders = false
        } else {
            loaderVersions = emptyList()
            loaderVersion = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安装 ${summary.id}") },
        text = {
            Column {
                OutlinedTextField(
                    value = versionName,
                    onValueChange = { versionName = it },
                    label = { Text("实例名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("加载器", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("vanilla", "fabric", "quilt").forEach { type ->
                        FilterChip(
                            selected = loader == type,
                            onClick = { loader = type },
                            label = { Text(type) }
                        )
                    }
                }
                if (loaderVersions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("加载器版本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        loaderVersions.take(3).forEach { v ->
                            FilterChip(
                                selected = loaderVersion == v,
                                onClick = { loaderVersion = v },
                                label = { Text(v, fontSize = 11.sp) }
                            )
                        }
                    }
                }
                if (loadingLoaders) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onInstall(loader, loaderVersion, versionName.ifBlank { summary.id }) }
            ) { Text("安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun installVersion(
    settings: SettingsStore,
    summary: VersionManifest.VersionSummary,
    loader: String,
    loaderVersion: String,
    versionName: String,
    instanceStore: InstanceStore,
    source: DownloadSource,
    onProgress: (InstallProgress) -> Unit,
    onLog: (String) -> Unit
) {
    val paths = GamePaths(File(settings.gameRoot)).ensure()
    val installer = VersionInstaller(paths, source = source)
    val listener = object : ProgressListener {
        override fun onProgress(progress: InstallProgress) = onProgress(progress)
        override fun onLog(line: String) = onLog(line)
    }

    installer.install(summary.id, summary.url, listener, includeAssets = true)

    if (loader == "fabric" && loaderVersion.isNotEmpty()) {
        onProgress(InstallProgress("安装 Fabric", 0, 1, loaderVersion))
        val profile = FabricInstaller().saveProfileJson(summary.id, loaderVersion, paths.versions)
        onProgress(InstallProgress("安装 Fabric", 1, 1, profile.name))
    } else if (loader == "quilt" && loaderVersion.isNotEmpty()) {
        onProgress(InstallProgress("安装 Quilt", 0, 1, loaderVersion))
        val profile = QuiltInstaller().saveProfileJson(summary.id, loaderVersion, paths.versions)
        onProgress(InstallProgress("安装 Quilt", 1, 1, profile.name))
    }

    val resolver = com.mcbox.core.launch.VersionResolver(paths)
    val targetId = if (loader == "vanilla" || loaderVersion.isEmpty()) summary.id
    else {
        val prefix = if (loader == "fabric") "fabric-loader-$loaderVersion-" else "quilt-loader-$loaderVersion-"
        paths.versions.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(summary.id) }?.name
            ?: summary.id
    }
    val javaMajor = try {
        resolver.requiredJavaMajor(resolver.loadMerged(targetId))
    } catch (t: Throwable) {
        17
    }

    instanceStore.upsert(
        Instance(
            id = "inst-" + System.currentTimeMillis(),
            name = versionName,
            versionId = targetId,
            loader = loader,
            loaderVersion = loaderVersion,
            javaMajor = javaMajor
        )
    )
}