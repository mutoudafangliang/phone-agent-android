package com.autoglm.agent.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 审计日志条目
 * 记录每个操作的详细信息
 */
data class AuditLogEntry(
    val timestamp: Long,          // 时间戳（毫秒）
    val action: String,          // 操作类型
    val details: String,         // 操作详情
    val result: String,          // 结果：SUCCESS / BLOCKED / FAILED
    val blocked: Boolean,        // 是否被拦截
    val reason: String?,        // 拦截原因（如果有）
    val clientIp: String?,       // 客户端 IP
    val sessionToken: String?,   // Session Token（只保存前缀）
    val deviceFingerprint: String? // 设备指纹
)

/**
 * 操作审计日志管理器
 * 
 * 功能：
 * 1. 记录所有远程操作（点击、滑动、输入等）
 * 2. 记录操作结果（成功/失败/拦截）
 * 3. 支持本地存储和查询
 * 4. 支持导出审计日志
 * 
 * 存储策略：
 * - 使用 JSON 格式存储，便于阅读和解析
 * - 按日期分文件存储，避免单个文件过大
 * - 保留最近 7 天的日志
 */
class AuditLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "AuditLogger"
        
        // 日志存储目录
        private const val LOG_DIR = "audit_logs"
        
        // 日志文件名格式
        private const val LOG_FILE_PREFIX = "audit_"
        private const val LOG_FILE_EXTENSION = ".json"
        
        // 最大日志条数（内存中缓存）
        private const val MAX_CACHED_LOGS = 1000
        
        // 日志保留天数
        private const val LOG_RETENTION_DAYS = 7
    }
    
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    // 内存缓存的日志
    private val logCache = mutableListOf<AuditLogEntry>()
    
    // 日志存储目录
    private val logDirectory: File
        get() {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
    
    init {
        // 清理过期日志
        cleanupOldLogs()
    }
    
    /**
     * 记录操作
     * @param action 操作类型
     * @param details 操作详情
     * @param result 结果
     * @param blocked 是否被拦截
     * @param reason 拦截原因
     * @param clientIp 客户端 IP
     * @param sessionToken Session Token
     * @param deviceFingerprint 设备指纹
     */
    fun log(
        action: String,
        details: String,
        result: String = "SUCCESS",
        blocked: Boolean = false,
        reason: String? = null,
        clientIp: String? = null,
        sessionToken: String? = null,
        deviceFingerprint: String? = null
    ) {
        val entry = AuditLogEntry(
            timestamp = System.currentTimeMillis(),
            action = action,
            details = details,
            result = result,
            blocked = blocked,
            reason = reason,
            clientIp = clientIp,
            sessionToken = sessionToken?.take(10) + "...",
            deviceFingerprint = deviceFingerprint
        )
        
        // 添加到缓存
        synchronized(logCache) {
            logCache.add(entry)
            
            // 如果缓存过大，移除最旧的
            if (logCache.size > MAX_CACHED_LOGS) {
                logCache.removeAt(0)
            }
        }
        
        // 写入文件
        writeToFile(entry)
        
        // 打印日志
        val status = if (blocked) "🔴 BLOCKED" else "🟢 $result"
        Log.i(TAG, "$status | $action | $details | $reason")
    }
    
    /**
     * 记录成功操作
     */
    fun logSuccess(
        action: String,
        details: String,
        clientIp: String? = null,
        sessionToken: String? = null
    ) {
        log(
            action = action,
            details = details,
            result = "SUCCESS",
            blocked = false,
            clientIp = clientIp,
            sessionToken = sessionToken
        )
    }
    
    /**
     * 记录失败操作
     */
    fun logFailure(
        action: String,
        details: String,
        reason: String? = null,
        clientIp: String? = null,
        sessionToken: String? = null
    ) {
        log(
            action = action,
            details = details,
            result = "FAILED",
            blocked = false,
            reason = reason,
            clientIp = clientIp,
            sessionToken = sessionToken
        )
    }
    
    /**
     * 记录被拦截的操作
     */
    fun logBlocked(
        action: String,
        details: String,
        reason: String,
        clientIp: String? = null,
        sessionToken: String? = null,
        deviceFingerprint: String? = null
    ) {
        log(
            action = action,
            details = details,
            result = "BLOCKED",
            blocked = true,
            reason = reason,
            clientIp = clientIp,
            sessionToken = sessionToken,
            deviceFingerprint = deviceFingerprint
        )
    }
    
    /**
     * 记录认证事件
     */
    fun logAuth(
        success: Boolean,
        clientIp: String? = null,
        reason: String? = null
    ) {
        log(
            action = "AUTH",
            details = if (success) "认证成功" else "认证失败",
            result = if (success) "SUCCESS" else "FAILED",
            blocked = false,
            reason = reason,
            clientIp = clientIp
        )
    }
    
    /**
     * 获取最近 N 条日志
     */
    fun getRecentLogs(count: Int = 50): List<AuditLogEntry> {
        synchronized(logCache) {
            val start = maxOf(0, logCache.size - count)
            return logCache.subList(start, logCache.size).toList()
        }
    }
    
    /**
     * 获取指定日期的日志
     */
    fun getLogsByDate(date: Date): List<AuditLogEntry> {
        val dateStr = dateFormat.format(date)
        val file = File(logDirectory, "$LOG_FILE_PREFIX$dateStr$LOG_FILE_EXTENSION")
        
        if (!file.exists()) {
            return emptyList()
        }
        
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<AuditLogEntry>>() {}.type
            gson.fromJson<List<AuditLogEntry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "读取日志文件失败: ${file.absolutePath}", e)
            emptyList()
        }
    }
    
    /**
     * 获取指定时间范围的日志
     */
    fun getLogsByTimeRange(startTime: Long, endTime: Long): List<AuditLogEntry> {
        val allLogs = mutableListOf<AuditLogEntry>()
        
        // 遍历日志目录中的所有文件
        logDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)) {
                try {
                    val json = file.readText()
                    val type = object : TypeToken<List<AuditLogEntry>>() {}.type
                    val logs: List<AuditLogEntry> = gson.fromJson(json, type) ?: emptyList()
                    allLogs.addAll(logs.filter { it.timestamp in startTime..endTime })
                } catch (e: Exception) {
                    Log.e(TAG, "读取日志文件失败: ${file.absolutePath}", e)
                }
            }
        }
        
        return allLogs.sortedBy { it.timestamp }
    }
    
    /**
     * 导出所有日志为文本格式
     */
    fun exportAsText(): String {
        val sb = StringBuilder()
        sb.appendLine("PhoneAgent 审计日志导出")
        sb.appendLine("导出时间: ${timeFormat.format(Date())}")
        sb.appendLine("=" .repeat(80))
        sb.appendLine()
        
        // 收集所有日志
        val allLogs = mutableListOf<AuditLogEntry>()
        logDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)) {
                try {
                    val json = file.readText()
                    val type = object : TypeToken<List<AuditLogEntry>>() {}.type
                    val logs: List<AuditLogEntry> = gson.fromJson(json, type) ?: emptyList()
                    allLogs.addAll(logs)
                } catch (e: Exception) {
                    Log.e(TAG, "读取日志文件失败: ${file.absolutePath}", e)
                }
            }
        }
        
        // 按时间排序
        allLogs.sortedBy { it.timestamp }.forEach { entry ->
            val time = timeFormat.format(Date(entry.timestamp))
            val status = if (entry.blocked) "🔴 BLOCKED" else "🟢 ${entry.result}"
            val reason = if (entry.reason != null) " [${entry.reason}]" else ""
            
            sb.appendLine("[$time] $status | ${entry.action} | ${entry.details}$reason")
        }
        
        return sb.toString()
    }
    
    /**
     * 清除所有日志
     */
    fun clearAllLogs() {
        synchronized(logCache) {
            logCache.clear()
        }
        
        logDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)) {
                file.delete()
            }
        }
        
        Log.i(TAG, "所有审计日志已清除")
    }
    
    /**
     * 获取日志统计信息
     */
    fun getStatistics(): Map<String, Any> {
        val totalCount = logCache.size
        val blockedCount = logCache.count { it.blocked }
        val failedCount = logCache.count { it.result == "FAILED" }
        
        // 按操作类型统计
        val actionStats = logCache.groupingBy { it.action }.eachCount()
        
        return mapOf(
            "total_count" to totalCount,
            "blocked_count" to blockedCount,
            "failed_count" to failedCount,
            "success_count" to (totalCount - blockedCount - failedCount),
            "action_stats" to actionStats,
            "blocked_rate" to if (totalCount > 0) blockedCount.toFloat() / totalCount else 0f
        )
    }
    
    /**
     * 将日志写入文件
     */
    private fun writeToFile(entry: AuditLogEntry) {
        val dateStr = dateFormat.format(Date(entry.timestamp))
        val file = File(logDirectory, "$LOG_FILE_PREFIX$dateStr$LOG_FILE_EXTENSION")
        
        try {
            // 读取现有日志
            val existingLogs = if (file.exists()) {
                try {
                    val json = file.readText()
                    val type = object : TypeToken<MutableList<AuditLogEntry>>() {}.type
                    gson.fromJson<MutableList<AuditLogEntry>>(json, type) ?: mutableListOf()
                } catch (e: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }
            
            // 添加新日志
            existingLogs.add(entry)
            
            // 写回文件
            val json = gson.toJson(existingLogs)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "写入日志文件失败: ${file.absolutePath}", e)
        }
    }
    
    /**
     * 清理过期的日志文件
     */
    private fun cleanupOldLogs() {
        val cutoffDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -LOG_RETENTION_DAYS)
        }.time
        
        logDirectory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffDate.time) {
                file.delete()
                Log.d(TAG, "删除过期日志文件: ${file.name}")
            }
        }
    }
}
