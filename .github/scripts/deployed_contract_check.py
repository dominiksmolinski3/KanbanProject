#!/usr/bin/env python3
"""Check that a deployed origin answers what this checkout says it should.

Every claim below is already asserted somewhere in the build - ``SecurityHeadersTest`` reads the
policy, ``PublicBundlePathsTest`` reads the static-asset patterns, ``OpenApiContractTest`` reads
the published contract, ``SpaRoutes`` is single-sourced for the forward and the permit. All of
those read *source*. Nothing reads the deployment, and the two can diverge for exactly one reason:
a deploy that did not happen. That is not hypothetical here - an environment pinned to ``latest``
ran eight-day-old code for eight days while reporting itself converged, because the only signal
anybody had was a ``terraform plan`` that correctly said nothing had changed.

So this is the missing direction, and it is deliberately narrow. It does **not** know which commit
is deployed; nothing public says. It knows the claims in *this* checkout and asks the origin
whether it makes them, so a deployment falls behind visibly the moment a claim moves in the trunk
and not in the environment. A revision that changes no claim is invisible to it, which is stated
rather than solved.

The claims are read out of the Java rather than copied here. A copy is a second source of truth
that drifts, which is the failure two thirds of the guards in this repository exist to catch, and
it would be absurd to introduce one in the file whose whole job is catching divergence. A source
file this cannot parse is an error, never a skipped check: a checker that quietly stops checking
is the thing being fixed on the branch this arrived with.
"""

from __future__ import annotations

import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SECURITY_HEADERS = REPO / "backend/src/main/java/pl/myproject/kanbanproject2/config/security/SecurityHeaders.java"
SPA_ROUTES = REPO / "backend/src/main/java/pl/myproject/kanbanproject2/config/SpaRoutes.java"

TIMEOUT = 30

failures = []
checks = 0


def fail(claim, detail):
    failures.append(claim + ": " + detail)
    print("::error::" + claim + ": " + detail)


