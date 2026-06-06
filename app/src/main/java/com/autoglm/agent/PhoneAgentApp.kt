package com.autoglm.agent

import android.app.Application
import android.content.Context
import android.util.Log
import com.autoglm.agent.capture.ScreenCaptureManager
import com.autoglm.agent.security.CertificateGenerator
import com.autoglm.agent.security.DeviceFingerprint
import com.autoglm.agent.server.AgentHttpServer
import com.autoglm.agent.server.AuditLogger
import com.autoglm.agent.server.AuthManager
import com.autoglm.agent.server.SensitiveGuard
import com.autoglm.agent.service.AgentForegroundService
import com.autoglm.agent.service.ControlAccessibilityService

/**
 * PhoneAgent Application 类
 * 
 * 全局单例和组件管理
 * 
 * 职责：
 * 1. 全局组件初始化
 * 2. 组件生命周期管理
 * 3. 跨组件通信
 */
class PhoneAgentApp : Application() {
    
    companion object {
        private const val TAG = "PhoneAgentApp"
        
        @Volatile
        private var instance: PhoneAgentApp? = null
        
        fun getInstance(): PhoneAgentApp? = instance
    }
    
    // ==================== 核心组件 ====================
    
    /** HTTP 服务器 */
    lateinit var httpServer: AgentHttpServer
        private set
    
    /** 认证管理器 */
    lateinit var authManager: AuthManager
        private set
    
    /** 会话管理器（由 AuthManager 持有） */
    // val sessionManager: SessionManager by lazy { SessionManager() }
    
    /** 审计日志 */
    lateinit var auditLogger: AuditLogger
        private set
    
    /** 敏感操作检测 */
    lateinit var sensitiveGuard: SensitiveGuard
        private set
    
    /** 设备指纹 */
    lateinit var deviceFingerprint: DeviceFingerprint
        private set
    
    /** 截屏管理器 */
    lateinit var screenCaptureManager: ScreenCaptureManager
        private set
    
    /** 证书生成器 */
    lateinit var certificateGenerator: CertificateGenerator
        private set
    
    // ==================== 状态 ====================
    
    /** 服务是否运行中 */
    @Volatile
    var isServerRunning = false
        private set
    
    /** 服务器地址 */
    @Volatile
    var serverAddress: String? = null
        private set
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.i(TAG, "PhoneAgent Application 启动")
        
        // 初始化所有组件
        initializeComponents()
    }
    
    /**
     * 初始化所有组件
     */
    private fun initializeComponents() {
        // 1. 设备指纹（最先初始化，因为其他组件可能需要）
        deviceFingerprint = DeviceFingerprint(this)
        Log.d(TAG, "设备指纹: ${deviceFingerprint.getShortFingerprint()}")
        
        // 2. 审计日志
        auditLogger = AuditLogger(this)
        Log.d(TAG, "审计日志已初始化")
        
        // 3. 认证管理器
        authManager = AuthManager(this)
        Log.d(TAG, "认证管理器已初始化")
        
        // 4. 敏感操作检测
        sensitiveGuard = SensitiveGuard(this)
        Log.d(TAG, "敏感操作检测已初始化")
        
        // 5. 截屏管理器
        screenCaptureManager = ScreenCaptureManager(this)
        Log.d(TAG, "截屏管理器已初始化")
        
        // 6. 证书生成器
        certificateGenerator = CertificateGenerator(this)
        Log.d(TAG, "证书生成器已初始化")
        
        // 7. HTTP 服务器
        httpServer = AgentHttpServer(this)
        // 注入依赖
        httpServer.authManager = authManager
        httpServer.auditLogger = auditLogger
        httpServer.sensitiveGuard = sensitiveGuard
        httpServer.screenCaptureManager = screenCaptureManager
        httpServer.deviceFingerprint = deviceFingerprint
        Log.d(TAG, "HTTP 服务器已初始化")
    }
    
    /**
     * 启动 HTTP 服务器
     */
    fun startServer(): Boolean {
        if (isServerRunning) {
            Log.w(TAG, "服务器已在运行")
            return true
        }
        
        // 检查必要的服务状态
        if (!authManager.isPinSet()) {
            Log.e(TAG, "PIN 未设置，无法启动服务器")
            return false
        }
        
        if (!certificateGenerator.areCertificatesGenerated()) {
            Log.e(TAG, "证书未生成，无法启动服务器")
            return false
        }
        
        try {
            // 启动前台服务
            AgentForegroundService.start(this, serverAddress)
            
            // 启动 HTTP 服务器
            httpServer.start()
            
            // 获取服务器地址
            serverAddress = httpServer.updateServerAddress()
            
            isServerRunning = true
            
            // 通知前台服务更新地址
            serverAddress?.let {
                AgentForegroundService.updateStatus(this, it)
            }
            
            Log.i(TAG, "HTTP 服务器启动成功: $serverAddress")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 服务器启动失败", e)
            isServerRunning = false
            return false
        }
    }
    
    /**
     * 停止 HTTP 服务器
     */
    fun stopServer() {
        if (!isServerRunning) {
            Log.w(TAG, "服务器未运行")
            return
        }
        
        try {
            // 停止 HTTP 服务器
            httpServer.stop()
            
            // 停止前台服务
            AgentForegroundService.stop(this)
            
            // 吊销所有会话
            authManager.revokeAllSessions()
            
            isServerRunning = false
            serverAddress = null
            
            Log.i(TAG, "HTTP 服务器已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止服务器时出错", e)
        }
    }
    
    /**
     * 注入无障碍服务引用
     * 由 ControlAccessibilityService 调用
     */
    fun setAccessibilityService(service: ControlAccessibilityService?) {
        httpServer.accessibilityService = service
    }
    
    /**
     * 获取无障碍服务状态
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        return ControlAccessibilityService.getInstance() != null
    }
    
    override fun onTerminate() {
        Log.i(TAG, "PhoneAgent Application 终止")
        
        // 停止所有服务
        stopServer()
        
        // 释放截屏资源
        screenCaptureManager.release()
        
        super.onTerminate()
    }
    
    override fun onLowMemory() {
        Log.w(TAG, "系统内存不足")
        super.onLowMemory()
    }
    
    override fun onTrimMemory(level: Int) {
        Log.d(TAG, "Trim memory: level=$level")
        super.onTrimMemory(level)
    }
}
