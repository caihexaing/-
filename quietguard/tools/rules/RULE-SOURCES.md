# Bundled DNS Rules

`app/src/main/assets/ad-domains.txt` contains 280,602 blocked domains and 339 host exceptions, compiled from four DNS-specialised upstream filter lists plus the curated seed list. `app/src/main/assets/ad-domains.counts` carries the same totals as a tiny `key=value` asset so the UI can show rule coverage without parsing the 5.7 MB list on the main thread.

Sources, all snapshotted into `tools/rules/vendor/dns/` and recorded with digests in `tools/rules/ad-domains.meta.json`:

| File | Upstream | License | SHA-256 |
| --- | --- | --- | --- |
| `agdns.txt` | `https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt` | GPLv3 | `b4b493903e1f4aa0e4ed6b22c7eb1e1b6e32012195f7a361aa4f713421558591` |
| `oisd.txt` | `https://small.oisd.nl/domainswild2` | GPLv3 | `f7db987a998eea20c925be703513bedc1f92b5467f43f4ab6ed848316f7807f3` |
| `antiad.txt` | `https://anti-ad.net/easylist.txt` | MIT | `57d5c8fd80e8cf75a55d4e8d2661502b0c353dbf8920fed83fe80a52b1e36e2f` |
| `adrulesdns.txt` | `https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblockdns.txt` | GPLv3 | `136e0e0bc037f8d78058507571a1a87392e853e643577a8e0cbb684f7f221452` |

License texts ship in the source tree as `vendor/dns/LICENSE-*.txt` and in the APK as `ad-rules-license.txt`, which also carries the attribution header. Preserve both when redistributing the derived rules. The list is data, not executable code.

## Regenerate

```powershell
$v = "tools\rules\vendor\dns"
python tools\rules\compile_dns_filter.py `
  --source "$v\agdns.txt=https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt" `
  --source "$v\oisd.txt=https://small.oisd.nl/domainswild2" `
  --source "$v\antiad.txt=https://anti-ad.net/easylist.txt" `
  --source "$v\adrulesdns.txt=https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblockdns.txt" `
  --source "tools\rules\quietguard-seed-domains.txt=curated" `
  --output "app\src\main\assets\ad-domains.txt" `
  --meta "tools\rules\ad-domains.meta.json" `
  --counts "app\src\main\assets\ad-domains.counts"
```

Then update the two expected totals in `tests/CoreChecks.java` and run `.\test-core.ps1`.

## Import rules

Only rules that reduce to a host name are imported, because the DNS engine matches host names and cannot express a path, a query string, or an element selector:

- `||host^`, `||host^$modifier`, `||host/…`, `|host^|`, bare hosts and `0.0.0.0 host` lines all yield the host.
- Rules carrying `domain=`, `app=`, `denyallow=`, `client=`, `badfilter`, `dnsrewrite`, `removeparam`, `csp=`, `cookie`, `redirect`, `popup`, `elemhide`, `generichide`, `genericblock`, `specifichide`, `jsinject`, `content`, `extension`, `replace`, `urltransform`, `download` or `urlblock` are skipped: their effect cannot be reproduced by blocking the host.
- Wildcards, cosmetic rules (`##`, `#?#`, `#@#`) and regular expressions are skipped.

Host exceptions (`@@`) are honoured at host level even when they carry modifiers or paths, so a dropped exception can never silently broaden blocking. Unmodified host exceptions become suffix exceptions (`!host`), which also unblock subdomains; exceptions with modifiers, paths or app scoping are treated the same way at host level because the Android VPN tunnel does not expose which selected application originated an individual DNS packet. A blocked parent already covers its children, so children of a blocked domain are pruned. An exception that covers a blocked host removes that host from the list.

Compilation fails if any domain in `SAFETY_DOMAINS` (WeChat, Alipay, Taobao, JD, Baidu, Bilibili, Douyin, Xiaohongshu, Kuaishou, Xiaomi, Huawei, vivo, OPPO, Honor, iQiyi, Youku, music and cloud providers, and the major app stores) would end up blocked, if the set is empty, or if it exceeds 500,000 entries or 8 MiB. This is the guard that keeps a broad upstream list from breaking the apps the VPN is meant to protect.

## Runtime behaviour

`RuleSet` loads the asset once per VPN session (about 160–260 ms and 35 MB heap for 280k domains on a test JVM) and answers a lookup in tens of microseconds. The list is a fixed APK snapshot: this build does not download rule updates at runtime. Optional SNI filtering is documented separately; neither DNS nor SNI filtering decrypts HTTPS, and direct-IP, application-embedded DoH, cached or same-host content can still bypass it.
