#!/usr/bin/env python3
from __future__ import annotations

import html
import json
import os
import re
import subprocess
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

REPO = Path(__file__).resolve().parents[2]
TIMEOUT = 30
USER_AGENT = "component-advisory-check"

NGINX_ADVISORIES = "https://nginx.org/en/security_advisories.html"
CURL_ADVISORIES = "https://curl.se/docs/vuln.json"
OTP_ADVISORIES = "https://api.github.com/repos/erlang/otp/security-advisories?per_page=100"
POSTGRES_VERSIONS = "https://www.postgresql.org/versions.json"
TEMURIN_LATEST = "https://api.adoptium.net/v3/assets/latest/{feature}/hotspot?image_type=jre&os=linux&architecture=x64"

Version = tuple[int, ...]


def parse_version(text: str) -> Version:
    text = text.strip()
    if not re.fullmatch(r"\d+(\.\d+)*", text):
        raise ValueError(f"not a version: {text!r}")
    return tuple(int(part) for part in text.split("."))


def parse_jdk(text: str) -> Version:
    match = re.fullmatch(r"jdk-(\d+(?:\.\d+)*)\+(\d+)", text.strip())
    if not match:
        raise ValueError(f"not a Temurin release name: {text!r}")
    numbers = parse_version(match.group(1))
    return numbers + (0,) * (4 - len(numbers)) + (int(match.group(2)),)


def fetch(url: str, headers: dict[str, str] | None = None) -> tuple[str, dict[str, str]]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return response.read().decode("utf-8"), dict(response.headers)


def is_fixed(version: Version, fixes: list[Version], branch: int) -> bool:
    # Only the newest fix covers every later branch; each other fix covers its own branch.
    return bool(fixes) and (
        version >= max(fixes) or any(version[:branch] == fix[:branch] and version >= fix for fix in fixes))


def nginx_findings(version_text: str) -> list[str]:
    version = parse_version(version_text)
    page, _ = fetch(NGINX_ADVISORIES)
    entries = re.findall(r"<li><p>(.*?)</p></li>", page, re.S)
    # The page has listed over seventy entries since 2024; far fewer means it was not parsed.
    if len(entries) < 50:
        raise ValueError(f"read {len(entries)} advisories from {NGINX_ADVISORIES}, expected at least 50")
    findings = []
    for item in entries:
        lines = [html.unescape(re.sub(r"<[^>]+>", "", line)).strip() for line in re.split(r"<br\s*/?>", item)]
        fields = dict(line.split(": ", 1) for line in lines if ": " in line)
        missing = {"Severity", "Vulnerable", "Not vulnerable"} - fields.keys()
        if missing:
            raise ValueError(f"nginx advisory {lines[0]!r} has no {', '.join(sorted(missing))}")
        vulnerable, not_vulnerable = fields["Vulnerable"], fields["Not vulnerable"]
        if fields["Severity"].lower() == "none" or vulnerable.startswith("nginx/Windows"):
            continue
        if vulnerable != "all" and not any(
                parse_version(low) <= version <= parse_version(high or low)
                for low, _, high in (part.partition("-") for part in vulnerable.split(", "))):
            continue
        fixes = []
        if not_vulnerable != "none":
            for part in not_vulnerable.split(", "):
                if not part.endswith("+"):
                    raise ValueError(f"unknown nginx 'Not vulnerable' entry: {part!r}")
                fixes.append(parse_version(part[:-1]))
        if not is_fixed(version, fixes, branch=2):
            cves = ", ".join(dict.fromkeys(re.findall(r"CVE-\d{4}-\d+", item))) or "no CVE"
            findings.append(f"{cves}: {lines[0]} (severity {fields['Severity'].lower()}); "
                            f"vulnerable {vulnerable}, fixed in {not_vulnerable}")
    return findings


def osv_affected(version: Version, events: list[dict[str, str]]) -> bool:
    affected = False
    for event in events:
        (kind, value), = event.items()
        at = (0,) if value == "0" else parse_version(value)
        if at > version:
            break
        if kind == "introduced":
            affected = True
        elif kind == "fixed":
            affected = False
        elif kind == "last_affected" and at < version:
            affected = False
    return affected


def curl_findings(version_text: str) -> list[str]:
    version = parse_version(version_text)
    entries = json.loads(fetch(CURL_ADVISORIES)[0])
    if len(entries) < 100:
        raise ValueError(f"read {len(entries)} advisories from {CURL_ADVISORIES}, expected at least 100")
    findings = []
    for entry in entries:
        ranges = [r for a in entry.get("affected", []) for r in a.get("ranges", []) if r.get("type") == "SEMVER"]
        if not ranges:
            raise ValueError(f"curl advisory {entry.get('id')} has no SEMVER range")
        if any(osv_affected(version, r["events"]) for r in ranges):
            severity = entry.get("database_specific", {}).get("severity", "unrated")
            findings.append(f"{entry['id']}: {entry.get('summary', '').strip()} (severity {severity})")
    return findings