def check(claim, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print("  ok    " + claim)
    else:
        fail(claim, detail)
    return ok


def get(url, accept="*/*"):
    """Fetch a URL, returning (status, headers, body). A refusal is an answer, not an exception."""
    request = urllib.request.Request(
        url, headers={"Accept": accept, "User-Agent": "deployed-contract-check"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as refusal:
        # 401/403/404 are answers this asks for, so they must not look like a broken sweep.
        return refusal.code, refusal.headers, refusal.read()
    except urllib.error.URLError as unreachable:
        fail("the origin is unreachable", url + ": " + str(unreachable.reason))
        raise SystemExit(1)


def java_string_join(source, constant):
    """Rebuild a String.join(sep, "a", "b", ...) constant from the Java that declares it."""
    pattern = constant + r'\s*=\s*String\.join\(\s*"((?:[^"\\]|\\.)*)"\s*,(.*?)\);'
    match = re.search(pattern, source, re.DOTALL)
    if not match:
        raise SystemExit(
            "::error::cannot parse " + constant + " out of SecurityHeaders.java - the header this "
            "compares against is gone, so the check below would pass on nothing")
    separator = match.group(1).encode().decode("unicode_escape")
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', match.group(2))
    return separator.join(part.encode().decode("unicode_escape") for part in parts)


def spa_routes(source):
    match = re.search(r"ALL\s*=\s*\{(.*?)\}", source, re.DOTALL)
    if not match:
        raise SystemExit("::error::cannot parse SpaRoutes.ALL - the client routes this checks are gone")
    routes = re.findall(r'"([^"]+)"', match.group(1))
    if not routes:
        raise SystemExit("::error::SpaRoutes.ALL parsed empty, which would make every route check vacuous")
    return routes


def main(origin):
    origin = origin.rstrip("/")
    headers_source = SECURITY_HEADERS.read_text(encoding="utf-8")
    routes = spa_routes(SPA_ROUTES.read_text(encoding="utf-8"))

    print("checking " + origin)

    # --- the headers the browser is told to obey ------------------------------------------
    status, headers, body = get(origin + "/")
    check("the origin serves the app shell", status == 200, "GET / answered " + str(status))

    for header, constant in (("Content-Security-Policy", "CONTENT_SECURITY_POLICY"),
                             ("Permissions-Policy", "PERMISSIONS_POLICY")):
        expected = java_string_join(headers_source, constant)
        served = headers.get(header)
        check(header + " is the one this checkout declares", served == expected,
              "deployed: " + repr(served) + " / source: " + repr(expected))

    for header, expected in (("X-Content-Type-Options", "nosniff"),
                             ("X-Frame-Options", "DENY"),
                             ("Cross-Origin-Opener-Policy", "same-origin"),
                             ("Cross-Origin-Resource-Policy", "same-origin")):
        check(header + ": " + expected, headers.get(header) == expected,
              "served " + repr(headers.get(header)))

    # Declined deliberately, and asserted absent by SecurityHeadersTest so that adding it is a
    # deliberate act. It would break the reCAPTCHA frame, which carries no CORP header of its own.
    check("Cross-Origin-Embedder-Policy is still declined",
          headers.get("Cross-Origin-Embedder-Policy") is None,
          "served " + repr(headers.get("Cross-Origin-Embedder-Policy")) + ", which breaks the reCAPTCHA frame")

    # --- the bundle the shell boots -------------------------------------------------------
    # PublicBundlePathsTest asserts the patterns; this asserts they match what Vite actually
    # emitted. Getting it wrong serves index.html and then 403s the script that boots it, and no
    # suite here can see that - Jest stubs fetch and Cypress runs against the Vite dev server.
    shell = body.decode("utf-8", "replace")
    check("the shell has a mount point", '<div id="root">' in shell, "no #root in the served HTML")
    check("the shell carries no inline script",
          not re.search(r"<script(?![^>]*\ssrc=)[^>]*>\s*\S", shell),
          "an inline script means script-src would need 'unsafe-inline', which is the half worth having")

    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', shell)
    check("the shell references the hashed bundle", bool(assets),
          "no /assets/ reference in the served HTML")
    for asset in assets:
        status, _, _ = get(origin + asset)
        check(asset + " is served", status == 200,
              "answered " + str(status) + " - the shell boots and the script behind it does not")

    # --- the published contract -----------------------------------------------------------
    status, _, body = get(origin + "/v3/api-docs", accept="application/json")
    check("/v3/api-docs is public", status == 200, "answered " + str(status) + " to an anonymous caller")
    check("/v3/api-docs is an OpenAPI document", b'"openapi"' in body, "no openapi key in the body")

    status, _, _ = get(origin + "/api/v3/api-docs")
    check("/api/v3/api-docs is not where the contract lives", status in (401, 403, 404),
          "answered " + str(status) + "; the prefix predicate has moved springdoc's route")

    # --- the routes the client owns -------------------------------------------------------
    for route in routes:
        status, _, body = get(origin + route)
        check(route + " serves the SPA shell", status == 200 and b'<div id="root">' in body,
              "answered " + str(status) + " - a deep link 403s when a route is missing from SpaRoutes.ALL")

    # --- what an anonymous caller must not get --------------------------------------------
    status, _, _ = get(origin + "/api/columns")
    check("/api/columns refuses an anonymous caller", status in (401, 403), "answered " + str(status))

    status, _, body = get(origin + "/actuator/health", accept="application/json")
    check("/actuator/health answers", status == 200, "answered " + str(status))
    check("the probes are both groups", b'"liveness"' in body and b'"readiness"' in body,
          "a probe group is gone, which is a container that cannot report itself healthy")
    # The control for SEC-09: show-details=when_authorized has to still mean *when authorized*.
    check("an anonymous caller gets no health details", b'"components"' not in body,
          "the health endpoint is disclosing its components to anybody who asks")

    for probe in ("/actuator/health/liveness", "/actuator/health/readiness"):
        status, _, _ = get(origin + probe)
        check(probe + " answers 200", status == 200,
              "answered " + str(status) + "; Container Apps probes this")

    print("")
    print(str(checks - len(failures)) + "/" + str(checks) + " claims hold")
    if failures:
        print("")
        print("the deployment does not answer what this checkout claims:")
        for failure in failures:
            print("  - " + failure)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1].startswith("https://"):
        raise SystemExit("usage: deployed_contract_check.py https://<origin>")
    sys.exit(main(sys.argv[1]))
