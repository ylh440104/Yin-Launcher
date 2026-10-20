package com.mcbox.launcher.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.mcbox.core.auth.DeviceCodeInfo
import com.mcbox.core.auth.MicrosoftAuth
import com.mcbox.core.model.Account
import com.mcbox.core.model.AccountType
import com.mcbox.core.model.AccountUuid
import com.mcbox.launcher.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var activeId by remember { mutableStateOf(settings.activeAccountId) }
    var showOffline by remember { mutableStateOf(false) }
    var deviceCode by remember { mutableStateOf<DeviceCodeInfo?>(null) }
    var msStatus by remember { mutableStateOf("") }
    var msBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        accounts = settings.accounts()
        activeId = settings.activeAccountId
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        Text(
            "账号",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(16.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        msBusy = true
                        error = null
                        msStatus = "请求设备码"
                        try {
                            val auth = MicrosoftAuth()
                            val account = withContext(Dispatchers.IO) {
                                auth.fullLogin(
                                    onDeviceCode = { info -> deviceCode = info },
                                    onStatus = { s -> msStatus = s }
                                )
                            }
                            settings.addAccount(account)
                            deviceCode = null
                            msStatus = "登录成功: ${account.displayName}"
                            reload()
                        } catch (t: Throwable) {
                            error = t.message
                            msStatus = ""
                        }
                        msBusy = false
                    }
                },
                enabled = !msBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text("微软登录")
            }
            OutlinedButton(
                onClick = { showOffline = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("离线登录")
            }
        }

        if (msBusy) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(msStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    active = account.id == activeId,
                    onSelect = {
                        settings.activeAccountId = account.id
                        reload()
                    },
                    onDelete = {
                        settings.removeAccount(account.id)
                        reload()
                    }
                )
            }
        }
    }

    if (deviceCode != null) {
        val info = deviceCode!!
        AlertDialog(
            onDismissRequest = { },
            title = { Text("微软登录") },
            text = {
                Column {
                    Text("在浏览器打开下面的网址，输入代码", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(info.verificationUri, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(info.userCode, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Text(msStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(info.verificationUri)
                    )
                    try {
                        context.startActivity(intent)
                    } catch (t: Throwable) {
                    }
                }) { Text("打开浏览器") }
            }
        )
    }

    if (showOffline) {
        var name by remember { mutableStateOf(settings.playerName) }
        AlertDialog(
            onDismissRequest = { showOffline = false },
            title = { Text("离线登录") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("玩家名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "3-16 字符，只能用字母数字下划线",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim()
                    if (clean.length !in 3..16 || !clean.matches(Regex("^[A-Za-z0-9_]+$"))) {
                        error = "玩家名不合法"
                        return@TextButton
                    }
                    val uuid = AccountUuid.fromOfflineName(clean)
                    settings.addAccount(
                        Account(
                            id = uuid,
                            type = AccountType.Offline,
                            displayName = clean,
                            uuid = uuid,
                            accessToken = "0"
                        )
                    )
                    settings.playerName = clean
                    showOffline = false
                    reload()
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showOffline = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AccountCard(
    account: Account,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (active) Icons.Default.CheckCircle else Icons.Default.Person,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when (account.type) {
                        AccountType.Microsoft -> "微软账号"
                        AccountType.Offline -> "离线"
                        AccountType.Authlib -> "第三方"
                    },
                    fontSize = 11.sp,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!active) {
                TextButton(onClick = onSelect) { Text("切换") }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}