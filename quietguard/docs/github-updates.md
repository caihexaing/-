# GitHub 发布与 APK 更新

仓库 caihexaing/- 中 quietguard/ 是净屏，根目录为已有的行程助手；不要将两者混用。

## 已安装用户

先覆盖安装 0.4.2 构建 8 一次，之后可在设置中点击“检查更新”，同意访问 GitHub 后开启每天一次的启动检查。新版本会显示版本号和发布说明；点击“立即更新”在 APK 内下载，显示进度条、字节数及速度，可取消、收起、失败重试。完成校验后点击“安装更新”，首次需要授予净屏安装应用的系统权限，再由系统确认安装。开屏跳过仍需额外的系统无障碍授权。检查和下载期间自动暂时断开净屏自己的 VPN，结束、取消或失败后按原状态恢复；安装替换后也会检查恢复。更新中手动暂停防护优先。旧版 0.4.2 构建 7 不识别同版本名修订，需要手动覆盖安装构建 8。

如果手机无法连接 GitHub，检查或下载会失败，可稍后重试。下载由用户主动发起，发起后系统可在后台继续；再次回到净屏恢复显示。没有静默安装或手机关机时检查功能。检查请求不上传已安装应用列表。

## 发布下一版

1. 修改 quietguard/ 下源码，并在 quietguard/app/build.gradle 同时增加 versionCode（整数，必须比旧版大）和 versionName（三段数字，例如 0.4.3）。
2. 在 quietguard/RELEASE_NOTES.md 写本次说明，提交代码并推送。
3. 创建与 versionName 一致的标签，例如 quietguard-v0.4.3，再推送该标签。
4. 工作流“发布净屏 APK”自动编译、核心测试、Lint、校验签名，创建 Release 并上传 QuietGuard-0.4.3.apk，然后自动提交 quietguard/update.json（含 SHA-256），手机据此显示和校验新版本。
5. 在 GitHub Actions 中确认成功；手机点击“检查更新”验证。如果未发布或工作流失败，APP 不会显示新版。

```bash
git add quietguard
git commit -m "QuietGuard: update to 0.4.3"
git push origin master
git tag quietguard-v0.4.3
git push origin quietguard-v0.4.3
```

0.4.2 构建 8 按用户要求基于 0.4.1 重新实现更新 VPN 流程，保持版本名并提高版本码；旧构建 7 源码标签已另行保留，0.4.2 标签和附件指向修订版。后续优先增加正常版本名，同名修订必须严格提高 versionCode；工作流在替换附件前确认版本码递增，防止重跑覆盖已发布哈希。

不要只修改代码却不发布，也不要把 versionName 改成后缀测试版。客户端仅识别 quietguard-vX.Y.Z 的正式发布，通过独立 quietguard/update.json 文件提供版本信息，要求附件名精确匹配 QuietGuard-X.Y.Z.apk。净屏 Release 不设置为整个仓库的 Latest，避免影响原项目的更新渠道。

## 签名

为兼容首次测试版，当前继续使用原测试证书。构建密钥仅存储在 GitHub Actions 加密 Secret QUIETGUARD_KEYSTORE_BASE64 中，未提交到公开仓库。不要删除或更换，否则新 APK 不能覆盖安装。该原型仍使用调试签名，不是商店正式发行方案；正式签名迁移需另行设计。不要下载并覆盖密钥，也不要将它写进源码、日志或 Release。

仅工作流有 contents:write 权限，拉取请求不会运行发布流程。标签应指向已经审查的主分支提交。
