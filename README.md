# 行程助手（Android 首版）

这是一个私人侧载的 Android Kotlin/Compose 应用，用于查询 12306 官方真实车次、保存一条本地行程并在开售时辅助操作。它不会保存 12306 密码、证件号、手机号或支付信息，也不绕过验证码、风控、登录和支付确认。

开发构建产物：

`app/build/outputs/apk/debug/app-debug.apk`

当前版本为 `0.3.2-diagnostic`（`versionCode` 14），支持 Android 8.0（API 26）及以上，目标 Android 16（API 36）。APK 包名为 `com.example.ticketassistant`。

## 构建

需要 Android Studio、JDK 17、Android SDK 36。使用 Android Studio 打开仓库根目录后选择 `Build > Build APK(s)`。也可以在已安装 Gradle 的环境执行：

```text
gradle assembleDebug
```

正式发布包必须使用固定签名：复制 `release-signing.properties.example` 为 `release-signing.properties`，填入不入库的 keystore 路径和密码；也可以设置 `TICKET_RELEASE_STOREFILE`、`TICKET_RELEASE_STOREPASSWORD`、`TICKET_RELEASE_KEYALIAS`、`TICKET_RELEASE_KEYPASSWORD` 环境变量。未配置签名时，`assembleRelease` 会明确失败。

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。项目已提供 `gradlew` 和 `gradlew.bat`。Windows 上需要在 ASCII 路径中执行构建，或保留 `gradle.properties` 里的 `android.overridePathCheck=true`，避免 Android Gradle Plugin 因项目路径含中文而中止。

## 安装

将 APK 传到手机后打开安装，按系统提示允许安装未知来源应用。首次打开时授予通知权限；未开售任务必须授权精确闹钟，建议同时取消电池优化。官方 12306 App 必须由用户自行安装和登录。

## 使用前提

1. 设备安装官方 12306 App，并由用户自行保持登录。
2. 首次运行按系统提示授予通知权限；未开售任务还需精确闹钟权限，建议解除电池优化；无障碍服务为可选实验能力。
3. 可靠模式下，开售前保持设备已解锁、屏幕可用。验证码、滑块、身份核验、候补协议、页面改版和支付始终由用户接管。

查询接口会复用官方会话，再读取 `leftTicket/queryG` 返回的真实数据；会话失效时重新初始化，正常轮询间隔为 15～30 秒，遇到限流会停止。网络失败或响应格式变化时显示错误，不以模拟车次兜底。

保存任务时会重新查询确认开售状态。已确认所选席别有票时无需填写开售时间并立即执行；确认尚未开售时才要求填写未来的 `yyyy-MM-dd HH:mm`（统一 `Asia/Shanghai`）；状态不明确时禁止启用。旧版本任务会进入草稿，不能静默执行。

开售闹钟会启动前台执行服务，重新查询并匹配目标车次和席别，然后唤起官方 App。开启无障碍服务后，App 会先验证官方 12306 的日期、路线、车次和席别，再辅助选择唯一同名成人乘车人，并在订单字段匹配时最多发送一次“提交订单”点击；识别到订单号、乘车日期、车次、席别、乘车人和待支付金额后进入待支付状态并停止。验证码、登录失效、身份核验、风控、候补和支付由用户手动处理。遇到弹窗、首页、未知页面、页面节点为空或提交结果不明会停止并通知用户，不绕过官方安全控制。`0.3.2-diagnostic` 会在任务页显示无障碍事件、页面状态、最近动作和提交锁，并可导出不含账号/证件/支付信息的诊断报告；它仍未经过真实 12306 端到端验证，不保证自动生成订单。
