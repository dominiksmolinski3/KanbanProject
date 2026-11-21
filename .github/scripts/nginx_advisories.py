#!/usr/bin/env python3
from __future__ import annotations

import html
import re
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ADVISORIES = "https://nginx.org/en/security_advisories.html"
TIMEOUT = 30
# The page has listed over seventy entries since 2024; far fewer means it was not parsed.
MIN_ENTRIES = 50

Version = tuple[int, ...]


@dataclass(frozen=True)
class Advisory:
    title: str
    severity: str
    cves: tuple[str, ...]
    vulnerable: str
    not_vulnerable: str


def parse_version(text: str) -> Version:
    if not re.fullmatch(r"\d+(\.\d+)+", text):
        raise ValueError(f"not an nginx version: {text!r}")
    return tuple(int(part) for part in text.split("."))


def advisories(page: str) -> list[Advisory]:
    found = []
    for item in re.findall(r"<li><p>(.*?)</p></li>", page, re.S):
        lines = [html.unescape(re.sub(r"<[^>]+>", "", line)).strip()
                 for line in re.split(r"<br\s*/?>", item)]
        fields = dict(line.split(": ", 1) for line in lines if ": " in line)
        missing = {"Severity", "Vulnerable", "Not vulnerable"} - fields.keys()
        if missing:
            raise ValueError(f"advisory {lines[0]!r} has no {', '.join(sorted(missing))}")
        found.append(Advisory(
            title=lines[0],
            severity=fields["Severity"].lower(),
            cves=tuple(dict.fromkeys(re.findall(r"CVE-\d{4}-\d+", item))),
            vulnerable=fields["Vulnerable"],
            not_vulnerable=fields["Not vulnerable"]))
    if len(found) < MIN_ENTRIES:
        raise ValueError(f"read {len(found)} advisories from {ADVISORIES}, expected at least {MIN_ENTRIES}")
    return found


def in_vulnerable_range(version: Version, spec: str) -> bool:
    if spec.startswith("nginx/Windows"):
        return False
    if spec == "all":
        return True
    for part in spec.split(", "):
        low, _, high = part.partition("-")
        if parse_version(low) <= version <= parse_version(high or low):
            return True
    return False


def is_fixed(version: Version, spec: str) -> bool:
    if spec == "none":
        return False
    fixes = []
    for part in spec.split(", "):
        if not part.endswith("+"):
            raise ValueError(f"unknown 'Not vulnerable' entry: {part!r}")
        fixes.append(parse_version(part[:-1]))
    # Only the newest "X.Y.Z+" covers every later branch; the others cover their own branch.
    return version >= max(fixes) or any(version[:2] == fix[:2] and version >= fix for fix in fixes)


def applicable(version: Version, entries: list[Advisory]) -> list[Advisory]:
    return [entry for entry in entries
            if entry.severity != "none"
            and in_vulnerable_range(version, entry.vulnerable)
            and not is_fixed(version, entry.not_vulnerable)]


def fetch(source: str) -> str:
    if not source.startswith("https://"):
        return Path(source).read_text(encoding="utf-8")
    request = urllib.request.Request(source, headers={"User-Agent": "nginx-advisory-check"})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return response.read().decode("utf-8")


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        raise SystemExit("usage: nginx_advisories.py <nginx version> [advisories page or file]")
    version = parse_version(argv[1])
    hits = applicable(version, advisories(fetch(argv[2] if len(argv) == 3 else ADVISORIES)))
    if not hits:
        print(f"nginx {argv[1]} is not in the range of any advisory on {ADVISORIES}")
        return 0
    for hit in hits:
        cves = ", ".join(hit.cves) or "no CVE"
        print(f"::error::nginx {argv[1]} - {cves}: {hit.title} (severity {hit.severity}); "
              f"vulnerable {hit.vulnerable}, fixed in {hit.not_vulnerable}")
    print(f"nginx {argv[1]} is affected by {len(hits)} advisories on {ADVISORIES}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
