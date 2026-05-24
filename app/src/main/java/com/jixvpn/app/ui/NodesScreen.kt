package com.jixvpn.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixvpn.app.core.ApiClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(onBack: () -> Unit) {
    val apiClient = remember { ApiClient() }
    val scope = rememberCoroutineScope()

    var nodes by remember { mutableStateOf<List<ApiClient.ProxyInfo>>(emptyList()) }
    var selectedNode by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            isLoading = true
            nodes = apiClient.getProxies()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("节点列表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (statusMsg.isNotBlank()) {
                Text(
                    statusMsg,
                    color = Color(0xFF1A73E8),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (nodes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("VPN 未连接或无法获取节点", color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(nodes) { node ->
                        Card(
                            onClick = {
                                scope.launch {
                                    val ok = apiClient.switchNode(node.name)
                                    statusMsg = if (ok) "已切换到: ${node.name}" else "切换失败"
                                    if (ok) selectedNode = node.name
                                }
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedNode == node.name)
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
                                Icon(
                                    imageVector = when {
                                        node.type.contains("ss", true) -> Icons.Default.VpnKey
                                        node.type.contains("vmess", true) -> Icons.Default.Shield
                                        node.type.contains("trojan", true) -> Icons.Default.Security
                                        else -> Icons.Default.Language
                                    },
                                    contentDescription = null,
                                    tint = if (selectedNode == node.name) Color(0xFF1A73E8) else Color.Gray
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        node.name,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        node.type,
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                                if (node.delay > 0) {
                                    Text(
                                        "${node.delay}ms",
                                        fontSize = 12.sp,
                                        color = if (node.delay < 200) Color(0xFF1B5E20) else Color(0xFFE65100)
                                    )
                                } else {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val delay = apiClient.testDelay(node.name)
                                            statusMsg = if (delay != null) {
                                                "${node.name} 延迟: ${delay}ms"
                                            } else {
                                                "${node.name} 超时"
                                            }
                                        }
                                    }) {
                                        Text("测速", fontSize = 12.sp)
                                    }
                                }
                                if (selectedNode == node.name) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF1A73E8)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
