#!/usr/bin/env python3
import os
import re
import subprocess
import sys

RATE_LIMITED = re.compile(
    r"invalid response status 429"
    r"|HTTP Status: 429"
    r"|\bHTTP(?:/\d(?:\.\d)?)? 429\b"
    r"|\b429 Too Many Requests\b"
    r"|\btoomanyrequests\b"
    r"|API rate limit exceeded"
    r"|secondary rate limit",
    re.IGNORECASE,
)
ANSI = re.compile(r"\x1b\[[0-9;]*m")
EVENT_PAYLOAD = "github_event_payload"


def rate_limited_line(log: str) -> str | None:
    for line in log.splitlines():
        if EVENT_PAYLOAD in line:
            continue
        line = ANSI.sub("", line)
        if RATE_LIMITED.search(line):
            return line.strip()[:200]
    return None


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def failed_jobs(repo: str, run_id: str) -> list[tuple[str, str]]:
    listing = gh("api", "--paginate", f"repos/{repo}/actions/runs/{run_id}/jobs?filter=latest",
                 "--jq", '.jobs[] | select(.conclusion == "failure") | "\\(.id)\\t\\(.name)"')
    return [tuple(line.split("\t", 1)) for line in listing.splitlines() if line]


def main() -> int:
    repo, run_id, attempt = os.environ["GH_REPO"], os.environ["RUN_ID"], os.environ["RUN_ATTEMPT"]
    if attempt != "1":
        print(f"run {run_id} is already on attempt {attempt}; a second failure stays red")
        return 0
    for job_id, name in failed_jobs(repo, run_id):
        log = gh("api", "--allow-escape-sequences", f"repos/{repo}/actions/jobs/{job_id}/logs")
        line = rate_limited_line(log)
        if line:
            print(f"::notice::{name} was rate limited ({line}); rerunning the failed jobs of run {run_id} once")
            gh("run", "rerun", run_id, "--failed")
            return 0
    print(f"no failed job of run {run_id} was rate limited; leaving it red")
    return 0


if __name__ == "__main__":
    sys.exit(main())
