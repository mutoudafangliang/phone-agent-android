package com.autoglm.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autoglm.agent.databinding.ActivityMainBinding
import com.autoglm.agent.server.AuditLogEntry
import com.autoglm.agent.service.ControlAccessibilityService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PhoneAgent 主界面
 * 
 * 功能：
 * 1. 显示服务状态（运行中/停止）
 * 2. 显示无障碍服务和截屏权限状态
 * 3. PIN 码设置/修改
 * 4. 证书生成
 * 5. 设备指纹显示
 * 6. 服务启动/停止控制
 * 7. 审计日志查看
 * 
 * 布局结构：
 * - 状态卡片：显示服务状态、地址、权限状态
 * - 安全设置卡片：PIN、证书、mTLS 状态、指纹
 * - 权限卡片：请求无障碍和截屏权限
 * - 服务控制按钮：启动/停止服务
 * - 审计日志：显示最近操作记录
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    // Application 引用
    private val app: PhoneAgentApp
        get() = application as PhoneAgentApp
    
    // 审计日志适配器
    private val auditLogAdapter = AuditLogAdapter()
    
    // 审计日志刷新 Runnable
    private val refreshLogRunnable = object : Runnable {
        override fun run() {
            refreshAuditLog()
            binding.root.postDelayed(this, 2000) // 每 2 秒刷新
        }
    }
    
    // 截屏权限 Activity Result
    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 初始化截屏服务
            val success = app.screenCaptureManager.onActivityResult(result.resultCode, result.data)
            if (success) {
                Toast.makeText(this, "截屏权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "截屏权限初始化失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "截屏权限被拒绝", Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }
    
    // 截屏结果广播接收器
    private val captureResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.autoglm.agent.util.Constants.ACTION_SCREEN_CAPTURE_RESULT) {
                val granted = intent.getBooleanExtra("granted", false)
                if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    app.screenCaptureManager.handleCapturePermission(RESULT_OK)
                }
                updateUI()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        updateUI()
        
        // 注册广播接收器
        registerCaptureReceiver()
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
        startLogRefresh()
    }
    
    override fun onPause() {
        super.onPause()
        stopLogRefresh()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(captureResultReceiver)
    }
    
    // ==================== UI 设置 ====================
    
    private fun setupUI() {
        // 设置审计日志 RecyclerView
        binding.rvAuditLog.adapter = auditLogAdapter
        binding.rvAuditLog.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        // 设置按钮点击事件
        binding.btnSetPin.setOnClickListener { showSetPinDialog() }
        binding.btnGenerateCert.setOnClickListener { generateCertificates() }
        binding.btnRequestAccessibility.setOnClickListener { requestAccessibilityPermission() }
        binding.btnRequestCapture.setOnClickListener { requestCapturePermission() }
        binding.btnToggleServer.setOnClickListener { toggleServer() }
        binding.btnClearLog.setOnClickListener { clearAuditLog() }
        
        // 显示设备指纹
        binding.tvDeviceFingerprint.text = getString(
            R.string.device_fingerprint,
            app.deviceFingerprint.getShortFingerprint()
        )
    }
    
    /**
     * 更新 UI 状态
     */
    private fun updateUI() {
        runOnUiThread {
            // 服务状态
            val isRunning = app.isServerRunning
            updateServerStatus(isRunning)
            
            // 无障碍服务状态
            updateAccessibilityStatus()
            
            // 截屏权限状态
            updateCaptureStatus()
            
            // PIN 状态
            updatePinStatus()
            
            // 证书状态
            updateCertStatus()
            
            // 服务地址
            if (isRunning && app.serverAddress != null) {
                binding.tvServerAddress.visibility = View.VISIBLE
                binding.tvServerAddress.text = getString(R.string.server_address, app.serverAddress)
            } else {
                binding.tvServerAddress.visibility = View.GONE
            }
        }
    }
    
    /**
     * 更新服务状态显示
     */
    private fun updateServerStatus(isRunning: Boolean) {
        // 状态指示器颜色
        val indicatorColor = if (isRunning) {
            ContextCompat.getColor(this, R.color.status_running)
        } else {
            ContextCompat.getColor(this, R.color.status_idle)
        }
        
        val drawable = binding.statusIndicator.background as? GradientDrawable
        drawable?.setColor(indicatorColor)
        
        // 状态文本
        binding.tvServerStatus.text = if (isRunning) {
            getString(R.string.server_status_running)
        } else {
            getString(R.string.server_status_stopped)
        }
        
        // 按钮文字
        binding.btnToggleServer.text = if (isRunning) {
            getString(R.string.stop_server)
        } else {
            getString(R.string.start_server)
        }
    }
    
    /**
     * 更新无障碍服务状态
     */
    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        val status = if (isEnabled) "已开启" else "未开启"
        val color = if (isEnabled) R.color.secure_green else R.color.warning_orange
        
        binding.tvAccessibilityStatus.text = getString(R.string.accessibility_status, status)
        binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, color))
        binding.btnRequestAccessibility.isEnabled = !isEnabled
        binding.btnRequestAccessibility.text = if (isEnabled) "无障碍已开启" else getString(R.string.request_accessibility)
    }
    
    /**
     * 更新截屏权限状态
     */
    private fun updateCaptureStatus() {
        val isAuthorized = app.screenCaptureManager.isAuthorized()
        val status = if (isAuthorized) "已授权" else "未授权"
        val color = if (isAuthorized) R.color.secure_green else R.color.warning_orange
        
        binding.tvCaptureStatus.text = getString(R.string.capture_status, status)
        binding.tvCaptureStatus.setTextColor(ContextCompat.getColor(this, color))
        binding.btnRequestCapture.isEnabled = !isAuthorized
        binding.btnRequestCapture.text = if (isAuthorized) "截屏已授权" else getString(R.string.request_capture)
    }
    
    /**
     * 更新 PIN 状态
     */
    private fun updatePinStatus() {
        val isPinSet = app.authManager.isPinSet()
        binding.tvPinStatus.text = if (isPinSet) getString(R.string.pin_set) else getString(R.string.pin_not_set)
        binding.btnSetPin.text = if (isPinSet) "修改 PIN" else "设置 PIN"
    }
    
    /**
     * 更新证书状态
     */
    private fun updateCertStatus() {
        val isGenerated = app.certificateGenerator.areCertificatesGenerated()
        binding.tvCertStatus.text = if (isGenerated) getString(R.string.cert_generated) else getString(R.string.cert_not_generated)
    }
    
    // ==================== PIN 设置 ====================
    
    private fun showSetPinDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_set_pin, null)
        val pinInput = dialogView.findViewById<TextInputEditText>(R.id.etPin)
        val confirmInput = dialogView.findViewById<TextInputEditText>(R.id.etPinConfirm)
        val pinLayout = dialogView.findViewById<TextInputLayout>(R.id.tilPin)
        val confirmLayout = dialogView.findViewById<TextInputLayout>(R.id.tilPinConfirm)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("设置 PIN 码")
            .setMessage("请输入 6 位数字 PIN 码")
            .setView(dialogView)
            .setPositiveButton("确认") { _, _ ->
                val pin = pinInput.text?.toString() ?: ""
                val confirm = confirmInput.text?.toString() ?: ""
                
                when {
                    pin.length != 6 || !pin.all { it.isDigit() } -> {
                        Toast.makeText(this, getString(R.string.pin_too_short), Toast.LENGTH_SHORT).show()
                    }
                    pin != confirm -> {
                        Toast.makeText(this, getString(R.string.pin_mismatch), Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        if (app.authManager.setPin(pin)) {
                            Toast.makeText(this, getString(R.string.pin_set_success), Toast.LENGTH_SHORT).show()
                            updateUI()
                        } else {
                            Toast.makeText(this, "PIN 设置失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    // ==================== 证书生成 ====================
    
    private fun generateCertificates() {
        binding.btnGenerateCert.isEnabled = false
        binding.tvCertStatus.text = getString(R.string.cert_generating)
        
        lifecycleScope.launch(Dispatchers.IO) {
            val success = app.certificateGenerator.generateAllCertificates()
            
            withContext(Dispatchers.Main) {
                binding.btnGenerateCert.isEnabled = true
                
                if (success) {
                    Toast.makeText(this@MainActivity, getString(R.string.cert_generated_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "证书生成失败", Toast.LENGTH_SHORT).show()
                }
                
                updateUI()
            }
        }
    }
    
    // ==================== 权限请求 ====================
    
    /**
     * 请求无障碍权限
     */
    private fun requestAccessibilityPermission() {
        try {
            // 打开无障碍服务设置
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请在列表中开启 PhoneAgent", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 请求截屏权限
     */
    private fun requestCapturePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Toast.makeText(this, "Android 5.0 以下不支持截屏", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val intent = app.screenCaptureManager.createCaptureIntent()
            capturePermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "请求截屏权限失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 检查无障碍服务是否已启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == ControlAccessibilityService::class.java.name
        }
    }
    
    // ==================== 服务控制 ====================
    
    private fun toggleServer() {
        if (app.isServerRunning) {
            // 停止服务
            app.stopServer()
            Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
        } else {
            // 检查前置条件
            if (!app.authManager.isPinSet()) {
                Toast.makeText(this, getString(R.string.error_pin_not_set), Toast.LENGTH_SHORT).show()
                return
            }
            
            if (!app.certificateGenerator.areCertificatesGenerated()) {
                Toast.makeText(this, getString(R.string.error_cert_not_ready), Toast.LENGTH_SHORT).show()
                return
            }
            
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, getString(R.string.accessibility_permission_required), Toast.LENGTH_SHORT).show()
                return
            }
            
            // 启动服务
            val success = app.startServer()
            if (success) {
                Toast.makeText(this, "服务已启动: ${app.serverAddress}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.error_server_start, "未知错误"), Toast.LENGTH_SHORT).show()
            }
        }
        
        updateUI()
    }
    
    // ==================== 审计日志 ====================
    
    private fun startLogRefresh() {
        binding.root.post(refreshLogRunnable)
    }
    
    private fun stopLogRefresh() {
        binding.root.removeCallbacks(refreshLogRunnable)
    }
    
    private fun refreshAuditLog() {
        val logs = app.auditLogger.getRecentLogs(50)
        auditLogAdapter.submitList(logs)
    }
    
    private fun clearAuditLog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除日志")
            .setMessage("确定要清除所有审计日志吗？")
            .setPositiveButton("清除") { _, _ ->
                app.auditLogger.clearAllLogs()
                refreshAuditLog()
                Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    // ==================== 广播接收器 ====================
    
    private fun registerCaptureReceiver() {
        val filter = IntentFilter(com.autoglm.agent.util.Constants.ACTION_SCREEN_CAPTURE_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureResultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureResultReceiver, filter)
        }
    }
}

// ==================== 审计日志适配器 ====================

class AuditLogAdapter : androidx.recyclerview.widget.ListAdapter<AuditLogEntry, AuditLogAdapter.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<AuditLogEntry>() {
        override fun areItemsTheSame(oldItem: AuditLogEntry, newItem: AuditLogEntry) =
            oldItem.timestamp == newItem.timestamp
        override fun areContentsTheSame(oldItem: AuditLogEntry, newItem: AuditLogEntry) =
            oldItem == newItem
    }
) {
    
    class ViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        val tvTime: android.widget.TextView = itemView.findViewById(R.id.tvTime)
        val tvAction: android.widget.TextView = itemView.findViewById(R.id.tvAction)
        val tvDetails: android.widget.TextView = itemView.findViewById(R.id.tvDetails)
        val tvStatus: android.widget.TextView = itemView.findViewById(R.id.tvStatus)
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audit_log, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        
        holder.tvTime.text = timeFormat.format(java.util.Date(entry.timestamp))
        holder.tvAction.text = entry.action
        holder.tvDetails.text = entry.details
        holder.tvStatus.text = if (entry.blocked) "🔴" else "🟢"
    }
}
