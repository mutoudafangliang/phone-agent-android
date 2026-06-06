package com.autoglm.agent.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.autoglm.agent.util.Constants

/**
 * 截屏权限请求 Activity
 * 
 * 用途：
 * 1. 封装 MediaProjection 授权流程
 * 2. 提供统一的结果回调
 * 3. 处理授权被拒绝的情况
 * 
 * 使用方式：
 * ```kotlin
 * val activity = CapturePermissionActivity.createIntent(context)
 * activityResultLauncher.launch(activity)
 * ```
 */
class CapturePermissionActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "CapturePermissionActivity"
        
        /**
         * 创建用于启动截屏权限请求的 Intent
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, CapturePermissionActivity::class.java)
        }
    }
    
    // 截屏管理器（由 MainActivity 注入或创建）
    private var captureManager: ScreenCaptureManager? = null
    
    // 结果回调
    private var onResult: ((Boolean) -> Unit)? = null
    
    // Activity Result API
    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        Log.i(TAG, "截屏权限结果: $granted")
        
        if (granted && result.data != null) {
            // 通知 MainActivity 处理授权结果
            notifyCaptureResult(granted, result.data)
        } else {
            Toast.makeText(this, "截屏权限被拒绝", Toast.LENGTH_SHORT).show()
            notifyCaptureResult(false, null)
        }
        
        finish()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "截屏权限请求 Activity 已启动")
        
        // 检查是否已有授权
        if (intent.getBooleanExtra("skip_if_ready", false) == true) {
            if (isCaptureAuthorized()) {
                setResult(Activity.RESULT_OK)
                finish()
                return
            }
        }
        
        // 请求截屏权限
        requestCapturePermission()
    }
    
    /**
     * 请求截屏权限
     */
    private fun requestCapturePermission() {
        try {
            // 获取 ScreenCaptureManager 并创建授权 Intent
            val screenCaptureManager = getScreenCaptureManager()
            val captureIntent = screenCaptureManager.createCaptureIntent()
            
            capturePermissionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "请求截屏权限失败", e)
            Toast.makeText(this, "请求截屏权限失败: ${e.message}", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }
    
    /**
     * 获取 ScreenCaptureManager 实例
     * 子类可重写此方法提供自定义实例
     */
    protected open fun getScreenCaptureManager(): ScreenCaptureManager {
        // 尝试从 Application 获取
        val app = application as? com.autoglm.agent.PhoneAgentApp
        return app?.screenCaptureManager ?: ScreenCaptureManager(this)
    }
    
    /**
     * 检查是否已有截屏授权
     */
    private fun isCaptureAuthorized(): Boolean {
        // 通过检查 SharedPreferences 或其他方式判断
        val prefs = getSharedPreferences("capture_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("capture_authorized", false)
    }
    
    /**
     * 保存截屏授权状态
     */
    private fun saveCaptureAuthorized(authorized: Boolean) {
        getSharedPreferences("capture_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("capture_authorized", authorized)
            .apply()
    }
    
    /**
     * 通知截屏结果
     * 通过 LocalBroadcast 广播结果
     */
    private fun notifyCaptureResult(granted: Boolean, data: Intent?) {
        saveCaptureAuthorized(granted)
        
        val intent = Intent(Constants.ACTION_SCREEN_CAPTURE_RESULT).apply {
            putExtra("granted", granted)
            data?.let { putExtra(Constants.EXTRA_SCREEN_CAPTURE_RESULT_DATA, it) }
            setPackage(packageName)
        }
        sendBroadcast(intent)
        
        Log.d(TAG, "截屏结果已广播: granted=$granted")
    }
    
    /**
     * 设置结果回调
     */
    fun setOnResultListener(listener: (Boolean) -> Unit) {
        onResult = listener
    }
    
    override fun onDestroy() {
        super.onDestroy()
        onResult = null
    }
}