def otp_findings(version_text: str) -> list[str]:
    version = parse_version(version_text)
    token = os.environ.get("GITHUB_TOKEN")
    headers = {"Accept": "application/vnd.github+json", **({"Authorization": f"Bearer {token}"} if token else {})}
    advisories, url = [], OTP_ADVISORIES
    while url:
        body, response_headers = fetch(url, headers)
        advisories += json.loads(body)
        url = next(iter(re.findall(r'<([^>]+)>;\s*rel="next"', response_headers.get("Link", ""))), None)
    if len(advisories) < 20:
        raise ValueError(f"read {len(advisories)} advisories from {OTP_ADVISORIES}, expected at least 20")
    findings = []
    for advisory in advisories:
        entries = [v for v in advisory.get("vulnerabilities") or [] if v["package"]["name"] == "OTP"]
        if not entries:
            raise ValueError(f"OTP advisory {advisory['ghsa_id']} names no OTP version")
        # One entry per release branch, each open-ended: only the union of their fixes is meaningful.
        introduced = [parse_version(re.sub(r"^>=\s*", "", e["vulnerable_version_range"])) for e in entries]
        fixes = [parse_version(fix) for e in entries for fix in (e.get("patched_versions") or "").split(",")
                 if fix.strip()]
        if version >= min(introduced) and not is_fixed(version, fixes, branch=1):
            findings.append(f"{advisory.get('cve_id') or advisory['ghsa_id']}: {advisory['summary'].strip()} "
                            f"(severity {advisory['severity']}); fixed in "
                            f"{', '.join('.'.join(map(str, f)) for f in sorted(fixes, reverse=True)) or 'nothing yet'}")
    return findings


def postgres_findings(version_text: str) -> list[str]:
    major, minor = parse_version(version_text)
    releases = {r["major"]: r for r in json.loads(fetch(POSTGRES_VERSIONS)[0])}
    release = releases.get(str(major))
    if release is None:
        raise ValueError(f"{POSTGRES_VERSIONS} does not list PostgreSQL {major}")
    findings = []
    if not release["supported"]:
        findings.append(f"PostgreSQL {major} has been out of support since {release['eolDate']}")
    if minor < int(release["latestMinor"]):
        findings.append(f"{major}.{minor} is behind {major}.{release['latestMinor']}, released "
                        f"{release['relDate']}; every PostgreSQL minor release carries its security fixes")
    return findings


def jre_findings(version_text: str) -> list[str]:
    version = parse_jdk(version_text)
    assets = json.loads(fetch(TEMURIN_LATEST.format(feature=version[0]))[0])
    if not assets:
        raise ValueError(f"Adoptium lists no Temurin {version[0]} JRE")
    latest = max(assets, key=lambda asset: parse_jdk(asset["release_name"]))["release_name"]
    if parse_jdk(latest) > version:
        return [f"{version_text} is behind {latest}, the newest Temurin {version[0]} JRE; an update is "
                f"either the quarterly OpenJDK security release or a respin that fixes one"]
    return []


@dataclass(frozen=True)
class Component:
    dockerfile: str
    probe: tuple[str, ...]
    pattern: str
    check: Callable[[str], list[str]]


COMPONENTS = {
    "nginx": Component("frontend/Dockerfile", ("nginx", "-v"), r"nginx/(\d+(?:\.\d+)+)", nginx_findings),
    "jre": Component("backend/Dockerfile", ("sh", "-c", "echo $JAVA_VERSION"), r"(jdk-[\d.]+\+\d+)", jre_findings),
    "curl": Component("images/curl/Dockerfile", ("curl", "--version"), r"^curl (\d+(?:\.\d+)+)", curl_findings),
    "otp": Component("images/rabbitmq/Dockerfile", ("sh", "-c", "cat /opt/erlang/lib/erlang/releases/*/OTP_VERSION"),
                     r"^(\d+(?:\.\d+)+)", otp_findings),
    "postgres": Component("images/postgres/Dockerfile", ("pg_dump", "--version"), r"PostgreSQL\) (\d+(?:\.\d+)+)",
                          postgres_findings),
}


def image_of(component: Component) -> str:
    images = [[word for word in line.split()[1:] if not word.startswith("--")][0]
              for line in (REPO / component.dockerfile).read_text(encoding="utf-8").splitlines()
              if line.split()[:1] == ["FROM"]]
    if not images:
        raise ValueError(f"{component.dockerfile} has no FROM line")
    return images[-1]


def version_in(component: Component, image: str) -> str:
    entrypoint, *args = component.probe
    result = subprocess.run(["docker", "run", "--rm", "--entrypoint", entrypoint, image, *args],
                            capture_output=True, text=True, timeout=300)
    match = re.search(component.pattern, result.stdout + result.stderr, re.M)
    if result.returncode != 0 or not match:
        raise ValueError(f"{image} printed no version for {' '.join(component.probe)!r}: "
                         f"{(result.stdout + result.stderr).strip()[:200]!r}")
    return match.group(1)


def report(name: str, version: str, source: str) -> bool:
    try:
        findings = COMPONENTS[name].check(version)
    except Exception as error:
        print(f"::error::{name} {version}: the check could not run, so it found nothing: {error}")
        return False
    for finding in findings:
        print(f"::error::{name} {version} - {finding}")
    print(f"{name} {version} ({source}): {len(findings) or 'no'} advisories apply")
    return not findings


def main(argv: list[str]) -> int:
    if argv[1:] == ["--images"]:
        print("\n".join(dict.fromkeys(image_of(c) for c in COMPONENTS.values())))
        return 0
    if len(argv) == 3 and argv[1] in COMPONENTS:
        return 0 if report(argv[1], argv[2], "given") else 1
    if len(argv) != 1:
        raise SystemExit(f"usage: component_advisories.py [--images | {{{','.join(COMPONENTS)}}} <version>]")
    clean = True
    for name, component in COMPONENTS.items():
        try:
            image = image_of(component)
            version = version_in(component, image)
        except Exception as error:
            print(f"::error::{name}: could not read its version, so it was not checked: {error}")
            clean = False
            continue
        clean = report(name, version, image) and clean
    return 0 if clean else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
