package com.autoglm.agent.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.autoglm.agent.util.Constants
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 认证管理器
 * 负责 PIN 码验证和 Session Token 生成
 * 
 * 安全设计：
 * 1. PIN 码存储为 SHA-256 哈希（不可逆）
 * 2. Session Token 为一次性随机令牌
 * 3. 每个 Token 有有效期（30 分钟）
 * 4. Token 验证时检查指纹匹配
 */
class AuthManager(context: Context) {
    
    companion object {
        private const val TAG = "AuthManager"
        
        // Token 长度（字节）
        private const val TOKEN_BYTES = 32
        
        // Token 前缀
        private const val TOKEN_PREFIX = "pt_"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "auth_prefs", Context.MODE_PRIVATE
    )
    
    private val sessionManager = SessionManager()
    
    /**
     * 检查 PIN 是否已设置
     */
    fun isPinSet(): Boolean {
        return prefs.contains(Constants.PREF_PIN_HASH)
    }
    
    /**
     * 设置 PIN 码
     * @param pin 6 位数字 PIN
     * @return true 如果设置成功
     */
    fun setPin(pin: String): Boolean {
        if (pin.length != Constants.PIN_LENGTH || !pin.all { it.isDigit() }) {
            Log.w(TAG, "PIN 格式不正确: length=${pin.length}")
            return false
        }
        
        val hash = hashPin(pin)
        prefs.edit().putString(Constants.PREF_PIN_HASH, hash).apply()
        Log.i(TAG, "PIN 设置成功")
        return true
    }
    
    /**
     * 验证 PIN 码
     * @param pin 用户输入的 PIN
     * @return true 如果验证通过
     */
    fun verifyPin(pin: String): Boolean {
        if (pin.length != Constants.PIN_LENGTH) {
            return false
        }
        
        val storedHash = prefs.getString(Constants.PREF_PIN_HASH, null)
        if (storedHash == null) {
            Log.w(TAG, "PIN 未设置，无法验证")
            return false
        }
        
        val inputHash = hashPin(pin)
        val matches = storedHash == inputHash
        
        if (matches) {
            Log.i(TAG, "PIN 验证成功")
        } else {
            Log.w(TAG, "PIN 验证失败")
        }
        
        return matches
    }
    
    /**
     * 生成 Session Token
     * @param deviceFingerprint 设备指纹
     * @param validDurationMs Token 有效期（毫秒）
     * @return 生成的 token，如果 PIN 未设置返回 null
     */
    fun generateSessionToken(deviceFingerprint: String, validDurationMs: Long = Constants.SESSION_TOKEN_VALIDITY_MS): String? {
        if (!isPinSet()) {
            Log.w(TAG, "PIN 未设置，无法生成 token")
            return null
        }
        
        val token = generateSecureToken()
        val expiresAt = System.currentTimeMillis() + validDurationMs
        
        sessionManager.createSession(token, deviceFingerprint, expiresAt)
        Log.i(TAG, "Session Token 生成成功，expiresAt=${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(expiresAt))}")
        
        return token
    }
    
    /**
     * 验证 Session Token
     * @param token 要验证的 token
     * @param deviceFingerprint 当前设备指纹
     * @return true 如果 token 有效且指纹匹配
     */
    fun validateSessionToken(token: String, deviceFingerprint: String): Boolean {
        if (token.isBlank()) {
            return false
        }
        
        val session = sessionManager.getSession(token)
        if (session == null) {
            Log.w(TAG, "Token 不存在或已过期")
            return false
        }
        
        // 检查指纹是否匹配
        if (session.deviceFingerprint != deviceFingerprint) {
            Log.w(TAG, "设备指纹不匹配: expected=${session.deviceFingerprint}, actual=$deviceFingerprint")
            // 注意：这里可以选择拒绝，也可以选择宽松模式放行
            // 为了安全，我们严格检查指纹匹配
            return false
        }
        
        // 检查是否过期
        if (System.currentTimeMillis() > session.expiresAt) {
            Log.w(TAG, "Token 已过期")
            sessionManager.removeSession(token)
            return false
        }
        
        return true
    }
    
    /**
     * 验证 Session Token（仅检查 token 有效性，不检查指纹）
     * 用于 /health 等低敏感操作
     */
    fun validateSessionTokenOnly(token: String): Boolean {
        if (token.isBlank()) {
            return false
        }
        
        val session = sessionManager.getSession(token) ?: return false
        
        if (System.currentTimeMillis() > session.expiresAt) {
            sessionManager.removeSession(token)
            return false
        }
        
        return true
    }
    
    /**
     * 吊销 Session Token
     * @param token 要吊销的 token
     */
    fun revokeSessionToken(token: String) {
        sessionManager.removeSession(token)
        Log.i(TAG, "Session Token 已吊销")
    }
    
    /**
     * 吊销所有会话
     */
    fun revokeAllSessions() {
        sessionManager.removeAllSessions()
        Log.i(TAG, "所有 Session Token 已吊销")
    }
    
    /**
     * 生成安全的随机 Token
     */
    private fun generateSecureToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "$TOKEN_PREFIX$base64"
    }
    
    /**
     * PIN 码 SHA-256 哈希
     */
    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // 加盐：固定盐 + PIN
        val salted = "PhoneAgentSalt2024_$pin"
        val hashBytes = digest.digest(salted.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 获取当前活跃的会话数量
     */
    fun getActiveSessionCount(): Int {
        return sessionManager.getSessionCount()
    }
    
    /**
     * 清理过期会话
     */
    fun cleanupExpiredSessions() {
        sessionManager.cleanupExpiredSessions()
    }
}
