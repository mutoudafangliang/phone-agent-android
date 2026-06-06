package com.autoglm.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autoglm.agent.MainActivity
import com.autoglm.agent.R
import com.autoglm.agent.util.Constants

/**
 * 前台服务
 * 确保 Agent 始终在后台运行，防止被系统回收
 * 
 * Android 8.0+ 要求所有后台服务都必须显示通知
 * 此服务以 foreground 形式运行，显示持久通知
 */
class AgentForegroundService : Service() {
    
    companion object {
        private const val TAG = "AgentForegroundService"
        
        const val ACTION_START = "com.autoglm.agent.START_FOREGROUND"
        const val ACTION_STOP = "com.autoglm.agent.STOP_FOREGROUND"
        const val ACTION_UPDATE_STATUS = "com.autoglm.agent.UPDATE_STATUS"
        
        private const val EXTRA_SERVER_ADDRESS = "server_address"
        
        @Volatile
        private var isRunning = false
        
        fun isServiceRunning(): Boolean = isRunning
        
        /**
         * 启动前台服务
         */
        fun start(context: Context, serverAddress: String? = null) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_ADDRESS, serverAddress)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * 停止前台服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * 更新服务状态
         */
        fun updateStatus(context: Context, serverAddress: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_SERVER_ADDRESS, serverAddress)
            }
            context.startService(intent)
        }
    }
    
    private var notificationManager: NotificationManager? = null
    private var currentServerAddress: String? = null
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.i(TAG, "前台服务已创建")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentServerAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)
                startForegroundService()
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
            ACTION_UPDATE_STATUS -> {
                currentServerAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS)
                updateNotification()
            }
        }
        
        // STICKY 确保服务被杀死后会重启
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "前台服务已销毁")
        super.onDestroy()
    }
    
    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false) // 不显示角标
                enableLights(false) // 不亮灯
                enableVibration(false) // 不震动
            }
            
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "通知渠道已创建")
        }
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        isRunning = true
        
        val notification = createNotification()
        startForeground(Constants.NOTIFICATION_ID, notification)
        
        Log.i(TAG, "前台服务已启动，serverAddress=$currentServerAddress")
    }
    
    /**
     * 停止前台服务
     */
    private fun stopForegroundService() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "前台服务已停止")
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        // 点击通知打开主界面
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // 停止服务按钮
        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val contentText = if (!currentServerAddress.isNullOrEmpty()) {
            getString(R.string.notification_text, currentServerAddress)
        } else {
            getString(R.string.notification_channel_desc)
        }
        
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentIntent(mainPendingIntent)
            .addAction(
                R.drawable.ic_lock,
                getString(R.string.stop_server),
                stopPendingIntent
            )
            .setOngoing(true) // 不可滑动清除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification() {
        val notification = createNotification()
        notificationManager?.notify(Constants.NOTIFICATION_ID, notification)
        Log.d(TAG, "通知已更新: $currentServerAddress")
    }
}
