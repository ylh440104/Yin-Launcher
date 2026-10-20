package com.mcbox.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcbox.launcher.data.GameDirs
import java.io.File

@Composable
fun DirectoryPickerDialog(
    initial: String,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit
) {
    val context = LocalContext.current
    val root = remember { GameDirs.browseRoot() }
    var current by remember {
        mutableStateOf(initial.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() } ?: root)
    }
    var manual by remember { mutableStateOf(current.absolutePath) }
    var error by remember { mutableStateOf<String?>(null) }

    val dirs = remember(current) { GameDirs.listDirs(current) }
    val parent = current.parentFile

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择游戏目录") },
        text = {
            Column {
                Text(
                    GameDirs.relativeLabel(root, current),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (parent != null) {
                                current = parent
                                manual = parent.absolutePath
                            }
                        }
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "上级")
                    }
                    Text(
                        "共 ${dirs.size} 个文件夹",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(dirs) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    current = dir
                                    manual = dir.absolutePath
                                    error = null
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(0.dp))
                            Text(
                                dir.name,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = {
                        manual = it
                        error = null
                    },
                    label = { Text("路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val target = GameDirs.normalize(manual, context)
                        if (GameDirs.isUsable(target)) {
                            current = target
                            manual = target.absolutePath
                            error = null
                        } else {
                            error = "目录不可写"
                        }
                    }) { Text("跳转") }
                    TextButton(onClick = {
                        val target = GameDirs.normalize(manual, context)
                        val created = File(target, "MCBox")
                        if (GameDirs.isUsable(created)) {
                            onPick(created)
                        } else if (GameDirs.isUsable(target)) {
                            onPick(target)
                        } else {
                            error = "无法在该路径创建目录"
                        }
                    }) { Text("在此新建 MCBox") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (GameDirs.isUsable(current)) {
                    onPick(current)
                } else {
                    error = "目录不可写：${current.absolutePath}"
                }
            }) { Text("使用此目录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}