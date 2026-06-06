package com.autoglm.agent.server

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autoglm.agent.util.Constants
import com.autoglm.agent.util.AppPackageMapper

/**
 * 敏感操作检测和拦截器
 * 
 * 检测策略：
 * 1. 检测目标 APP 包名 - 如果是支付/银行类 APP，拦截操作
 * 2. 检测界面文本内容 - 如果包含敏感关键词（密码、验证码等），拦截操作
 * 3. 检测操作类型 - 某些操作（如输入密码）在任何界面都需要额外谨慎
 * 
 * 返回拦截结果包含：
 * - isBlocked: 是否拦截
 * - reason: 拦截原因
 * - confidence: 可信度 (0.0 - 1.0)
 */
data class SensitiveCheckResult(
    val isBlocked: Boolean,
    val reason: String?,
    val confidence: Float = if (isBlocked) 0.9f else 0f
)

/**
 * 敏感操作拦截器
 * 
 * 使用无障碍服务检测当前界面是否敏感
 * 结合包名和界面内容进行多维度判断
 */
class SensitiveGuard(private val context: Context) {
    
    companion object {
        private const val TAG = "SensitiveGuard"
        
        // 高敏感 APP 列表（直接拦截所有操作）
        private val HIGH_RISK_PACKAGES = setOf(
            "com.alipay.android.app",
            "com.eg.android.AlipayGphone",
            "com.tencent.wxpay",
            "com.unionpay",
            "com.chinaums",
            "com.icbc",
            "com.ccb",
            "com.bankcomm",
            "com.PABank"
        )
        
        // 中敏感 APP 列表（需要检测界面内容）
        private val MEDIUM_RISK_PACKAGES = setOf(
            "com.tencent.mm",         // 微信（包含微信支付）
            "com.cmbchina.ccd.pluto.cmbActivity", // 招商银行
            "com.spdb",               // 浦发银行
            "com.psbc"                // 邮储银行
        )
        
        // 高敏感关键词（匹配即拦截）
        private val HIGH_RISK_KEYWORDS = setOf(
            "支付密码", "支付密码设置", "确认支付", "立即支付",
            "验证码", "短信验证码", "动态密码",
            "转账", "汇款", "实时转账", "转账确认",
            "输入密码", "安全键盘"
        )
        
        // 中敏感关键词（需要结合 APP 类型判断）
        private val MEDIUM_RISK_KEYWORDS = setOf(
            "密码", "登录密码", "账户密码",
            "余额", "可用余额", "账户余额",
            "充值", "提现", "购买", "下单",
            "银行卡", "信用卡", "借记卡",
            "安全中心", "安全设置"
        )
    }
    
    /**
     * 检查操作是否应被拦截
     * 
     * @param targetPackage 目标 APP 包名（如果知道的话）
     * @param operationType 操作类型
     * @return 拦截结果
     */
    fun checkOperation(
        targetPackage: String? = null,
        operationType: OperationType = OperationType.OTHER
    ): SensitiveCheckResult {
        Log.d(TAG, "检查敏感操作: package=$targetPackage, type=$operationType")
        
        // 1. 检查包名
        if (targetPackage != null) {
            val packageResult = checkPackage(targetPackage)
            if (packageResult.isBlocked) {
                return packageResult
            }
        }
        
        // 2. 检查操作类型
        val operationResult = checkOperationType(operationType)
        if (operationResult.isBlocked) {
            return operationResult
        }
        
        return SensitiveCheckResult(isBlocked = false, reason = null)
    }
    
    /**
     * 检查目标 APP 包名
     * @param packageName APP 包名
     * @return 拦截结果
     */
    private fun checkPackage(packageName: String): SensitiveCheckResult {
        // 高风险 APP 直接拦截
        if (HIGH_RISK_PACKAGES.any { packageName.contains(it, ignoreCase = true) }) {
            val reason = "检测到高风险应用: ${getAppDisplayName(packageName)}"
            Log.w(TAG, reason)
            return SensitiveCheckResult(
                isBlocked = true,
                reason = reason,
                confidence = 1.0f
            )
        }
        
        // 中风险 APP 需要进一步检查界面内容
        if (MEDIUM_RISK_PACKAGES.any { packageName.contains(it, ignoreCase = true) }) {
            val reason = "检测到中风险应用: ${getAppDisplayName(packageName)}，需要检测界面内容"
            Log.d(TAG, reason)
            return SensitiveCheckResult(
                isBlocked = false,
                reason = reason,
                confidence = 0.5f
            )
        }
        
        return SensitiveCheckResult(isBlocked = false, reason = null)
    }
    
