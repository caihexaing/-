# GitHub 发布与 APK 更新

仓库 caihexaing/- 中 quietguard/ 是净屏，根目录为已有的行程助手；不要将两者混用。

## 已安装用户

0.1.0 不含检查更新功能，需要先手动安装 0.2.0 一次。0.2.0 在设置中点击“检查更新”，同意访问 GitHub 后开启自动检查；每天首次启动时检查一次，也可关闭自动检查。发现新版本会显示版本号和发布说明；下载由浏览器执行，安装需用户确认。

如果手机无法连接 GitHub，检查或下载会失败，可稍后重试。没有静默安装、自动后台下载或手机关机时检查功能。检查请求不上传已安装应用列表。

## 发布下一版

1. 修改 quietguard/ 下源码，并在 quietguard/app/build.gradle 同时增加 versionCode（整数，必须比旧版大）和 versionName（三段数字，例如 0.2.1）。
2. 在 quietguard/RELEASE_NOTES.md 写本次说明，提交代码并推送。
3. 创建与 versionName 一致的标签，例如 quietguard-v0.2.1，再推送该标签。
4. 工作流“发布净屏 APK”自动编译、核心测试、Lint、校验签名，创建 Release 并上传 QuietGuard-0.2.1.apk。
5. 在 GitHub Actions 中确认成功；手机点击“检查更新”验证。如果未发布或工作流失败，APP 不会显示新版。

```bash
git add quietguard
git commit -m "QuietGuard: update to 0.2.1"
git push origin master
git tag quietguard-v0.2.1
git push origin quietguard-v0.2.1
```

不要只修改代码却不发布，也不要把 versionName 改成后缀测试版。客户端仅识别 quietguard-vX.Y.Z 的正式发布，忽略 Draft、Prerelease 及行程助手的版本，要求附件名精确匹配 QuietGuard-X.Y.Z.apk。净屏 Release 不设置为整个仓库的 Latest，避免影响原项目的更新渠道。

## 签名

为兼容首次测试版，当前继续使用原测试证书。构建密钥仅存储在 GitHub Actions 加密 Secret QUIETGUARD_KEYSTORE_BASE64 中，未提交到公开仓库。不要删除或更换，否则新 APK 不能覆盖安装。该原型仍使用调试签名，不是商店正式发行方案；正式签名迁移需另行设计。不要下载并覆盖密钥，也不要将它写进源码、日志或 Release。

仅工作流有 contents:write 权限，拉取请求不会运行发布流程。标签应指向已经审查的主分支提交。
