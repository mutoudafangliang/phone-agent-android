package com.autoglm.agent.server

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autoglm.agent.capture.ScreenCaptureManager
import com.autoglm.agent.security.DeviceFingerprint
import com.autoglm.agent.service.ControlAccessibilityService
import com.autoglm.agent.util.Constants
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * PhoneAgent HTTP 服务器
 * 使用 NanoHTTPD 实现轻量级 HTTP Server
 * 
 * 支持的端点：
 * - POST /auth          - 认证（Token + PIN）
 * - GET  /health        - 健康检查
 * - POST /screenshot    - 截屏
 * - POST /tap           - 点击
 * - POST /double_tap    - 双击
 * - POST /long_press    - 长按
 * - POST /swipe         - 滑动
 * - POST /type          - 输入文本
 * - POST /keyevent      - 按键事件
 * - POST /launch        - 启动应用
 * - GET  /current_app  - 获取当前应用
 * - POST /disconnect    - 断开连接
 */
class AgentHttpServer(
    private val context: Context,
    private val port: Int = Constants.DEFAULT_PORT
) : NanoHTTPD(port) {
    
    companion object {
        private const val TAG = "AgentHttpServer"
        
        // 请求头名称
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_X_FINGERPRINT = "X-Device-Fingerprint"
        
        // Content-Type
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        private const val CONTENT_TYPE_PNG = "image/png"
        
        // Bearer Token 前缀
        private const val BEARER_PREFIX = "Bearer "
    }
    
    // 依赖组件（由 MainActivity 注入）
    var authManager: AuthManager? = null
    var auditLogger: AuditLogger? = null
    var sensitiveGuard: SensitiveGuard? = null
    var accessibilityService: ControlAccessibilityService? = null
    var screenCaptureManager: ScreenCaptureManager? = null
    var deviceFingerprint: DeviceFingerprint? = null
    
    // 服务状态
    @Volatile
    var isRunning = false
        private set
    
    // 服务地址
    var serverAddress: String? = null
        private set
    
    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri
        val clientIp = session.remoteIpAddress
        
        Log.d(TAG, "$method $uri from $clientIp")
        
        // 记录请求
        auditLogger?.log(
            action = "HTTP_REQUEST",
            details = "$method $uri",
            clientIp = clientIp
        )
        
        return try {
            when {
                // 认证端点（无需 Token）
                uri == "/auth" && method == Method.POST -> handleAuth(session)
                
                // 健康检查（需要 Token）
                uri == "/health" && method == Method.GET -> handleHealth(session)
                
                // 截屏（需要 Token）
                uri == "/screenshot" && method == Method.POST -> handleScreenshot(session)
                
                // 点击操作（需要 Token）
                uri == "/tap" && method == Method.POST -> handleTap(session, clientIp)
                
                // 双击操作（需要 Token）
                uri == "/double_tap" && method == Method.POST -> handleDoubleTap(session, clientIp)
                
                // 长按操作（需要 Token）
                uri == "/long_press" && method == Method.POST -> handleLongPress(session, clientIp)
                
                // 滑动操作（需要 Token）
                uri == "/swipe" && method == Method.POST -> handleSwipe(session, clientIp)
                
                // 输入文本（需要 Token）
                uri == "/type" && method == Method.POST -> handleType(session, clientIp)
                
                // 按键事件（需要 Token）
                uri == "/keyevent" && method == Method.POST -> handleKeyEvent(session, clientIp)
                
                // 启动应用（需要 Token）
                uri == "/launch" && method == Method.POST -> handleLaunch(session, clientIp)
                
                // 获取当前应用（需要 Token）
                uri == "/current_app" && method == Method.GET -> handleCurrentApp(session)
                
                // 断开连接（需要 Token）
                uri == "/disconnect" && method == Method.POST -> handleDisconnect(session, clientIp)
                
                // 根路径
                uri == "/" && method == Method.GET -> handleRoot()
                
                // 未知端点
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    CONTENT_TYPE_JSON,
                    errorJson("not_found", "未知端点: $method $uri")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理请求失败", e)
            auditLogger?.logFailure(
                action = "HTTP_REQUEST",
                details = "$method $uri",
                reason = e.message,
                clientIp = clientIp
            )
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("internal_error", e.message ?: "内部错误")
            )
        }
    }
    
    // ==================== 认证处理 ====================
    
    private fun handleAuth(session: IHTTPSession): Response {
        val params = parsePostParams(session)
        
        val pin = params.optString("pin", "")
        val deviceFingerprintParam = params.optString("device_fingerprint", "")
        
        // 验证 PIN
        if (!authManager!!.verifyPin(pin)) {
            auditLogger?.logAuth(success = false, clientIp = session.remoteIpAddress, reason = "PIN 错误")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                CONTENT_TYPE_JSON,
                errorJson("auth_failed", "PIN 验证失败")
            )
        }
        
        // 验证设备指纹（如果提供）
        val currentFingerprint = deviceFingerprint?.getFingerprint() ?: ""
        if (deviceFingerprintParam.isNotEmpty() && deviceFingerprintParam != currentFingerprint) {
            auditLogger?.logAuth(success = false, clientIp = session.remoteIpAddress, reason = "设备指纹不匹配")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                CONTENT_TYPE_JSON,
                errorJson("device_mismatch", "设备指纹不匹配")
            )
        }
        
        // 生成 Session Token
        val token = authManager!!.generateSessionToken(currentFingerprint)
        if (token == null) {
            auditLogger?.logAuth(success = false, clientIp = session.remoteIpAddress, reason = "Token 生成失败")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("token_error", "无法生成会话令牌")
            )
        }
        
        auditLogger?.logAuth(success = true, clientIp = session.remoteIpAddress)
        
        val response = JSONObject().apply {
            put("success", true)
            put("token", token)
            put("expires_in", Constants.SESSION_TOKEN_VALIDITY_MS / 1000)
            put("device_fingerprint", currentFingerprint)
        }
        
        return newFixedLengthResponse(
            Response.Status.OK,
            CONTENT_TYPE_JSON,
            response.toString()
        )
    }
    
    // ==================== 健康检查 ====================
    
    private fun handleHealth(session: IHTTPSession): Response {
        val token = extractToken(session)
        if (!validateToken(token)) {
            return unauthorizedResponse()
        }
        
        val response = JSONObject().apply {
            put("status", "ok")
            put("server", "PhoneAgent")
            put("version", "1.0")
            put("uptime", System.currentTimeMillis())
            put("accessibility_enabled", accessibilityService?.isServiceEnabled() ?: false)
            put("capture_ready", screenCaptureManager?.isReady() ?: false)
            put("active_sessions", authManager?.getActiveSessionCount() ?: 0)
        }
        
        return newFixedLengthResponse(
            Response.Status.OK,
            CONTENT_TYPE_JSON,
            response.toString()
        )
    }
    
    // ==================== 截屏 ====================
    
    private fun handleScreenshot(session: IHTTPSession): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        auditLogger?.logSuccess("SCREENSHOT", "请求截屏", session.remoteIpAddress, token)
        
        val bitmap = screenCaptureManager?.captureScreen()
        if (bitmap == null) {
            auditLogger?.logFailure("SCREENSHOT", "截屏失败", "无法获取屏幕内容", session.remoteIpAddress, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("capture_failed", "无法截取屏幕")
            )
        }
        
        // 压缩为 PNG
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        
        val response = JSONObject().apply {
            put("success", true)
            put("image", "data:image/png;base64,$base64")
            put("width", bitmap.width)
            put("height", bitmap.height)
            put("timestamp", System.currentTimeMillis())
        }
        
        bitmap.recycle()
        
        return newFixedLengthResponse(
            Response.Status.OK,
            CONTENT_TYPE_JSON,
            response.toString()
        )
    }
    
    // ==================== 点击操作 ====================
    
    private fun handleTap(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val params = parsePostParams(session)
        val x = params.optInt("x", -1)
        val y = params.optInt("y", -1)
        
        if (x < 0 || y < 0) {
            return badRequestResponse("缺少 x 或 y 参数")
        }
        
        // 敏感操作检查
        val sensitiveResult = sensitiveGuard?.checkCurrentScreen(accessibilityService)
        if (sensitiveResult?.isBlocked == true) {
            auditLogger?.logBlocked(
                "TAP", "点击 ($x, $y)",
                sensitiveResult.reason ?: "敏感操作", clientIp, token
            )
            return sensitiveBlockedResponse(sensitiveResult.reason)
        }
        
        // 执行点击
        val success = accessibilityService?.performTap(x, y) ?: false
        if (!success) {
            auditLogger?.logFailure("TAP", "点击 ($x, $y)", "执行失败", clientIp, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("tap_failed", "点击操作失败")
            )
        }
        
        auditLogger?.logSuccess("TAP", "点击 ($x, $y)", clientIp, token)
        return successResponse("Tap performed at ($x, $y)")
    }
    
    // ==================== 双击操作 ====================
    
    private fun handleDoubleTap(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val params = parsePostParams(session)
        val x = params.optInt("x", -1)
        val y = params.optInt("y", -1)
        
        if (x < 0 || y < 0) {
            return badRequestResponse("缺少 x 或 y 参数")
        }
        
        val sensitiveResult = sensitiveGuard?.checkCurrentScreen(accessibilityService)
        if (sensitiveResult?.isBlocked == true) {
            auditLogger?.logBlocked("DOUBLE_TAP", "双击 ($x, $y)", sensitiveResult.reason ?: "敏感操作", clientIp, token)
            return sensitiveBlockedResponse(sensitiveResult.reason)
        }
        
        val success = accessibilityService?.performDoubleTap(x, y) ?: false
        if (!success) {
            auditLogger?.logFailure("DOUBLE_TAP", "双击 ($x, $y)", "执行失败", clientIp, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("double_tap_failed", "双击操作失败")
            )
        }
        
        auditLogger?.logSuccess("DOUBLE_TAP", "双击 ($x, $y)", clientIp, token)
        return successResponse("Double tap performed at ($x, $y)")
    }
    
    // ==================== 长按操作 ====================
    
    private fun handleLongPress(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val params = parsePostParams(session)
        val x = params.optInt("x", -1)
        val y = params.optInt("y", -1)
        val duration = params.optInt("duration", 1000)
        
        if (x < 0 || y < 0) {
            return badRequestResponse("缺少 x 或 y 参数")
        }
        
        val sensitiveResult = sensitiveGuard?.checkCurrentScreen(accessibilityService)
        if (sensitiveResult?.isBlocked == true) {
            auditLogger?.logBlocked("LONG_PRESS", "长按 ($x, $y, ${duration}ms)", sensitiveResult.reason ?: "敏感操作", clientIp, token)
            return sensitiveBlockedResponse(sensitiveResult.reason)
        }
        
        val success = accessibilityService?.performLongPress(x, y, duration) ?: false
        if (!success) {
            auditLogger?.logFailure("LONG_PRESS", "长按 ($x, $y, ${duration}ms)", "执行失败", clientIp, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("long_press_failed", "长按操作失败")
            )
        }
        
        auditLogger?.logSuccess("LONG_PRESS", "长按 ($x, $y, ${duration}ms)", clientIp, token)
        return successResponse("Long press performed at ($x, $y) for ${duration}ms")
    }
    
    // ==================== 滑动操作 ====================
    
    private fun handleSwipe(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val params = parsePostParams(session)
        val startX = params.optInt("startX", -1)
        val startY = params.optInt("startY", -1)
        val endX = params.optInt("endX", -1)
        val endY = params.optInt("endY", -1)
        val duration = params.optInt("duration", 300)
        
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            return badRequestResponse("缺少滑动坐标参数")
        }
        
        val sensitiveResult = sensitiveGuard?.checkCurrentScreen(accessibilityService)
        if (sensitiveResult?.isBlocked == true) {
            auditLogger?.logBlocked("SWIPE", "滑动 (${startX},${startY} -> ${endX},${endY})", sensitiveResult.reason ?: "敏感操作", clientIp, token)
            return sensitiveBlockedResponse(sensitiveResult.reason)
        }
        
        val success = accessibilityService?.performSwipe(startX, startY, endX, endY, duration) ?: false
        if (!success) {
            auditLogger?.logFailure("SWIPE", "滑动 (${startX},${startY} -> ${endX},${endY})", "执行失败", clientIp, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("swipe_failed", "滑动操作失败")
            )
        }
        
        auditLogger?.logSuccess("SWIPE", "滑动 (${startX},${startY} -> ${endX},${endY}, ${duration}ms)", clientIp, token)
        return successResponse("Swipe performed from ($startX, $startY) to ($endX, $endY) in ${duration}ms")
    }
    
    // ==================== 输入文本 ====================
    
    private fun handleType(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val params = parsePostParams(session)
        val text = params.optString("text", "")
        
        if (text.isEmpty()) {
            return badRequestResponse("缺少 text 参数")
        }
        
        val sensitiveResult = sensitiveGuard?.checkCurrentScreen(accessibilityService)
        if (sensitiveResult?.isBlocked == true) {
            auditLogger?.logBlocked("TYPE", "输入文本: ${text.take(20)}...", sensitiveResult.reason ?: "敏感操作", clientIp, token)
            return sensitiveBlockedResponse(sensitiveResult.reason)
        }
        
        val success = accessibilityService?.performType(text) ?: false
        if (!success) {
            auditLogger?.logFailure("TYPE", "输入文本: ${text.take(20)}...", "执行失败", clientIp, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("type_failed", "文本输入失败")
            )
        }
        
        auditLogger?.logSuccess("TYPE", "输入文本: ${text.take(20)}...", clientIp, token)
        return successResponse("Text typed: ${text.take(50)}")
    }
    
    // ==================== 按键事件 ====================
    
    private fun handleKeyEvent(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val params = parsePostParams(session)
        val key = params.optString("key", "").uppercase()
        
        val success = when (key) {
            "BACK" -> accessibilityService?.performBack() ?: false
            "HOME" -> accessibilityService?.performHome() ?: false
            "RECENT" -> accessibilityService?.performRecentApps() ?: false
            "POWER" -> accessibilityService?.performPower() ?: false
            "VOLUME_UP" -> accessibilityService?.performVolumeUp() ?: false
            "VOLUME_DOWN" -> accessibilityService?.performVolumeDown() ?: false
            else -> {
                auditLogger?.logFailure("KEYEVENT", "按键: $key", "未知按键", clientIp, token)
                return badRequestResponse("未知按键: $key，支持: BACK, HOME, RECENT, POWER, VOLUME_UP, VOLUME_DOWN")
            }
        }
        
        if (!success) {
            auditLogger?.logFailure("KEYEVENT", "按键: $key", "执行失败", clientIp, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("keyevent_failed", "按键操作失败")
            )
        }
        
        auditLogger?.logSuccess("KEYEVENT", "按键: $key", clientIp, token)
        return successResponse("Key event: $key performed")
    }
    
    // ==================== 启动应用 ====================
    
    private fun handleLaunch(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val params = parsePostParams(session)
        val appId = params.optString("app", "")
        
        if (appId.isEmpty()) {
            return badRequestResponse("缺少 app 参数")
        }
        
        // 检查是否为敏感应用
        if (com.autoglm.agent.util.AppPackageMapper.isSensitivePackage(appId)) {
            auditLogger?.logBlocked("LAUNCH", "启动应用: $appId", "敏感应用被拦截", clientIp, token)
            return sensitiveBlockedResponse("敏感应用被拦截: $appId")
        }
        
        val success = accessibilityService?.launchApp(appId) ?: false
        if (!success) {
            auditLogger?.logFailure("LAUNCH", "启动应用: $appId", "执行失败，可能应用不存在", clientIp, token)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                CONTENT_TYPE_JSON,
                errorJson("launch_failed", "应用启动失败，可能应用不存在")
            )
        }
        
        auditLogger?.logSuccess("LAUNCH", "启动应用: $appId", clientIp, token)
        return successResponse("App launched: $appId")
    }
    
    // ==================== 获取当前应用 ====================
    
    private fun handleCurrentApp(session: IHTTPSession): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        val packageName = accessibilityService?.getCurrentPackageName() ?: "unknown"
        val appName = com.autoglm.agent.util.AppPackageMapper.nameToPackage.entries
            .find { it.value == packageName }?.key ?: packageName
        
        val response = JSONObject().apply {
            put("success", true)
            put("package", packageName)
            put("name", appName)
            put("timestamp", System.currentTimeMillis())
        }
        
        return newFixedLengthResponse(
            Response.Status.OK,
            CONTENT_TYPE_JSON,
            response.toString()
        )
    }
    
    // ==================== 断开连接 ====================
    
    private fun handleDisconnect(session: IHTTPSession, clientIp: String?): Response {
        val token = extractToken(session)
        if (!validateToken(token)) return unauthorizedResponse()
        
        authManager?.revokeSessionToken(token!!)
        auditLogger?.logSuccess("DISCONNECT", "会话断开", clientIp, token!!)
        
        return successResponse("Disconnected")
    }
    
    // ==================== 根路径 ====================
    
    private fun handleRoot(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>PhoneAgent</title></head>
            <body>
                <h1>PhoneAgent</h1>
                <p>Remote Control Agent for Android</p>
                <p>Version: 1.0</p>
                <p>Status: Running</p>
            </body>
            </html>
        """.trimIndent()
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html
        )
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 从请求头提取 Bearer Token
     */
    private fun extractToken(session: IHTTPSession): String? {
        val authHeader = session.headers[HEADER_AUTHORIZATION.lowercase()]
        return if (authHeader?.startsWith(BEARER_PREFIX) == true) {
            authHeader.substring(BEARER_PREFIX.length)
        } else {
            null
        }
    }
    
    /**
     * 验证 Token
     */
    private fun validateToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val fingerprint = deviceFingerprint?.getFingerprint() ?: ""
        return authManager?.validateSessionToken(token, fingerprint) ?: false
    }
    
    /**
     * 解析 POST 请求参数
     */
    private fun parsePostParams(session: IHTTPSession): JSONObject {
        val params = JSONObject()
        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val postBody = session.queryParameterString
            if (!postBody.isNullOrEmpty()) {
                val pairs = postBody.split("&")
                pairs.forEach { pair ->
                    val idx = pair.indexOf("=")
                    if (idx > 0) {
                        val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        params.put(key, value)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 POST 参数失败", e)
        }
        return params
    }
    
    /**
     * 构造成功响应
     */
    private fun successResponse(message: String): Response {
        val response = JSONObject().apply {
            put("success", true)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            CONTENT_TYPE_JSON,
            response.toString()
        )
    }
    
    /**
     * 构造错误响应
     */
    private fun errorJson(error: String, message: String): String {
        return JSONObject().apply {
            put("error", error)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
    
    /**
     * 401 未授权响应
     */
    private fun unauthorizedResponse(): Response {
        return newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            CONTENT_TYPE_JSON,
            errorJson("unauthorized", "需要有效的认证令牌")
        )
    }
    
    /**
     * 400 错误请求响应
     */
    private fun badRequestResponse(message: String): Response {
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            CONTENT_TYPE_JSON,
            errorJson("bad_request", message)
        )
    }
    
    /**
     * 403 敏感操作拦截响应
     */
    private fun sensitiveBlockedResponse(reason: String?): Response {
        return newFixedLengthResponse(
            Response.Status.FORBIDDEN,
            CONTENT_TYPE_JSON,
            errorJson("sensitive_operation_blocked", reason ?: "敏感操作被拦截")
        )
    }
    
    // ==================== 服务生命周期 ====================
    
    override fun start() {
        isRunning = true
        super.start()
        Log.i(TAG, "HTTP Server 已启动，端口: $port")
    }
    
    override fun stop() {
        isRunning = false
        super.stop()
        Log.i(TAG, "HTTP Server 已停止")
    }
    
    /**
     * 获取服务器绑定的地址
     */
    fun updateServerAddress(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val ipAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
            
            if (ipAddress != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
                serverAddress = "https://$ip:$port"
                return serverAddress
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 IP 地址失败", e)
        }
        
        serverAddress = "https://localhost:$port"
        return serverAddress
    }
}
