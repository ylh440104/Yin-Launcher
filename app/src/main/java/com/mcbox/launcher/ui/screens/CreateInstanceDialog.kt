package com.mcbox.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcbox.core.GamePaths
import com.mcbox.launcher.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CreateInstanceDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, versionId: String, loader: String, loaderVersion: String) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        versions = withContext(Dispatchers.IO) {
            val paths = GamePaths(File(settings.gameRoot)).ensure()
            paths.versions.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
        }
        selected = versions.firstOrNull().orEmpty()
        name = selected
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建实例") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("实例名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("选择版本", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (versions.isEmpty()) {
                    Text(
                        "还没有已安装版本，先去下载页装一个",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(versions) { id ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                FilterChip(
                                    selected = selected == id,
                                    onClick = {
                                        selected = id
                                        if (name.isBlank() || name == versions.firstOrNull()) name = id
                                    },
                                    label = { Text(id, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selected.isNotEmpty()) {
                        onCreate(name.ifBlank { selected }, selected, "vanilla", "")
                    }
                }
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}