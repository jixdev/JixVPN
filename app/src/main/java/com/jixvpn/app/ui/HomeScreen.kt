package com.jixvpn.app.ui

import android.content.Intent
import android.net.VpnService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixvpn.app.core.ConfigManager
import com.jixvpn.app.service.JixVpnService
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigateToNodes: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(context) }

    var configs by remember { mutableStateOf<List<ConfigManager.ConfigInfo>>(emptyList()) }
    var selectedConfig by remember { mutableStateOf<ConfigManager.ConfigInfo?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("就绪") }
    var vpnRunning by remember { mutableStateOf(JixVpnService.isRunning) }

    LaunchedEffect(Unit) {
        configs = configManager.listCleanConfigs().map { configManager.getConfigInfo(it) }
        vpnRunning = JixVpnService.isRunning
        if (vpnRunning) {
            statusText = "VPN 已连接"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("JixVPN") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            Icon(
                imageVector = if (vpnRunning) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = if (vpnRunning) Color(0xFF1A73E8) else Color.Gray
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = statusText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (vpnRunning) Color(0xFF1A73E8) else Color.Gray
            )

            Spacer(Modifier.height(24.dp))

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = downloadProgress / 15f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("下载配置中... $downloadProgress/15", modifier = Modifier.padding(top = 8.dp))
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            isDownloading = true
                            downloadProgress = 0
                            statusText = "下载中..."
                            val files = configManager.downloadAll { cur, _ -> downloadProgress = cur }
                            if (files.isNotEmpty()) {
                                val cleaned = configManager.cleanConfigs(files)
                                configs = cleaned.map { configManager.getConfigInfo(it) }
                                statusText = "下载完成，${configs.size} 个配置文件"
                            } else {
                                statusText = "下载失败，请重试"
                            }
                            isDownloading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("下载配置")
                }
            }

            Spacer(Modifier.height(16.dp))

            if (configs.isNotEmpty()) {
                Text(
                    "配置文件列表",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(configs) { config ->
                        Card(
                            onClick = { selectedConfig = config },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedConfig?.name == config.name)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Dns, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(config.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${config.nodeCount} 个节点",
                                        fontSize = 13.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            selectedConfig?.let { config ->
                                val intent = Intent(context, JixVpnService::class.java).apply {
                                    action = JixVpnService.ACTION_START
                                    putExtra(JixVpnService.EXTRA_CONFIG_PATH, config.path)
                                }
                                val vpnIntent = VpnService.prepare(context)
                                if (vpnIntent != null) {
                                    context.startActivity(vpnIntent)
                                } else {
                                    context.startForegroundService(intent)
                                }
                                vpnRunning = true
                                statusText = "VPN 已连接"
                            }
                        },
                        enabled = selectedConfig != null && !vpnRunning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A73E8)
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("启动")
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, JixVpnService::class.java).apply {
                                action = JixVpnService.ACTION_STOP
                            }
                            context.startService(intent)
                            vpnRunning = false
                            statusText = "已断开"
                        },
                        enabled = vpnRunning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFD93025)
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("停止")
                    }
                }

                if (vpnRunning) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateToNodes,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.GridView, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("切换节点")
                    }
                }
            } else if (!isDownloading) {
                Text(
                    "暂无配置文件，请先下载",
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 32.dp)
                )
            }
        }
    }
}
