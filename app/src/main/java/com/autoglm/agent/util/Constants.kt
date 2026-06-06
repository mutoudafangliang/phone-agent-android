package com.autoglm.agent.util

/**
 * PhoneAgent 全局常量定义
 * 集中管理所有硬编码的常量值，便于维护和修改
 */
object Constants {
    // ==================== 服务器配置 ====================
    /** HTTP Server 默认端口 */
    const val DEFAULT_PORT = 8443
    
    /** Session Token 有效期（毫秒），默认 30 分钟 */
    const val SESSION_TOKEN_VALIDITY_MS = 30 * 60 * 1000L
    
    /** PIN 码长度 */
    const val PIN_LENGTH = 6
    
    /** 最小点击坐标（防止误触屏幕边缘） */
    const val MIN_TAP_COORDINATE = 10
    
    // ==================== 文件路径 ====================
    /** 证书存储目录 */
    const val CERTS_DIR = "certs"
    
    /** CA 证书文件名 */
    const val CA_CERT_FILE = "ca.crt"
    
    /** 服务器证书文件名 */
    const val SERVER_CERT_FILE = "server.crt"
    
    /** 服务器私钥文件名 */
    const val SERVER_KEY_FILE = "server.key"
    
    /** 客户端证书文件名 */
    const val CLIENT_CERT_FILE = "client.crt"
    
    /** 客户端私钥文件名 */
    const val CLIENT_KEY_FILE = "client.key"
    
    /** PKCS12 格式的客户端证书（包含私钥） */
    const val CLIENT_P12_FILE = "client.p12"
    
    // ==================== SharedPreferences Keys ====================
    /** PIN 码哈希（SHA-256）存储键 */
    const val PREF_PIN_HASH = "pin_hash"
    
    /** 证书是否已生成的标记 */
    const val PREF_CERTS_GENERATED = "certs_generated"
    
    /** 设备指纹 */
    const val PREF_DEVICE_FINGERPRINT = "device_fingerprint"
    
    /** 上次会话 token */
    const val PREF_LAST_SESSION_TOKEN = "last_session_token"
    
    /** 绑定的设备指纹列表（JSON 数组） */
    const val PREF_ALLOWED_FINGERPRINTS = "allowed_fingerprints"
    
    // ==================== Notification ====================
    /** 前台通知渠道 ID */
    const val NOTIFICATION_CHANNEL_ID = "phone_agent_channel"
    
    /** 前台通知 ID */
    const val NOTIFICATION_ID = 1001
    
    // ==================== Intent Actions ====================
    /** 截屏权限请求结果 Action */
    const val ACTION_SCREEN_CAPTURE_RESULT = "com.autoglm.agent.SCREEN_CAPTURE_RESULT"
    
    /** MediaProjection 额外数据 Key */
    const val EXTRA_SCREEN_CAPTURE_RESULT_CODE = "screen_capture_result_code"
    const val EXTRA_SCREEN_CAPTURE_RESULT_DATA = "screen_capture_result_data"
    
    // ==================== API 响应 ====================
    /** 成功响应码 */
    const val HTTP_OK = 200
    
    /** 认证失败响应码 */
    const val HTTP_UNAUTHORIZED = 401
    
    /** 敏感操作被拦截响应码 */
    const val HTTP_FORBIDDEN = 403
    
    /** 资源未找到响应码 */
    const val HTTP_NOT_FOUND = 404
    
    /** 服务器内部错误响应码 */
    const val HTTP_INTERNAL_ERROR = 500
    
    /** 错误响应 JSON 模板 */
    const val ERROR_JSON_TEMPLATE = """{"error": "%s", "message": "%s"}"""
    
    // ==================== 安全配置 ====================
    /** 敏感 APP 包名列表（支付/银行类） */
    val SENSITIVE_APP_PACKAGES = setOf(
        "com.alipay.android.app",           // 支付宝
        "com.eg.android.AlipayGphone",       // 支付宝（华为）
        "com.tencent.mm",                    // 微信支付（微信）
        "com.tencent.wxpay",                 // 微信支付
        "com.unionpay",                      // 云闪付
        "com.chinaums",                      // 银联
        "com.icbc",                          // 工商银行
        "com.android.bank",                  // 通用银行应用
        "com.ccb",                           // 建设银行
        "com.bankcomm",                      // 交通银行
        "com.psbc",                          // 邮储银行
        "com.PABank",                        // 平安银行
        "com.cmbchina.ccd.pluto.cmbActivity", // 招商银行
        "com.spdb",                          // 浦发银行
        "com.hxb",                           // 华夏银行
        "com.cib",                           // 兴业银行
        "com.CEB",                           // 光大银行
        "com.ms.android.service",            // 民生银行
        "com.njucx.app"                      // 南京银行
    )
    
    /** 敏感关键词列表（界面文本检测） */
    val SENSITIVE_KEYWORDS = listOf(
        "支付密码", "支付密码", "支付", "付款", "确认支付",
        "验证码", "短信验证码", "动态密码",
        "转账", "汇款", "实时转账",
        "登录密码", "账户密码", "支付密码",
        "银行卡", "信用卡", "借记卡",
        "余额", "账户余额", "可用余额",
        "充值", "提现",
        "购买", "下单", "确认下单",
        "安全中心", "安全设置"
    )
}
