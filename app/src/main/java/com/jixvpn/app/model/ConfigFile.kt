package com.jixvpn.app.model

data class ConfigFile(
    val name: String,
    val filePath: String,
    val nodeCount: Int,
    val nodes: List<String>
)