    /**
     * 检查操作类型
     * 某些操作在任何界面都是高风险的
     */
    private fun checkOperationType(operationType: OperationType): SensitiveCheckResult {
        return when (operationType) {
            OperationType.INPUT_PASSWORD -> {
                SensitiveCheckResult(
                    isBlocked = true,
                    reason = "密码输入操作被拦截（任何界面）",
                    confidence = 1.0f
                )
            }
            OperationType.CONFIRM_PAYMENT -> {
                SensitiveCheckResult(
                    isBlocked = true,
                    reason = "支付确认操作被拦截",
                    confidence = 1.0f
                )
            }
            else -> {
                SensitiveCheckResult(isBlocked = false, reason = null)
            }
        }
    }
    
    /**
     * 使用无障碍服务检测当前界面内容
     * 需要无障碍服务已启用
     * 
     * @param accessibilityService 无障碍服务实例
     * @return 拦截结果
     */
    fun checkCurrentScreen(
        accessibilityService: com.autoglm.agent.service.ControlAccessibilityService?
    ): SensitiveCheckResult {
        if (accessibilityService == null) {
            Log.w(TAG, "无障碍服务未就绪，无法检测界面内容")
            return SensitiveCheckResult(
                isBlocked = false,
                reason = "无障碍服务未就绪，跳过界面检测",
                confidence = 0f
            )
        }
        
        return try {
            val currentPackage = accessibilityService.getCurrentPackageName()
            val screenText = accessibilityService.getCurrentScreenText()
            
            Log.d(TAG, "当前界面: package=$currentPackage, text=$screenText")
            
            // 1. 检查包名
            if (currentPackage != null) {
                val packageResult = checkPackage(currentPackage)
                if (packageResult.isBlocked) {
                    return packageResult
                }
            }
            
            // 2. 检查界面文本内容
            if (!screenText.isNullOrEmpty()) {
                val contentResult = checkScreenContent(screenText)
                if (contentResult.isBlocked) {
                    return contentResult
                }
            }
            
            SensitiveCheckResult(isBlocked = false, reason = null)
        } catch (e: Exception) {
            Log.e(TAG, "检测界面内容失败", e)
            SensitiveCheckResult(
                isBlocked = false,
                reason = "界面检测失败: ${e.message}",
                confidence = 0f
            )
        }
    }
    
    /**
     * 检查界面文本内容是否包含敏感信息
     * @param text 界面文本
     * @return 拦截结果
     */
    private fun checkScreenContent(text: String): SensitiveCheckResult {
        val lowerText = text.lowercase()
        
        // 检查高风险关键词
        for (keyword in HIGH_RISK_KEYWORDS) {
            if (lowerText.contains(keyword.lowercase())) {
                val reason = "界面包含敏感关键词: $keyword"
                Log.w(TAG, reason)
                return SensitiveCheckResult(
                    isBlocked = true,
                    reason = reason,
                    confidence = 0.95f
                )
            }
        }
        
        // 检查中风险关键词
        var mediumRiskCount = 0
        for (keyword in MEDIUM_RISK_KEYWORDS) {
            if (lowerText.contains(keyword.lowercase())) {
                mediumRiskCount++
            }
        }
        
        // 如果中风险关键词数量 >= 3，视为高风险
        if (mediumRiskCount >= 3) {
            val reason = "界面包含多个敏感关键词（$mediumRiskCount 个），疑似敏感操作"
            Log.w(TAG, reason)
            return SensitiveCheckResult(
                isBlocked = true,
                reason = reason,
                confidence = 0.8f
            )
        }
        
        return SensitiveCheckResult(isBlocked = false, reason = null)
    }
    
    /**
     * 根据包名获取应用显示名称
     */
    private fun getAppDisplayName(packageName: String): String {
        return when {
            packageName.contains("alipay") -> "支付宝"
            packageName.contains("wxpay") || packageName.contains("wechat") -> "微信支付"
            packageName.contains("unionpay") || packageName.contains("chinaums") -> "银联"
            packageName.contains("icbc") -> "工商银行"
            packageName.contains("ccb") -> "建设银行"
            packageName.contains("bankcomm") -> "交通银行"
            packageName.contains("cmbchina") -> "招商银行"
            packageName.contains("spdb") -> "浦发银行"
            packageName.contains("psbc") -> "邮储银行"
            packageName.contains("bank") -> "银行应用"
            else -> packageName
        }
    }
    
    /**
     * 操作类型枚举
     */
    enum class OperationType {
        TAP,               // 点击
        SWIPE,             // 滑动
        INPUT_TEXT,        // 输入文本
        INPUT_PASSWORD,    // 输入密码（高风险）
        LAUNCH_APP,        // 启动应用
        SCREENSHOT,        // 截屏
        CONFIRM_PAYMENT,   // 确认支付（高风险）
        KEY_EVENT,         // 按键事件
        OTHER              // 其他
    }
}
