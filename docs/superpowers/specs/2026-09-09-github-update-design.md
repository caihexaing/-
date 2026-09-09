# GitHub APK 更新提醒设计

## 目标

每次发布新 APK 后，用户打开应用或从后台回到应用时，应用检查 GitHub Release；当远程版本高于本地版本且 Release 附带 APK 时，立即显示更新对话框。下载由 Android `DownloadManager` 执行，以便利用系统的网络调度、重试和中断续传能力。

## 发布流程

每次代码更新递增 Android `versionCode`/`versionName`，构建 debug APK，并在 `caihexaing/-` 创建对应的 `v<versionName>` Release，上传 APK 资产。当前已发布版本为 `0.2.1`，待构建发布版本为 `0.2.2`。

## 客户端行为

更新检查继续使用 GitHub `releases/latest` 接口，并要求版本号高于本地版本、资产名称以 `.apk` 结尾。检查挂在 Activity 的 `ON_START` 生命周期事件，覆盖首次打开和从后台回到前台；网络错误或没有更新时保持静默。

下载目标位于应用专用外部下载目录。完成后会校验 GitHub 资产大小、GitHub 提供时的 SHA-256、包名、版本号高于已安装版本，以及 APK 签名与当前应用一致，才交给系统安装器。未知来源安装权限、网络/存储下载失败、校验失败和安装器拒绝会向用户显示不同原因。

## 限制

APK 使用 debug 签名，仅用于私人侧载。GitHub 发布需要当前登录账号对目标仓库具有 Release 写权限。
