package com.jixvpn.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ConfigManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val stripFields = listOf(
        "icon", "ui", "ui-icon", "remarks", "remark", "comment",
        "alpn", "smux", "dialer-proxy", "detect-packet"
    )

    private val baseUrls = listOf(
        "https://www.gitlabip.xyz/Alvin9999/PAC/refs/heads/master/backup/img/1/2/ipp/clash.meta2",
        "https://gitlab.com/free9999/ipupdate/-/raw/master/backup/img/1/2/ipp/clash.meta2"
    )

    suspend fun downloadAll(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<File> = withContext(Dispatchers.IO) {
        val configDir = getConfigDir()
        if (configDir.exists()) configDir.deleteRecursively()
        configDir.mkdirs()

        val results = mutableListOf<File>()
        var completed = 0

        for (i in 1..15) {
            var downloaded = false
            for (baseUrl in baseUrls) {
                if (downloaded) break
                try {
                    val url = "${baseUrl}/${i}/config.yaml"
                    val req = Request.Builder().url(url).get().build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val file = File(configDir, "ip${i}.yaml")
                            file.writeText(body)
                            results.add(file)
                            downloaded = true
                        }
                    }
                } catch (_: Exception) {}
            }
            completed++
            onProgress(completed, 15)
        }

        results
    }

    fun cleanConfigs(rawFiles: List<File>): List<File> {
        val result = mutableListOf<File>()
        for (file in rawFiles) {
            val cleanFile = cleanConfig(file)
            if (cleanFile != null) result.add(cleanFile)
        }
        return result
    }

    private fun cleanConfig(file: File): File? {
        val content = file.readText()
        val blocks = parseProxies(content)
        if (blocks.isEmpty()) return null

        val cleaned = mutableListOf<String>()
        val names = mutableListOf<String>()

        for (block in blocks) {
            val pair = cleanBlock(block)
            if (pair != null) {
                cleaned.add(pair.first)
                names.add(pair.second)
            }
        }

        if (cleaned.isEmpty()) return null

        val yaml = buildMinimalYaml(cleaned, names)
        val cleanFile = File(file.parentFile, file.nameWithoutExtension + "_clean.yaml")
        cleanFile.writeText(yaml)
        return cleanFile
    }

    private fun parseProxies(content: String): List<String> {
        val lines = content.lines()
        var start = -1
        for (i in lines.indices) {
            if (lines[i].trimStart().startsWith("proxies:")) { start = i; break }
        }
        if (start < 0) return emptyList()

        var baseIndent = -1
        for (i in start + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank() || l.trimStart().startsWith("#")) continue
            baseIndent = l.length - l.trimStart().length
            break
        }
        if (baseIndent < 0) return emptyList()

        val blocks = mutableListOf<String>()
        val cur = mutableListOf<String>()
        var inEntry = false

        for (i in start + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank() || l.trimStart().startsWith("#")) {
                if (inEntry) cur.add(l)
                continue
            }
            val indent = l.length - l.trimStart().length

            if (indent == baseIndent && l.trimStart().startsWith("-")) {
                if (inEntry && cur.isNotEmpty()) {
                    blocks.add(cur.joinToString("\n"))
                    cur.clear()
                }
                inEntry = true
                cur.add(l)
            } else if (inEntry) {
                if (indent <= baseIndent && !l.trimStart().startsWith("-")) {
                    if (cur.isNotEmpty()) {
                        blocks.add(cur.joinToString("\n"))
                        cur.clear()
                    }
                    inEntry = false
                } else {
                    cur.add(l)
                }
            }
        }
        if (inEntry && cur.isNotEmpty()) {
            blocks.add(cur.joinToString("\n"))
        }

        return blocks
    }

    private fun cleanBlock(block: String): Pair<String, String>? {
        val lines = block.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (lines.isEmpty()) return null

        val base = lines[0].length - lines[0].trimStart().length
        val out = mutableListOf<String>()
        var skip = false
        var name = ""

        for (line in lines) {
            val trimmed = line.trimStart()
            val rel = (line.length - line.trimStart().length) - base

            if (rel == 0) {
                skip = false
                out.add("  $trimmed")
                val m = Regex("- name:\\s+(.+)").find(trimmed)
                if (m != null) name = m.groupValues[1].trim().trim('"').trim('\'')
            } else if (rel > 0) {
                val m = Regex("^(\\S[^:]*?):").find(trimmed)
                if (m != null && m.groupValues[1] in stripFields) {
                    skip = true; continue
                }
                if (m != null) skip = false
                if (!skip) out.add("    $trimmed")
            }
        }

        if (out.isEmpty() || name.isEmpty()) return null
        return Pair(out.joinToString("\n"), name)
    }

    private fun buildMinimalYaml(blocks: List<String>, names: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("mixed-port: 7890")
        sb.appendLine("allow-lan: false")
        sb.appendLine("log-level: info")
        sb.appendLine("dns:")
        sb.appendLine("  enabled: true")
        sb.appendLine("  nameserver:")
        sb.appendLine("    - 119.29.29.29")
        sb.appendLine("    - 223.5.5.5")
        sb.appendLine("proxies:")
        for (b in blocks) sb.appendLine(b)
        sb.appendLine("proxy-groups:")
        sb.appendLine("  - name: Proxy")
        sb.appendLine("    type: select")
        sb.appendLine("    proxies:")
        sb.appendLine("      - Auto")
        sb.appendLine("      - DIRECT")
        for (n in names) sb.appendLine("      - $n")
        sb.appendLine("  - name: Auto")
        sb.appendLine("    type: url-test")
        sb.appendLine("    url: \"https://www.gstatic.com/generate_204\"")
        sb.appendLine("    interval: 300")
        sb.appendLine("    proxies:")
        for (n in names) sb.appendLine("      - $n")
        sb.appendLine("rules:")
        sb.appendLine("  - GEOIP,CN,DIRECT")
        sb.appendLine("  - MATCH,Proxy")
        return sb.toString()
    }

    fun getConfigDir() = File(context.filesDir, "configs")

    fun listCleanConfigs(): List<File> {
        val dir = getConfigDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.name.endsWith("_clean.yaml") }?.sortedBy { it.name } ?: emptyList()
    }

    data class ConfigInfo(val name: String, val path: String, val nodeCount: Int, val nodes: List<String>)

    fun getConfigInfo(file: File): ConfigInfo {
        val content = file.readText()
        val names = mutableListOf<String>()
        val regex = Regex("^  - name:\\s+(.+)", RegexOption.MULTILINE)
        regex.findAll(content).forEach { names.add(it.groupValues[1].trim()) }
        val proxyNames = names.filter { it != "Proxy" && it != "Auto" && it != "DIRECT" && it != "GLOBAL" }
        return ConfigInfo(
            name = file.nameWithoutExtension,
            path = file.absolutePath,
            nodeCount = proxyNames.size,
            nodes = proxyNames
        )
    }
}
