"""Compile the domain-only part of AdGuard's mobile filter for DNS use."""
import argparse
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from compile_rules import domain


def compile_mobile(text, baseline=""):
    blocked, allowed = set(), set()
    exact_allowed = set()
    skipped_blocking = 0
    skipped_exceptions = 0
    for raw in baseline.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("!"):
            allowed.add(domain(line[1:]))
        elif line.startswith("~"):
            exact_allowed.add(domain(line[1:]))
        else:
            blocked.add(domain(line))

    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("!", "#", "[")):
            continue
        if line.startswith("||") and line.endswith("^"):
            candidate = line[2:-1]
            try:
                blocked.add(domain(candidate))
            except ValueError:
                skipped_blocking += 1
            continue
        if line.startswith("@@||"):
            if "$generichide" in line:
                skipped_exceptions += 1
                continue
            match = re.match(r"@@\|\|([A-Za-z0-9.-]+)(?=[^A-Za-z0-9.-]|$)", line)
            if match:
                try:
                    value = domain(match.group(1))
                    if "$" in line or "/" in line or "|" in line:
                        exact_allowed.add(value)
                    else:
                        allowed.add(value)
                except ValueError:
                    skipped_exceptions += 1
            else:
                skipped_exceptions += 1
            continue
        if line.startswith("@@"):
            skipped_exceptions += 1
        elif line.startswith("||"):
            skipped_blocking += 1

    entries = sorted(blocked | {"!" + value for value in allowed} | {"~" + value for value in exact_allowed})
    return ("# QuietGuard DNS domain subset. See bundled ad-rules-license.txt and RULE-SOURCES.md for source details.\n"
            + "\n".join(entries) + "\n"), {
                "blocked": len(blocked), "allowed": len(allowed), "exactAllowed": len(exact_allowed), "entries": len(entries),
                "skippedBlocking": skipped_blocking, "skippedExceptions": skipped_exceptions,
            }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    text, counts = compile_mobile(args.input.read_text(encoding="utf-8-sig"), args.baseline.read_text(encoding="utf-8"))
    args.output.write_text(text, encoding="ascii", newline="\n")
    print(counts)


if __name__ == "__main__":
    main()
