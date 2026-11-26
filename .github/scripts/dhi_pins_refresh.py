#!/usr/bin/env python3
import gzip
import re
import sys
import urllib.request

REPOSITORY = "https://dhi.io/deb/debian/main/"
INDEX = REPOSITORY + "dists/trixie/main/binary-amd64/Packages.gz"
ENTRY = re.compile(
    r"(?P<head>^  - name: (?P<name>\S+)\r?\n    version: (?P<version>\S+)\r?\n    deb: )"
    r"(?P<deb>\S+)"
    r"(?P<mid>\r?\n    sha256: )(?P<sha>[0-9a-f]{64})",
    re.MULTILINE,
)


def packages(index: str) -> list[dict[str, str]]:
    stanzas = []
    for block in index.replace("\r\n", "\n").split("\n\n"):
        fields = dict(line.split(": ", 1) for line in block.splitlines() if ": " in line and line[0] != " ")
        if "Package" in fields and "Filename" in fields:
            stanzas.append(fields)
    return stanzas


def newest(stanzas: list[dict[str, str]], package: str, version: str) -> dict[str, str] | None:
    revision = re.compile(rf"{re.escape(version)}-(\d+)")
    found = [(int(match.group(1)), s) for s in stanzas
             if s["Package"] == package and s.get("Architecture") == "amd64"
             and (match := revision.fullmatch(s.get("Version", "")))]
    return max(found, key=lambda pair: pair[0])[1] if found else None


def refresh(text: str, index: str) -> tuple[str, list[str]]:
    stanzas = packages(index)
    changes: list[str] = []

    def move(match: re.Match[str]) -> str:
        name, version, deb, sha = match["name"], match["version"], match["deb"], match["sha"]
        if not deb.startswith(REPOSITORY):
            raise ValueError(f"{name}: {deb} is not under {REPOSITORY}")
        package = deb.rsplit("/", 1)[1].split("_", 1)[0]
        latest = newest(stanzas, package, version)
        if latest is None:
            raise ValueError(f"{name}: the index lists no {package} {version}-N for amd64, so there is nothing to pin")
        filename, checksum = REPOSITORY + latest["Filename"], latest["SHA256"]
        if filename == deb and checksum == sha:
            return match.group(0)
        old, new = deb.rsplit("_", 2)[-2], latest["Version"]
        if filename == deb:
            changes.append(f"- `{name}` {version}: dhi.io replaced revision {new.rsplit('-', 1)[1]} in place "
                           f"(sha256 `{sha[:8]}` to `{checksum[:8]}`)")
        else:
            changes.append(f"- `{name}` {version}: revision {old.rsplit('-', 1)[1]} to {new.rsplit('-', 1)[1]}")
        return match["head"] + filename + match["mid"] + checksum

    updated = ENTRY.sub(move, text)
    if not ENTRY.search(text):
        raise ValueError("no monitoring_binaries entry with a deb: and sha256: was found to read")
    return updated, changes


def main(argv: list[str]) -> int:
    path, body = argv[1], argv[2]
    with open(path, encoding="utf-8", newline="") as source:
        text = source.read()
    with urllib.request.urlopen(INDEX, timeout=120) as response:
        index = gzip.decompress(response.read()).decode("utf-8", "replace")
    updated, changes = refresh(text, index)
    if changes:
        with open(path, "w", encoding="utf-8", newline="") as target:
            target.write(updated)
        with open(body, "w", encoding="utf-8") as out:
            out.write("dhi.io rebuilds these packages with current dependencies, and sometimes replaces a "
                      "revision in place, which fails the sweep's checksum. Moved to what its `Packages` index "
                      "lists now:\n\n" + "\n".join(changes) + "\n")
    print("\n".join(changes) or "every pin already matches dhi.io's index")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
