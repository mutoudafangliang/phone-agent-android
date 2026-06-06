package com.autoglm.agent.security

import android.content.Context
import android.provider.Settings
import android.telephony.TelephonyManager
import java.security.MessageDigest

/**
 * 设备指纹生成器
 * 为每个设备生成唯一标识，用于绑定和认证
 * 
 * 指纹生成策略：
 * 1. 优先使用 ANDROID_ID（设备级别唯一，重置后会变）
 * 2. 次选 Build.FINGERPRINT（硬件+ROM 组合）
 * 3. 最后使用 TelephonyManager.getDeviceId()（需要 READ_PHONE_STATE 权限）
 * 
 * 最终使用 SHA-256 哈希生成 16 字节指纹
 */
class DeviceFingerprint(private val context: Context) {
    
    companion object {
        private const val ANDROID_ID_PREFIX = "AndroidID:"
        private const val FINGERPRINT_PREFIX = "Fingerprint:"
        private const val DEVICE_ID_PREFIX = "DeviceID:"
    }
    
    /**
     * 获取当前设备的唯一指纹
     * @return 32 字符的 SHA-256 哈希（十六进制）
     */
    fun getFingerprint(): String {
        val rawFingerprint = buildRawFingerprint()
        return sha256(rawFingerprint)
    }
    
    /**
     * 构建原始指纹字符串
     * 组合多个设备标识，增加唯一性和防伪造能力
     */
    private fun buildRawFingerprint(): String {
        val sb = StringBuilder()
        
        // 1. Android ID（Settings.Secure.ANDROID_ID）
        // 恢复出厂设置后会变化，但大多数情况下稳定
        try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrEmpty()) {
                sb.append(ANDROID_ID_PREFIX).append(androidId)
            }
        } catch (e: Exception) {
            // 忽略获取失败
        }
        
        // 2. Build.FINGERPRINT（硬件 + ROM）
        // 包含制造商、型号、ROM 信息
        sb.append(FINGERPRINT_PREFIX).append(android.os.Build.FINGERPRINT)
        
        // 3. Build.SERIAL（设备序列号）
        // 可能为 "UNKNOWN"，但如果设置了则很可靠
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val serial = android.os.Build.getSerial()
                if (!serial.isNullOrEmpty() && serial != "UNKNOWN") {
                    sb.append(":Serial:").append(serial)
                }
            }
        } catch (e: SecurityException) {
            // 没有 READ_PHONE_STATE 权限
        }
        
        return sb.toString()
    }
    
    /**
     * 使用 TelephonyManager 获取 IMEI（如果有权限）
     * @return IMEI 或 null
     */
    @Suppress("DEPRECATION")
    private fun getImei(): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null && context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 优先获取 IMEI1
                tm.deviceId ?: tm.subscriberId
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * SHA-256 哈希计算
     * @param input 原始字符串
     * @return 64 字符的十六进制哈希
     */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 获取指纹的短格式（用于 UI 显示）
     * 只显示前 8 位和后 4 位，中间用 ... 连接
     * @return 形如 "a1b2c3d4 ... 9f0e"
     */
    fun getShortFingerprint(): String {
        val full = getFingerprint()
        return if (full.length >= 12) {
            "${full.substring(0, 8)} ... ${full.substring(full.length - 4)}"
        } else {
            full
        }
    }
}
