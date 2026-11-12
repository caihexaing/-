"""Compile bundled DNS-specialised ad filters into the APK domain asset.

Offline only: reads local snapshots, never fetches or activates rules at runtime.
Output keeps the runtime format understood by RuleSet:
  bare host   -> block host and all subdomains
  !host       -> allow host and all subdomains (exception)
  ~host       -> allow that exact host only (unused by this compiler)
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from compile_rules import MAX_BYTES, MAX_ENTRIES, domain  # noqa: E402

HEADER = ("# QuietGuard DNS domain subset. See bundled ad-rules-license.txt "
          "and RULE-SOURCES.md for source details.")

HOSTS_PREFIXES = {"0.0.0.0", "127.0.0.1", "::", "::1"}

# Rules scoped to a path, app, client or element cannot be expressed as DNS blocks.
SKIP_MODIFIERS = (
    "badfilter", "domain=", "app=", "denyallow=", "client=", "dnsrewrite",
    "removeparam", "csp=", "cookie", "redirect", "popup", "elemhide",
    "generichide", "genericblock", "specifichide", "jsinject", "content",
    "extension", "replace", "urltransform", "download", "urlblock",
)

# Product-critical hosts. A broad upstream list that blocks one of these would break
# the apps this VPN protects, so compilation fails instead of shipping it.
SAFETY_DOMAINS = (
    "weixin.qq.com", "api.weixin.qq.com", "qq.com", "gtimg.cn", "wx.qq.com",
    "alipay.com", "alipayobjects.com", "taobao.com", "tmall.com", "aliyun.com",
    "alicdn.com", "jd.com", "jd.hk", "360buyimg.com",
    "baidu.com", "bdstatic.com", "bilibili.com", "hdslb.com", "bilivideo.com",
    "douyin.com", "douyinvod.com", "bytedance.com", "bytednsdoc.com",
    "xiaohongshu.com", "xhscdn.com", "kuaishou.com", "kwimgs.com",
    "mi.com", "xiaomi.com", "hicloud.com", "heytapmobi.com", "oppomobile.com",
    "hihonor.com", "huawei.com", "vivo.com.cn", "iqiyi.com", "youku.com",
    "qqmusic.qq.com", "music.163.com", "126.net", "zhihu.com", "zhimg.com",
    "weibo.cn", "sinaimg.cn", "apple.com", "icloud.com", "google.com",
    "android.com", "gstatic.com", "github.com", "microsoft.com",
)


def host_from(body):
    """Return (host, skip_reason). Only host-shaped rules are accepted."""
    if body.startswith("||"):
        rest = body[2:]
    elif body.startswith("|"):
        # Anchored to the whole host name, e.g. |example.com^| — still just a host.
        rest = body[1:]
    elif body[:1] in ("*", "&", "+", "."):
        return None, "pattern"
    else:
        rest = body
    if "$" in rest:
        host, modifiers = rest.split("$", 1)
        lowered = modifiers.lower()
        if any(token in lowered for token in SKIP_MODIFIERS):
            return None, "modifier"
    else:
        host = rest
    if "##" in host or "#?#" in host or "#@#" in host:
        return None, "cosmetic"
    if "/" in host or "*" in host:
        return None, "pathOrWildcard"
    host = host.strip("^|")
    if not host:
        return None, "empty"
    try:
        return domain(host), None
    except ValueError:
        return None, "invalid"


def parse_source(text):
    blocked, exceptions = set(), set()
    skipped = {"pattern": 0, "modifier": 0, "cosmetic": 0, "pathOrWildcard": 0, "invalid": 0}
    for raw in text.splitlines():
        line = raw.strip().lstrip("\ufeff")
        if not line or line[0] in "!#[":
            continue
        if line.startswith("/") and line.rstrip("/") != line:
            continue  # regular expression rule
        if line.split()[0] in HOSTS_PREFIXES and len(line.split()) > 1:
            for value in line.split("#", 1)[0].split()[1:]:
                try:
                    blocked.add(domain(value))
                except ValueError:
                    skipped["invalid"] += 1
            continue
        exception = line.startswith("@@")
        host, reason = host_from(line[2:] if exception else line)
        if reason:
            # Exceptions are honoured at host level even when they carry modifiers,
            # so a dropped exception can never silently broaden blocking.
            skipped[reason] += 1
            continue
        (exceptions if exception else blocked).add(host)
    return blocked, exceptions, skipped


def compile_ruleset(sources):
    blocked, exceptions, report = set(), set(), {}
    for name, text in sources:
        source_blocked, source_exceptions, skipped = parse_source(text)
        report[name] = {
            "blocked": len(source_blocked),
            "exceptions": len(source_exceptions),
            **skipped,
        }
        blocked |= source_blocked
        exceptions |= source_exceptions

    merged_blocked = len(blocked)
    # An exception covering a host makes the corresponding block a no-op.
    blocked = {host for host in blocked if not covered_by(exceptions, host)}
    shadowed = merged_blocked - len(blocked)
    # A blocked parent already covers its children.
    pruned = prune_children(blocked)
    report["_summary"] = {
        "blockedMerged": merged_blocked,
        "blockedAfterExceptions": len(blocked),
        "droppedByException": shadowed,
        "droppedAsChild": pruned,
        "exceptions": len(exceptions),
    }

    unsafe = sorted(Safety.hits(blocked))
    if unsafe:
        raise SystemExit("safety domains would be blocked: " + ", ".join(unsafe))
    total = len(blocked) + len(exceptions)
    if not blocked or total > MAX_ENTRIES:
        raise SystemExit("empty or oversized rule set")
    lines = [HEADER] + sorted(blocked) + sorted("!" + host for host in exceptions)
    data = ("\n".join(lines) + "\n").encode("ascii")
    if len(data) > MAX_BYTES:
        raise SystemExit("compiled byte limit exceeded")
    report["_summary"].update({"bytes": len(data), "entries": total})
    return data, report


def covered_by(exceptions, host):
    probe = host
    while True:
        if probe in exceptions:
            return True
        dot = probe.find(".")
        if dot < 0:
            return False
        probe = probe[dot + 1:]


def prune_children(blocked):
    dropped = 0
    for host in list(blocked):
        probe = host[host.find(".") + 1:] if "." in host else ""
        while probe:
            if probe in blocked:
                blocked.discard(host)
                dropped += 1
                break
            probe = probe[probe.find(".") + 1:] if "." in probe else ""
    return dropped


class Safety:
    @staticmethod
    def hits(blocked):
        return {host for host in SAFETY_DOMAINS if covered_by(blocked, host)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="append", required=True,
                        help="path=URL of a local snapshot")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--meta", type=Path, required=True)
    parser.add_argument("--counts", type=Path,
                        help="tiny key=value asset so the UI need not parse the full list")
    args = parser.parse_args()
    sources = []
    urls = {}
    for spec in args.source:
        path, _, url = spec.partition("=")
        sources.append((Path(path).name, Path(path).read_text(encoding="utf-8-sig", errors="replace")))
        urls[Path(path).name] = url
    data, report = compile_ruleset(sources)
    args.output.write_bytes(data)
    summary = report.pop("_summary")
    meta = {
        "blocked": summary["entries"] - summary["exceptions"],
        "exceptions": summary["exceptions"],
        "bytes": summary["bytes"],
        "sources": urls,
        "detail": report,
    }
    args.meta.write_text(json.dumps(meta, ensure_ascii=True, sort_keys=True, indent=2) + "\n",
                         encoding="ascii", newline="\n")
    if args.counts:
        args.counts.write_text(
            "blocked=%d\nexceptions=%d\nbytes=%d\n" % (
                meta["blocked"], meta["exceptions"], meta["bytes"]),
            encoding="ascii", newline="\n")
    print(json.dumps({"blocked": meta["blocked"], "exceptions": meta["exceptions"],
                      "bytes": meta["bytes"], **summary}, sort_keys=True))


if __name__ == "__main__":
    main()
