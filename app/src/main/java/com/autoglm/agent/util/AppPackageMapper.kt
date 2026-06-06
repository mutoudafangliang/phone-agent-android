package com.autoglm.agent.util

/**
 * 常见 APP 包名映射表
 * 将常见的 APP 名称映射到对应的包名，方便远程启动应用
 */
object AppPackageMapper {
    
    /**
     * APP 名称到包名的映射
     * 使用小写作为 key，方便匹配
     */
    private val nameToPackage = mapOf(
        // 即时通讯
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "qq邮箱" to "com.tencent.androidqqmail",
        "钉钉" to "com.alibaba.android.rimet",
        "企业微信" to "com.tencent.wework",
        "飞书" to "com.ss.android.lark",
        "telegram" to "org.telegram.messenger",
        "whatsapp" to "com.whatsapp",
        "signal" to "org.thoughtcrime.securesms",
        
        // 支付
        "支付宝" to "com.eg.android.AlipayGphone",
        "微信支付" to "com.tencent.mm",
        "云闪付" to "com.unionpay",
        "银联" to "com.chinaums",
        
        // 银行
        "工商银行" to "com.icbc",
        "建设银行" to "com.ccb",
        "农业银行" to "com.android.bankabc",
        "中国银行" to "com.boc.bocmobile",
        "交通银行" to "com.bankcomm",
        "招商银行" to "com.cmbchina.ccd.pluto.cmbActivity",
        "浦发银行" to "com.spdb",
        "邮储银行" to "com.psbc",
        "平安银行" to "com.PABank",
        
        // 电商
        "淘宝" to "com.taobao.taobao",
        "天猫" to "com.tmall.wireless",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele.android",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        
        // 浏览器
        "chrome" to "com.android.chrome",
        "浏览器" to "com.android.browser",
        "夸克" to "com.quark.browser",
        "UC" to "com.UCMobile",
        "Firefox" to "org.mozilla.firefox",
        "edge" to "com.microsoft.emmx",
        
        // 系统工具
        "设置" to "com.android.settings",
        "相机" to "com.android.camera2",
        "相册" to "com.google.android.apps.photos",
        "文件管理" to "com.android.documentsui",
        "计算器" to "com.android.calculator2",
        "日历" to "com.android.calendar",
        "时钟" to "com.android.deskclock",
        "备忘录" to "com.google.android.keep",
        
        // 地图导航
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "腾讯地图" to "com.tencent.map",
        "google maps" to "com.google.android.apps.maps",
        
        // 音乐视频
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "酷狗" to "com.kugou.android",
        "酷我" to "com.kuwo.music",
        "哔哩哔哩" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "优酷" to "com.youku.phone",
        "爱奇艺" to "com.qiyi.video",
        "腾讯视频" to "com.tencent.qqlive",
        "youtube" to "com.google.android.youtube",
        
        // 新闻资讯
        "今日头条" to "com.ss.android.article.news",
        "腾讯新闻" to "com.tencent.news",
        "知乎" to "com.zhihu.android",
        "微博" to "com.sina.weibo",
        
        // 游戏
        "王者荣耀" to "com.tencent.lm",
        "和平精英" to "com.tencent.ig",
        "原神" to "com.miHoYo.Yuanshen",
        "崩坏" to "com.mihoyo.hkrpg",
        "我的世界" to "com.mojang.minecraftpe"
    )
    
    /**
     * 根据名称查找包名
     * @param name APP 名称（支持中文、英文）
     * @return 包名，如果未找到返回 null
     */
    fun getPackageByName(name: String): String? {
        return nameToPackage[name.lowercase().trim()]
    }
    
    /**
     * 检查包名是否为敏感应用
     * @param packageName 要检查的包名
     * @return true 如果是敏感应用
     */
    fun isSensitivePackage(packageName: String): Boolean {
        return Constants.SENSITIVE_APP_PACKAGES.any { sensitive ->
            packageName.contains(sensitive, ignoreCase = true) ||
            sensitive.contains(packageName, ignoreCase = true)
        }
    }
    
    /**
     * 验证包名格式是否合法
     * @param packageName 要验证的包名
     * @return true 如果格式合法
     */
    fun isValidPackageName(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        // 包名格式：com.company.app 或 android.*
        val parts = packageName.split(".")
        return parts.size >= 2 && parts.all { part ->
            part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '_' }
        }
    }
}
