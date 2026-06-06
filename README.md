# PhoneAgent - 远程手机控制 Agent

让 AutoGLM 通过网络远程操控 Android 手机。使用 AccessibilityService 实现点击/滑动/输入等操作（无 root），使用 MediaProjection API 截屏。

## 安全特性

1. **mTLS 双向证书认证** — 客户端和服务端都验证证书
2. **一次性 Token + PIN 认证** — 每次会话需 PIN 解锁
3. **敏感操作拦截** — 检测到支付/银行/密码输入类界面自动拒绝
4. **操作审计日志** — 记录所有远程操作
5. **前台通知** — Agent 运行时始终显示通知
6. **绑定设备指纹** — 只允许特定设备连接

## 技术栈

- **语言**: Kotlin
- **最低版本**: Android 7.0 (API 24)
- **目标版本**: Android 14 (API 34)
- **HTTP Server**: NanoHTTPD
- **手势操作**: AccessibilityService.dispatchGesture()
- **截屏**: MediaProjection API

## 安装步骤

### 1. 编译 APK

```bash
# 克隆项目
git clone <repo-url>
cd phone-agent-android

# 确保已安装 Android SDK 和 Gradle
# 如果没有 gradlew，先安装
gradle wrapper

# 编译 Debug APK
./gradlew assembleDebug

# APK 输出位置: app/build/outputs/apk/debug/app-debug.apk
```

### 2. 安装到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 配置权限

首次启动后，按顺序配置：

1. **无障碍权限**（必须）
   - 设置 → 无障碍 → 已安装的应用 → PhoneAgent → 开启

2. **截屏权限**（必须，用于获取屏幕画面）
   - 点击主界面的"请求截屏权限"按钮
   - 在弹出的系统对话框中选择"开始录制"

3. **通知权限**（Android 13+）
   - 设置 → 通知 → PhoneAgent → 允许通知

### 4. 设置 PIN 码

点击"设置 PIN"按钮，输入 6 位数字 PIN 码。

### 5. 生成证书

点击"生成证书"按钮，系统会生成：
- CA 证书 (ca.crt)
- 服务器证书 (server.crt + server.key)
- 客户端证书 (client.p12)

### 6. 启动服务

点击"启动服务"按钮，服务启动后会显示地址：
```
https://192.168.1.100:8443
```

## API 文档

### 认证流程

```
1. 客户端使用 client.p12 证书连接（mTLS）
2. POST /auth 传入 PIN 验证
3. 获取 session token（有效期 30 分钟）
4. 后续请求带 Authorization: Bearer <token>
```

### 端点列表

| 方法 | 路径 | 描述 | 认证 |
|------|------|------|------|
| POST | /auth | Token + PIN 认证 | 否 |
| GET | /health | 健康检查 | Bearer |
| POST | /screenshot | 截屏返回 base64 | Bearer |
| POST | /tap | 点击 | Bearer |
| POST | /double_tap | 双击 | Bearer |
| POST | /long_press | 长按 | Bearer |
| POST | /swipe | 滑动 | Bearer |
| POST | /type | 输入文本 | Bearer |
| POST | /keyevent | 按键 | Bearer |
| POST | /launch | 启动 APP | Bearer |
| GET | /current_app | 获取当前 APP | Bearer |
| POST | /disconnect | 断开连接 | Bearer |

### 认证示例

```bash
# 认证（需要 client.p12 证书）
curl -X POST https://192.168.1.100:8443/auth \
  --cert client.p12:changeit123 \
  --cert-type P12 \
  -H "Content-Type: application/json" \
  -d '{"pin": "123456"}'

# 响应
{
  "success": true,
  "token": "pt_xxxxxxxxxx...",
  "expires_in": 1800,
  "device_fingerprint": "..."
}
```

### 操作示例

