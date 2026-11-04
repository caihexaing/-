# 离线规则编译与验证工具

这些文件由 Codex 负责；VPN、Android 下载/存储适配与状态同步由 DeepSeek 接入。协议和交互规范见 `docs/rules-update-protocol.md`。当前没有把远程规则接入运行时，也没有发布新的 APK。

## 编译

输入必须是已审核并保存许可证的本地文本。本工具不会联网，不会激活规则，不保存签名私钥。

```powershell
python tools/rules/compile_rules.py --input hosts --source-url https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts --license-file license.txt --version 2026110401 --min-app-code 17 --output candidate-2026110401
```

输出 `rules.txt`、`rules.meta`、`sources.json`、`LICENSE.upstream`。版本为正整数且递增；输出目录必须不存在。`signed:false` 意味着只是候选，不能作为远程更新直接激活。

转换支持：纯 ASCII 域名、hosts 的 `0.0.0.0/127.0.0.1/::` 行、无修饰符 `||domain^` 和 `@@||domain^`。输出排序去重，例外写为 `!domain`，由运行时优先匹配并包含子域名。跳过不支持的拦截规则并计数；遇到不支持的例外则拒绝整份输入，防止丢失例外而扩大拦截。

不把 EasyList 的 URL、脚本、页面元素规则当域名。2026-11-04取得的 AdGuard DNS filter 原文含路径正则及非本工具支持的例外，已明确拒绝该来源，未以删除例外的方式强行转换。第一份验证候选改用 StevenBlack 默认 hosts，产出77,758条去重域名；14条未支持行记录为跳过。候选未启用，数量不等于可清除的广告数量。上游原文、来源元数据与许可证需同时保留；该来源的聚合项目说明和各来源声明也须随发布流程审查、保留，不能把根许可证视为对全部聚合来源的唯一说明。

## 签名

建立独立于 APK 签名的离线 RSA 规则签名密钥（2048–4096位），将 X.509 DER 格式公钥固化进客户端。私钥不进入仓库、APP或候选包。发布前用离线密钥对 `rules.meta` 原始字节做 SHA256withRSA/PKCS#1 v1.5 签名，生成原始二进制 `rules.meta.sig`。

例如在已配置 OpenSSL 的离线签名环境：

```text
openssl dgst -sha256 -sign /private/rules-signing.pem -out candidate-2026110401/rules.meta.sig candidate-2026110401/rules.meta
```

不在此工具中创建生产密钥；密钥配置是发布前必需的明确步骤。`BundleChecks` 使用临时内存测试密钥，不能把它当生产签名或持久信任根。缺少生产公钥与有效签名时，APP必须继续使用内置/已验证规则。

## 验证

```powershell
python -m unittest discover -s tools/rules -p 'test_*.py' -v
javac -encoding UTF-8 -d build/rules-checks app/src/main/java/cn/quietguard/app/rules/RuleBundleVerifier.java tools/rules/BundleChecks.java
java -cp build/rules-checks cn.quietguard.app.rules.BundleChecks
# 可选最后追加候选目录，再验证 Python 生成的实际元数据和规则；仍只使用内存测试密钥。
```

Java 模块不依赖 Android 或第三方库，可由 Android API26+调用。请在后台线程中执行；大规则的解析与哈希不应阻塞界面。现有 `test-core.ps1`、CI、MainActivity、FilterVpnService 未由本交付修改；集成时由 DeepSeek 加入检查入口。
