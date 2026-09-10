# 行程助手（Android 首版）

这是一个私人侧载的 Android Kotlin/Compose 首版，用于查询 12306 官方真实车次、保存一条本地行程并在开售前提醒用户。它不会保存 12306 密码、证件号、手机号或支付信息，也不绕过验证码、风控、登录和支付确认。

已生成并校验 debug APK：

`app/build/outputs/apk/debug/app-debug.apk`

当前开发版本为 `0.2.5`（`versionCode` 7），支持 Android 8.0（API 26）及以上，目标 Android 16（API 36）。APK 包名为 `com.example.ticketassistant`，使用 Android Debug 证书签名，只适合作为私人侧载测试包。

## 构建

需要 Android Studio、JDK 17、Android SDK 36。使用 Android Studio 打开仓库根目录后选择 `Build > Build APK(s)`。也可以在已安装 Gradle 的环境执行：

```text
gradle assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。项目已提供 `gradlew` 和 `gradlew.bat`。Windows 上需要在 ASCII 路径中执行构建，或保留 `gradle.properties` 里的 `android.overridePathCheck=true`，避免 Android Gradle Plugin 因项目路径含中文而中止。

## 安装

将 APK 传到手机后打开安装，按系统提示允许安装未知来源应用。首次打开时授予通知权限；如需开售提醒，还应在任务页授权精确闹钟。官方 12306 App 必须由用户自行安装和登录。

## 使用前提

1. 设备安装官方 12306 App，并由用户自行保持登录。
2. 首次运行按系统提示授予通知、精确闹钟权限；无障碍服务为可选实验能力。
3. 可靠模式下，开售前保持设备已解锁、屏幕可用。验证码、滑块、身份核验、候补协议、页面改版和支付始终由用户接管。

查询接口会先初始化官方会话，再读取 `leftTicket/queryG` 返回的真实数据；网络失败或响应格式变化时显示错误，不以模拟车次兜底。

开售闹钟会启动前台执行服务，重新查询并匹配目标车次和席别，然后唤起官方 App。开启无障碍服务后，App 会按页面结构辅助选择目标车次、席别和唯一同名成人乘车人，并在订单字段匹配时自动点击一次“提交订单”；进入待支付页面后停止。验证码、登录失效、身份核验、风控、候补和支付由用户手动处理。遇到未知页面会停止并通知用户，不绕过官方安全控制。
