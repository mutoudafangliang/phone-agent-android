package com.autoglm.agent.server

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话数据类
 * 存储单个会话的信息
 */
data class Session(
    val token: String,
    val deviceFingerprint: String,
    val createdAt: Long,
    val expiresAt: Long
) {
    /**
     * 检查会话是否过期
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() > expiresAt
    }
}

/**
 * 会话管理器
 * 内存中管理所有活跃会话
 * 
 * 设计考虑：
 * 1. 使用 ConcurrentHashMap 保证线程安全
 * 2. 会话存储在内存中，App 重启后会话失效
 * 3. 定期清理过期会话避免内存泄漏
 * 4. Token 有效期 30 分钟
 */
class SessionManager {
    
    companion object {
        private const val TAG = "SessionManager"
    }
    
    // Token -> Session 映射
    private val sessions = ConcurrentHashMap<String, Session>()
    
    /**
     * 创建新会话
     */
    fun createSession(token: String, deviceFingerprint: String, expiresAt: Long): Session {
        val session = Session(
            token = token,
            deviceFingerprint = deviceFingerprint,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt
        )
        sessions[token] = session
        Log.d(TAG, "创建会话: token=${token.take(10)}..., expiresAt=$expiresAt")
        return session
    }
    
    /**
     * 获取会话
     * @param token 会话 token
     * @return Session 或 null（如果不存在或已过期）
     */
    fun getSession(token: String): Session? {
        val session = sessions[token]
        
        // 自动清理过期会话
        if (session != null && session.isExpired()) {
            sessions.remove(token)
            Log.d(TAG, "会话已过期并清理: token=${token.take(10)}...")
            return null
        }
        
        return session
    }
    
    /**
     * 移除会话
     */
    fun removeSession(token: String) {
        sessions.remove(token)
        Log.d(TAG, "会话已移除: token=${token.take(10)}...")
    }
    
    /**
     * 获取会话数量
     */
    fun getSessionCount(): Int {
        cleanupExpiredSessions()
        return sessions.size
    }
    
    /**
     * 获取所有活跃会话
     */
    fun getAllSessions(): List<Session> {
        cleanupExpiredSessions()
        return sessions.values.toList()
    }
    
    /**
     * 清理所有过期会话
     */
    fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expiredTokens = sessions.filter { it.value.expiresAt < now }.keys
        
        expiredTokens.forEach { token ->
            sessions.remove(token)
            Log.d(TAG, "清理过期会话: token=${token.take(10)}...")
        }
        
        if (expiredTokens.isNotEmpty()) {
            Log.i(TAG, "共清理 ${expiredTokens.size} 个过期会话")
        }
    }
    
    /**
     * 移除所有会话
     */
    fun removeAllSessions() {
        val count = sessions.size
        sessions.clear()
        Log.i(TAG, "已移除所有 $count 个会话")
    }
    
    /**
     * 延长会话有效期
     * @param token 会话 token
     * @param extendByMs 延长的毫秒数
     * @return true 如果延长成功
     */
    fun extendSession(token: String, extendByMs: Long): Boolean {
        val session = sessions[token] ?: return false
        
        val updatedSession = session.copy(expiresAt = session.expiresAt + extendByMs)
        sessions[token] = updatedSession
        
        Log.d(TAG, "会话有效期延长: token=${token.take(10)}..., newExpiresAt=${updatedSession.expiresAt}")
        return true
    }
}
