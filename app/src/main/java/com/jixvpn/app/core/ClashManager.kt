package com.jixvpn.app.core

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ClashManager(private val context: Context) {

    private var mihomoProcess: Process? = null
    private var gotunProcess: Process? = null
    private val apiPort = 9090

    fun getWorkDir() = File(context.filesDir, "clash")

    fun getMihomoBin() = File(getWorkDir(), "mihomo")

    fun getGotunBin() = File(getWorkDir(), "gotun")

    fun isRunning() = mihomoProcess?.isAlive == true

    suspend fun extractBinaries() = withContext(Dispatchers.IO) {
        val dir = getWorkDir()
        dir.mkdirs()
        extractAsset("mihomo-arm64", getMihomoBin())
        extractAsset("gotun-arm64", getGotunBin())
    }

    private fun extractAsset(assetName: String, target: File) {
        if (target.exists()) return
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.setExecutable(true)
        } catch (e: Exception) {
            throw RuntimeException("Failed to extract $assetName: ${e.message}")
        }
    }

    suspend fun start(configFile: File, tunFd: ParcelFileDescriptor): Boolean = withContext(Dispatchers.IO) {
        try {
            extractBinaries()

            val workDir = getWorkDir()
            val configPath = File(workDir, "config.yaml")
            configFile.copyTo(configPath, overwrite = true)

            val mihomoArgs = listOf(
                getMihomoBin().absolutePath,
                "-d", workDir.absolutePath
            )
            val mihomoPb = ProcessBuilder(mihomoArgs)
            mihomoPb.directory(workDir)
            mihomoPb.environment()["CLASH_API_PORT"] = apiPort.toString()
            mihomoPb.redirectErrorStream(true)

            mihomoProcess = mihomoPb.start()

            Thread.sleep(1500)

            if (!mihomoProcess!!.isAlive) {
                stop()
                return@withContext false
            }

            val gotunArgs = listOf(getGotunBin().absolutePath)
            val gotunPb = ProcessBuilder(gotunArgs)
            gotunPb.directory(workDir)
            gotunPb.environment()["TUN_FD"] = tunFd.fd.toString()
            gotunPb.environment()["SOCKS5_ADDR"] = "127.0.0.1:7890"
            gotunPb.redirectErrorStream(true)

            gotunProcess = gotunPb.start()

            Thread.sleep(500)

            gotunProcess?.isAlive == true
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
            false
        }
    }

    fun stop() {
        try { gotunProcess?.destroyForcibly() } catch (_: Exception) {}
        gotunProcess = null
        try { mihomoProcess?.destroyForcibly() } catch (_: Exception) {}
        mihomoProcess = null
    }
}
