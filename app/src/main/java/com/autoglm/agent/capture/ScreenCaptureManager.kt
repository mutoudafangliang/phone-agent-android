package com.autoglm.agent.capture

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 截屏管理器
 * 使用 MediaProjection API 实现屏幕截图
 * 
 * 工作原理：
 * 1. 用户授权后获取 MediaProjection
 * 2. 创建 VirtualDisplay 绑定到 ImageReader
 * 3. ImageReader 接收每一帧画面
 * 4. 调用 captureScreen() 从 ImageReader 获取最新帧
 * 
 * 权限要求：
 * - 需要 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限
 * - 需要用户授权（通过 startActivityForResult）
 */
class ScreenCaptureManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ScreenCaptureManager"
        
        // 截图质量
        private const val IMAGE_QUALITY = 90
        
        // 最小宽高
        private const val MIN_WIDTH = 100
        private const val MIN_HEIGHT = 100
    }
    
    // MediaProjection 管理器
    private val projectionManager: MediaProjectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    // ImageReader
    private var imageReader: ImageReader? = null
    
    // VirtualDisplay
    private var virtualDisplay: VirtualDisplay? = null
    
    // MediaProjection 实例
    private var mediaProjection: MediaProjection? = null
    
    // 是否已授权
    @Volatile
    private var isAuthorized = false
    
    // 是否已准备好
    @Volatile
    private var isReady = false
    
    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * 初始化屏幕尺寸
     */
    init {
        initializeScreenSize()
    }
    
    /**
     * 获取屏幕尺寸信息
     */
    private fun initializeScreenSize() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}, density=$screenDensity")
    }
    
    /**
     * 检查是否已授权截屏
     */
    fun isAuthorized(): Boolean = isAuthorized
    
    /**
     * 检查截屏功能是否就绪
     */
    fun isReady(): Boolean = isReady && isAuthorized
    
    /**
     * 请求截屏授权
     * 返回启动授权 Activity 的 Intent
     */
    fun createCaptureIntent(): Intent {
        return projectionManager.createScreenCaptureIntent()
    }
    
    /**
     * 处理授权结果
     * @param resultCode Activity.RESULT_OK 或 Activity.RESULT_CANCELED
     * @param data 授权结果数据
     * @return true 如果初始化成功
     */
    fun onActivityResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "截屏授权被拒绝")
            isAuthorized = false
            return false
        }
        
        isAuthorized = true
        return initializeProjection(data)
    }
    
    /**
     * 处理 onActivityResult 的简化版本（使用 resultCode）
     * 调用者需要保存 projection 数据
     */
    fun handleCapturePermission(resultCode: Int) {
        isAuthorized = (resultCode == Activity.RESULT_OK)
        if (!isAuthorized) {
            Log.w(TAG, "截屏权限被拒绝")
        }
    }
    
    /**
     * 初始化 MediaProjection
     * @param data 授权结果数据
     */
    private fun initializeProjection(data: Intent): Boolean {
        try {
            // 获取 MediaProjection
            mediaProjection = projectionManager.getMediaProjection(
                Activity.RESULT_OK,
                data
            )
            
            if (mediaProjection == null) {
                Log.e(TAG, "无法创建 MediaProjection")
                return false
            }
            
            // 设置裁剪回调
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection 停止")
                    release()
                }
            }, mainHandler)
            
            // 创建 ImageReader
            if (!createImageReader()) {
                Log.e(TAG, "无法创建 ImageReader")
                return false
            }
            
            // 创建 VirtualDisplay
            if (!createVirtualDisplay()) {
                Log.e(TAG, "无法创建 VirtualDisplay")
                return false
            }
            
            isReady = true
            Log.i(TAG, "截屏服务初始化成功")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化截屏服务失败", e)
            return false
        }
    }
    
    /**
     * 创建 ImageReader
     */
    private fun createImageReader(): Boolean {
        try {
            // 关闭旧的 ImageReader
            imageReader?.close()
            
            // 创建 ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888, // 最常用的格式
                2 // 双缓冲
            )
            
            // 设置图像可用回调
            imageReader?.setOnImageAvailableListener({ reader ->
                // 图像到达，我们不主动处理，而是等待 captureScreen() 调用
                // 这里只是确保 reader 正常工作
            }, mainHandler)
            
            Log.d(TAG, "ImageReader 创建成功: ${screenWidth}x${screenHeight}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "ImageReader 创建失败", e)
            return false
        }
    }
    
    /**
     * 创建 VirtualDisplay
     */
    private fun createVirtualDisplay(): Boolean {
        val reader = imageReader ?: return false
        
        try {
            // 关闭旧的 VirtualDisplay
            virtualDisplay?.release()
            
            // 创建 VirtualDisplay
            // name: 显示名称（可任意）
            // width/height: 显示尺寸
            // densityDpi: 屏幕密度
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PhoneAgent_ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                object : VirtualDisplay.Callback() {
                    override fun onResized() {
                        Log.d(TAG, "VirtualDisplay 尺寸变化")
                    }
                    
                    override fun onPaused() {
                        Log.d(TAG, "VirtualDisplay 暂停")
                    }
                    
                    override fun onResumed() {
                        Log.d(TAG, "VirtualDisplay 恢复")
                    }
                    
                    override fun onStopped() {
                        Log.d(TAG, "VirtualDisplay 停止")
                    }
                },
                mainHandler
            )
            
            if (virtualDisplay == null) {
                Log.e(TAG, "VirtualDisplay 创建失败")
                return false
            }
            
            Log.d(TAG, "VirtualDisplay 创建成功")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay 创建失败", e)
            return false
        }
    }
    
    /**
     * 截取当前屏幕
     * @return Bitmap 截图，如果失败返回 null
     */
    fun captureScreen(): Bitmap? {
        if (!isReady || !isAuthorized) {
            Log.w(TAG, "截屏服务未就绪: isReady=$isReady, isAuthorized=$isAuthorized")
            return null
        }
        
        val reader = imageReader
        if (reader == null) {
            Log.e(TAG, "ImageReader 为空")
            return null
        }
        
        // 获取最新图像
        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "获取图像失败", e)
            null
        }
        
        if (image == null) {
            // 尝试获取旧图像
            return try {
                reader.acquireLatestImage()?.let { img ->
                    val bitmap = imageToBitmap(img)
                    img.close()
                    bitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取备用图像失败", e)
                null
            }
        }
        
        // 转换为 Bitmap
        val bitmap = imageToBitmap(image)
        image.close()
        
        return bitmap
    }
    
    /**
     * 将 Image 转换为 Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            
            // 创建 Bitmap
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // 如果有 padding，裁剪到正确尺寸
            return if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image 转 Bitmap 失败", e)
            return null
        }
    }
    
    /**
     * 重新初始化截屏（当屏幕旋转时调用）
     */
    fun reinitialize() {
        release()
        initializeScreenSize()
        
        if (isAuthorized && mediaProjection != null) {
            createImageReader()
            createVirtualDisplay()
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            isReady = false
            
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            Log.i(TAG, "截屏资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放截屏资源失败", e)
        }
    }
    
    /**
     * 获取屏幕宽度
     */
    fun getScreenWidth(): Int = screenWidth
    
    /**
     * 获取屏幕高度
     */
    fun getScreenHeight(): Int = screenHeight
    
    /**
     * 获取屏幕密度
     */
    fun getScreenDensity(): Int = screenDensity
}
