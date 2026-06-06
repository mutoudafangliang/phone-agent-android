package com.autoglm.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.content.Context

/**
 * 控制无障碍服务
 * 负责执行远程控制操作：点击、滑动、输入文本等
 * 
 * 技术实现：
 * 1. 使用 dispatchGesture() 执行手势操作（需要 API 24+）
 * 2. 使用 performGlobalAction() 执行系统级操作（返回、Home 等）
 * 3. 使用 AccessibilityNodeInfo 进行文本输入
 * 
 * 注意：
 * - 用户必须在系统设置中手动开启此服务
 * - 需要 BIND_ACCESSIBILITY_SERVICE 权限
 * - 手势操作可能因系统版本而异
 */
class ControlAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "ControlAccessibilityService"
        
        // 全局实例引用
        @Volatile
        private var instance: ControlAccessibilityService? = null
        
        fun getInstance(): ControlAccessibilityService? = instance
        
        // 手势回调结果常量
        private const val GESTURE_SUCCESS = true
        private const val GESTURE_FAILED = false
    }
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 手势结果回调
    private var gestureCallback: GestureResultCallback? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        android.util.Log.i(TAG, "无障碍服务已创建")
    }
    
    override fun onDestroy() {
        instance = null
        android.util.Log.i(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.i(TAG, "无障碍服务已连接")
        
        // 设置服务信息
        val serviceInfo = android.accessibilityservice.AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        this.serviceInfo = serviceInfo
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可选：记录界面变化用于调试
        // android.util.Log.d(TAG, "界面事件: ${event?.eventType}, package=${event?.packageName}")
    }
    
    override fun onInterrupt() {
        android.util.Log.w(TAG, "无障碍服务被中断")
    }
    
    // ==================== 公共 API ====================
    
    /**
     * 检查服务是否已启用
     */
    fun isServiceEnabled(): Boolean {
        return instance != null
    }
    
    /**
     * 获取当前前台应用的包名
     */
    fun getCurrentPackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }
    
    /**
     * 获取当前界面的所有文本内容
     * 用于敏感操作检测
     */
    fun getCurrentScreenText(): String? {
        val rootNode = rootInActiveWindow ?: return null
        return extractTextRecursively(rootNode)
    }
    
    /**
     * 执行点击操作
     * @param x 点击的 X 坐标
     * @param y 点击的 Y 坐标
     * @return true 如果操作成功提交
     */
    fun performTap(x: Int, y: Int): Boolean {
        val clickPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()
        
        return dispatchGestureWithCallback(gesture)
    }
    
    /**
     * 执行双击操作
     * @param x 点击的 X 坐标
     * @param y 点击的 Y 坐标
     */
    fun performDoubleTap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        // 两次点击，间隔 100ms
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .addStroke(GestureDescription.StrokeDescription(path, 150, 50))
            .build()
        
        return dispatchGestureWithCallback(gesture)
    }
    
    /**
     * 执行长按操作
     * @param x 点击的 X 坐标
     * @param y 点击的 Y 坐标
     * @param duration 长按持续时间（毫秒）
     */
    fun performLongPress(x: Int, y: Int, duration: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            .build()
        
        return dispatchGestureWithCallback(gesture)
    }
    
    /**
     * 执行滑动操作
     * @param startX 起始 X 坐标
     * @param startY 起始 Y 坐标
     * @param endX 结束 X 坐标
     * @param endY 结束 Y 坐标
     * @param duration 滑动持续时间（毫秒）
     */
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int): Boolean {
        val swipePath = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration.toLong()))
            .build()
        
        return dispatchGestureWithCallback(gesture)
    }
    
    /**
     * 输入文本
     * 使用两种策略：
     * 1. 找到当前焦点输入框，使用 ACTION_SET_TEXT
     * 2. 如果没有焦点输入框，使用剪贴板粘贴
     * @param text 要输入的文本
     */
    fun performType(text: String): Boolean {
        // 策略 1：查找焦点输入框
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val performed = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (performed) {
                android.util.Log.d(TAG, "文本输入成功（焦点输入框）: ${text.take(20)}...")
                focusedNode.recycle()
                return true
            }
            focusedNode.recycle()
        }
        
        // 策略 2：使用剪贴板粘贴
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("input", text)
            clipboard?.setPrimaryClip(clip)
            
            // 模拟粘贴操作：先聚焦，然后粘贴
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // 尝试在根节点上执行粘贴
                val performed = rootNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                android.util.Log.d(TAG, "剪贴板粘贴: $performed")
                rootNode.recycle()
                return performed
            }
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "文本输入失败", e)
            false
        }
    }
    
    // ==================== 全局动作 ====================
    
    /**
     * 按下返回键
     */
    fun performBack(): Boolean {
        val performed = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        android.util.Log.d(TAG, "执行返回键: $performed")
        return performed
    }
    
    /**
     * 按下 Home 键
     */
    fun performHome(): Boolean {
        val performed = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        android.util.Log.d(TAG, "执行 Home 键: $performed")
        return performed
    }
    
    /**
     * 打开最近任务列表
     */
    fun performRecentApps(): Boolean {
        val performed = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        android.util.Log.d(TAG, "执行最近任务: $performed")
        return performed
    }
    
    /**
     * 模拟电源键（锁屏/亮屏）
     */
    fun performPower(): Boolean {
        val performed = performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        android.util.Log.d(TAG, "执行电源菜单: $performed")
        return performed
    }
    
    /**
     * 音量上
     */
    fun performVolumeUp(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.adjustVolume(android.media.AudioManager.ADJUST_RAISE, 0)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "音量上失败", e)
            false
        }
    }
    
    /**
     * 音量下
     */
    fun performVolumeDown(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.adjustVolume(android.media.AudioManager.ADJUST_LOWER, 0)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "音量下失败", e)
            false
        }
    }
    
    /**
     * 启动指定应用
     * @param packageName 应用包名
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                android.util.Log.d(TAG, "应用启动成功: $packageName")
                true
            } else {
                android.util.Log.w(TAG, "无法找到启动意图: $packageName")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "应用启动失败: $packageName", e)
            false
        }
    }
    
    /**
     * 打开快速设置面板
     */
    fun openQuickSettings(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }
    
    /**
     * 锁屏
     */
    fun lockScreen(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }
    
    /**
     * 截图（使用系统截图功能）
     */
    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 使用回调派发手势
     * 由于 dispatchGesture 是异步的，我们需要等待结果
     */
    private fun dispatchGestureWithCallback(gesture: GestureDescription): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = GESTURE_SUCCESS
                latch.countDown()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result = GESTURE_FAILED
                latch.countDown()
            }
        }
        
        val dispatched = dispatchGesture(gesture, callback, mainHandler)
        
        if (!dispatched) {
            android.util.Log.w(TAG, "手势派发失败（同步）")
            return false
        }
        
        // 等待手势完成（最多 5 秒）
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            android.util.Log.e(TAG, "等待手势完成被中断", e)
            Thread.currentThread().interrupt()
        }
        
        return result
    }
    
    /**
     * 递归提取节点的所有文本
     */
    private fun extractTextRecursively(node: AccessibilityNodeInfo): String? {
        val sb = StringBuilder()
        
        // 获取当前节点的文本
        node.text?.let { sb.append(it).append(" ") }
        
        // 获取内容描述
        node.contentDescription?.let { sb.append(it).append(" ") }
        
        // 递归处理子节点
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractTextRecursively(child)?.let { sb.append(it) }
                child.recycle()
            }
        }
        
        return sb.toString().trim().ifEmpty { null }
    }
    
    /**
     * 查找指定坐标位置的节点
     */
    fun findNodeAtPosition(x: Int, y: Int): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByText("", false)?.firstOrNull()
    }
    
    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }
}
