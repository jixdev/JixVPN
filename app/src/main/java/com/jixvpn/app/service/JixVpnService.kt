package com.jixvpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.jixvpn.app.MainActivity
import com.jixvpn.app.core.ClashManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JixVpnService : VpnService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var clashManager: ClashManager
    private var tunFd: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_START = "com.jixvpn.app.START_VPN"
        const val ACTION_STOP = "com.jixvpn.app.STOP_VPN"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val CHANNEL_ID = "jixvpn_vpn"
        const val NOTIF_ID = 1001

        var isRunning = false
            private set
        var currentConfigPath: String? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        clashManager = ClashManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (configPath != null) {
                    currentConfigPath = configPath
                    startVpn(configPath)
                }
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(configPath: String) {
        val builder = Builder()
        builder.addAddress("10.0.0.2", 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")
        builder.setBlocking(true)
        builder.setSession("JixVPN")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMtu(1500)
        }

        tunFd = try {
            builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        if (tunFd == null) return

        startForeground(NOTIF_ID, buildNotification("JixVPN 正在连接..."))

        scope.launch {
            val configFile = java.io.File(configPath)
            val ok = clashManager.start(configFile, tunFd!!)
            if (ok) {
                isRunning = true
                updateNotification("JixVPN 已连接")
            } else {
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        currentConfigPath = null
        clashManager.stop()
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "JixVPN 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN 服务通知"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, JixVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JixVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