```bash
# 截屏
curl -X POST https://192.168.1.100:8443/screenshot \
  -H "Authorization: Bearer pt_xxxxxxxxxx..."

# 点击 (500, 1000)
curl -X POST https://192.168.1.100:8443/tap \
  -H "Authorization: Bearer pt_xxxxxxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"x": 500, "y": 1000}'

# 滑动
curl -X POST https://192.168.1.100:8443/swipe \
  -H "Authorization: Bearer pt_xxxxxxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"startX": 500, "startY": 1000, "endX": 500, "endY": 1500, "duration": 300}'

# 输入文本
curl -X POST https://192.168.1.100:8443/type \
  -H "Authorization: Bearer pt_xxxxxxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"text": "hello"}'

# 按键 (BACK/HOME/RECENT)
curl -X POST https://192.168.1.100:8443/keyevent \
  -H "Authorization: Bearer pt_xxxxxxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"key": "BACK"}'

# 启动应用
curl -X POST https://192.168.1.100:8443/launch \
  -H "Authorization: Bearer pt_xxxxxxxxxx..." \
  -H "Content-Type: application/json" \
  -d '{"app": "com.tencent.mm"}'

# 获取当前应用
curl -X GET https://192.168.1.100:8443/current_app \
  -H "Authorization: Bearer pt_xxxxxxxxxx..."
```

### 响应格式

成功:
```json
{
  "success": true,
  "message": "...",
  "timestamp": 1234567890
}
```

失败:
```json
{
  "error": "error_code",
  "message": "错误描述",
  "timestamp": 1234567890
}
```

敏感操作拦截:
```json
{
  "error": "sensitive_operation_blocked",
  "message": "检测到敏感应用: 支付宝",
  "timestamp": 1234567890
}
```

## 安全说明

### mTLS 双向认证

- 服务器使用 server.crt + server.key 证明身份
- 客户端使用 client.p12 证书证明身份
- 双方都验证对方证书由 CA 签发

### 证书导出

在主界面可以导出证书文件：
- `ca.crt` - CA 证书（客户端需导入）
- `client.p12` - 客户端证书（连接时使用）

### 设备指纹

每个设备有唯一指纹，认证时可验证：
- Android ID
- Build.FINGERPRINT
- 设备序列号

### 敏感操作拦截

检测以下场景时自动拒绝操作：
- 高风险 APP（支付宝、微信支付、银行 APP）
- 界面包含敏感关键词（支付密码、验证码、转账）
- 密码输入操作

## 项目结构

```
phone-agent-android/
├── app/
│   └── src/main/
│       ├── java/com/autoglm/agent/
│       │   ├── MainActivity.kt          # 主界面
│       │   ├── PhoneAgentApp.kt         # Application 类
│       │   ├── server/
│       │   │   ├── AgentHttpServer.kt   # HTTP 服务
│       │   │   ├── AuthManager.kt       # 认证管理
│       │   │   ├── SessionManager.kt    # 会话管理
│       │   │   ├── SensitiveGuard.kt     # 敏感检测
│       │   │   └── AuditLogger.kt       # 审计日志
│       │   ├── service/
│       │   │   ├── ControlAccessibilityService.kt  # 无障碍服务
│       │   │   └── AgentForegroundService.kt       # 前台服务
│       │   ├── capture/
│       │   │   ├── ScreenCaptureManager.kt         # 截屏管理
│       │   │   └── CapturePermissionActivity.kt    # 截屏权限
│       │   ├── security/
│       │   │   ├── MTLSHandler.kt        # mTLS 处理
│       │   │   ├── CertificateGenerator.kt # 证书生成
│       │   │   └── DeviceFingerprint.kt  # 设备指纹
│       │   └── util/
│       │       ├── AppPackageMapper.kt   # APP 包名映射
│       │       └── Constants.kt          # 常量定义
│       └── res/
│           ├── layout/                   # 布局文件
│           ├── xml/                      # 无障碍配置
│           └── values/                   # 资源文件
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 常见问题

### Q: 无障碍服务无法开启？
A: 部分设备需要在设置中搜索"无障碍"找到入口。确保关闭了其他无障碍服务后重试。

### Q: 截屏权限被拒绝？
A: 某些设备（如华为、小米）可能需要额外设置允许悬浮窗或应用锁。

### Q: 服务启动后手机无法连接？
A: 检查防火墙设置，确保手机和电脑在同一局域网。如果使用 HTTPS，确保客户端导入了 CA 证书。

### Q: 手势操作没有反应？
A: 
1. 确保无障碍服务已开启
2. 检查是否在敏感界面被拦截
3. 某些设备可能需要开启"模拟点击"权限

## 许可证

MIT License

## 联系方式

如有问题，请提交 Issue。
# Build test Sat Jun  6 14:02:26 CST 2026
