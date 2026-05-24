package com.jixvpn.app.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiClient {

    private val baseUrl = "http://127.0.0.1:9090"
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()
    private val gson = Gson()

    data class ProxyInfo(
        val name: String,
        val type: String,
        val delay: Long = -1
    )

    suspend fun getProxies(): List<ProxyInfo> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/proxies").get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()

            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JsonParser.parseString(body)?.asJsonObject ?: return@withContext emptyList()
            val proxiesObj = json.getAsJsonObject("proxies") ?: return@withContext emptyList()

            val result = mutableListOf<ProxyInfo>()
            for (key in proxiesObj.keySet()) {
                if (key == "Proxy" || key == "Auto" || key == "GLOBAL" || key == "DIRECT" || key == "REJECT") continue
                val obj = proxiesObj.getAsJsonObject(key)
                val type = obj?.get("type")?.asString ?: "unknown"
                result.add(ProxyInfo(name = key, type = type))
            }
            result.sortedBy { it.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun switchNode(proxyName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("name" to proxyName))
            val body = json.toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("$baseUrl/proxies/Proxy")
                .put(body)
                .build()
            val resp = client.newCall(req).execute()
            resp.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun testDelay(proxyName: String): Long? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/proxies/$proxyName/delay?timeout=5000&url=https://www.gstatic.com/generate_204")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            val json = JsonParser.parseString(body)?.asJsonObject ?: return@withContext null
            json.get("delay")?.asLong
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/version").get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            val json = JsonParser.parseString(body)?.asJsonObject ?: return@withContext null
            json.get("version")?.asString
        } catch (_: Exception) {
            null
        }
    }
}
