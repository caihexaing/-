"""Offline DNS subset compiler. Does not fetch, activate rules, or hold signing keys."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import re

MAX_BYTES = 8 * 1024 * 1024
MAX_ENTRIES = 500_000

def domain(value):
    value = value.lower().rstrip('.')
    if not value.isascii() or len(value) > 253 or '.' not in value:
        raise ValueError('expected ASCII FQDN')
    try:
        ipaddress.ip_address(value)
    except ValueError:
        pass
    else:
        raise ValueError('IP rules unsupported')
    for label in value.split('.'):
        if not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', label):
            raise ValueError('invalid DNS label')
    if value.rsplit('.', 1)[1].isdigit():
        raise ValueError('numeric suffix unsupported')
    return value

def compile_text(text):
    blocked, allowed = set(), set()
    skipped = 0
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(('!', '#', '[')):
            continue
        exception = line.startswith('@@')
        candidate = line[2:] if exception else line
        try:
            if candidate.startswith('||') and candidate.endswith('^'):
                values = [domain(candidate[2:-1])]
            elif not exception and len(candidate.split()) >= 2 and candidate.split()[0] in ('0.0.0.0', '127.0.0.1', '::'):
                values = [domain(x) for x in candidate.split('#',1)[0].split()[1:]]
            elif not exception:
                values = [domain(candidate)]
            else:
                raise ValueError('unsupported exception')
        except ValueError:
            # Dropping an exception could broaden blocking: refuse that source instead.
            if exception:
                raise ValueError(f'unsupported exception at line {number}') from None
            skipped += 1
            continue
        (allowed if exception else blocked).update(values)
    if not blocked or len(blocked) + len(allowed) > MAX_ENTRIES:
        raise ValueError('empty or oversized rule set')
    lines = sorted(blocked | {'!' + x for x in allowed})
    data = ('\n'.join(lines) + '\n').encode('ascii')
    if len(data) > MAX_BYTES:
        raise ValueError('compiled byte limit exceeded')
    return data, {'blocked': len(blocked), 'exceptions': len(allowed), 'entries': len(lines), 'skippedBlockingLines': skipped}

def manifest(version, app_code, rules, sources, entries):
    if version <= 0 or app_code <= 0 or not 0 < entries <= MAX_ENTRIES:
        raise ValueError('invalid version or entry count')
    if version > 2**63-1 or app_code > 2**31-1 or len(rules) > MAX_BYTES:
        raise ValueError('metadata limits exceeded')
    return (f'format=quietguard-dns-v1\nversion={version}\nminAppVersionCode={app_code}\n'
            f'bytes={len(rules)}\nsha256={hashlib.sha256(rules).hexdigest()}\nentries={entries}\n'
            f'sourcesSha256={hashlib.sha256(sources).hexdigest()}\n').encode('ascii')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--source-url', required=True)
    parser.add_argument('--license-file', type=Path, required=True)
    parser.add_argument('--version', type=int, required=True)
    parser.add_argument('--min-app-code', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('output must be a new directory')
    raw = args.input.read_bytes()
    if len(raw) > 16 * 1024 * 1024:
        raise SystemExit('source exceeds 16 MiB')
    license_bytes = args.license_file.read_bytes()
    rules, counts = compile_text(raw.decode('utf-8-sig'))
    sources = (json.dumps({'schema': 1, 'sourceUrl': args.source_url,
        'sourceSha256': hashlib.sha256(raw).hexdigest(),
        'licenseFile': 'LICENSE.upstream', 'licenseSha256': hashlib.sha256(license_bytes).hexdigest(),
        'conversion': 'DNS subset only; exceptions override suffix blocks', **counts},
        ensure_ascii=True, sort_keys=True, indent=2) + '\n').encode('ascii')
    meta = manifest(args.version, args.min_app_code, rules, sources, counts['entries'])
    args.output.mkdir(parents=True)
    for name, data in [('rules.txt',rules), ('sources.json',sources), ('rules.meta',meta), ('LICENSE.upstream',license_bytes)]:
        (args.output / name).write_bytes(data)
    print(json.dumps({**counts, 'bytes':len(rules), 'signed':False}))

if __name__ == '__main__':
    main()
