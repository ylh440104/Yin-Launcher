package com.mcbox.launcher.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcbox.core.auth.DeviceCodeInfo

@Composable
fun DeviceCodeDialog(
    info: DeviceCodeInfo,
    status: String,
    onOpenBrowser: (String) -> Unit
) {
    val context = LocalContext.current
    var copied by remember(info.userCode) { mutableStateOf(false) }

    fun copy(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("微软登录代码", info.userCode))
            cm != null
        } catch (t: Throwable) {
            false
        }
    }

    LaunchedEffect(info.userCode) {
        copied = copy()
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("微软登录") },
        text = {
            Column {
                Text(
                    if (copied) "代码已复制，粘贴即可" else "复制失败，请手动输入",
                    fontSize = 12.sp,
                    color = if (copied) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    info.verificationUri,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    info.userCode,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    status,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copied = copy()
                onOpenBrowser(info.verificationUri)
            }) { Text("复制并打开浏览器") }
        },
        dismissButton = {
            TextButton(onClick = { copied = copy() }) { Text("只复制") }
        }
    )
}