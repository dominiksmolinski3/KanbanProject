# The unreviewed dependency backlog, read

*12 September 2026. Covers every pull request merged into `main` between #61 and #121.*

This backlog has led the "tasks left" list of an audit of this repository since its seventeenth
revision, always in the same form: a number, and "still not started". The number reached 53. Two
costs were attributed to it — a major framework upgrade nobody read, and an hour of red trunk — and
the honest observation each round was that deferring it was becoming a decision by default.

So it was read. What follows is what the pull requests actually did, what that says about the risk,
and what changed as a result. The conclusion is narrower and more useful than "53 unread pull
requests are dangerous", and it points at a mechanism rather than at a habit.

## What is in the range

58 pull requests merged; #79, #96 and #106 were opened and never merged.

| | Count | Merged by |
|---|---|---|
| Dependabot | 32 | the auto-merge workflow, or a person for majors |
| This project's owner | 26 | a person |

Of the owner's 26, fifteen (#97–#111) are the audit's own work, reviewed by nobody but the process
that produced it — that remains true and is not what this document is about. The other eleven
(#61–#65, #78, #80–#84, #95) are the attachments feature and the six security-scanning workflows
that the audit has listed as "eleven workflows now, not four" without ever reading them.

## The finding

**Every major-version bump in the range was merged by a person, and every one that needed source
changes got them. The only bump that broke anything was a minor, and it was correct to classify it
as one.**

`dependabot-auto-merge.yml` merges `semver-patch` and `semver-minor` and leaves majors alone. That
rule worked exactly as written:

| PR | Bump | Merged by | Adapted |
|---|---|---|---|
| #87 | Spring Boot 3.5.16 → **4.1.1** | a person | 11 source and test files in the same PR |
| #90 | azurerm 4.81.0 → **5.3.0** | a person | 5 Terraform files (`enable_rbac_authorization` → `rbac_authorization_enabled`) |
| #89 | jjwt 0.11.5 → **0.13.0** | a person | `JwtService` migrated to the 0.12+ parser/builder API, by hand, on the Dependabot branch |
| #116 | vitest 4.1.11 → **5.0.0** | a person | nothing needed — see below |
| #88, #91, #93, #94, #75, #74, #117 | seven further majors | a person | nothing needed |
| **#115** | **react 19.0.0 → 19.2.8** | **the auto-merge workflow** | **nothing — and that was the problem** |

The jjwt one is worth pausing on, because it is the case the backlog's own framing would have got
wrong. A jump from `0.11.5` to `0.13.0` reads like a patch-level number and is a rewrite of the
library's entire builder and parser API. It was caught, and it was caught because Dependabot
classified it as a major and the auto-merge rule therefore did not touch it. Somebody opened it,
found it did not compile, and pushed a migration commit onto the branch.

### Why #115 got through, precisely

`react-dom` reads `react`'s version at import time and throws when the two differ. So a bump of
`react` alone is not a degradation, it is a stop: every suite that renders anything fails before its
first assertion. Fifteen did.

Nothing in the process was wrong about semver. `react` 19.0.0 → 19.2.8 **is** a minor release of
`react`. The rule is not "minors are safe"; it never was. What is actually true is narrower:

> A package whose semver understates its coupling to another package cannot be auto-merged alone,
> whatever its update-type says.

That is a property of two named packages, not of the backlog's size. Reading fifty more pull
requests would not have found it; the one that mattered had two files in it and both were lockfiles.

## Dead dependencies, and why they are not only noise

Two things in `frontend/package.json` were being bumped, tested, reviewed and merged for years while
nothing anywhere used them.

- **`vitest`** — a test runner, in a project whose test runner is Jest. No `vitest.config.*`, no npm
  script invoking it, nothing importing it. Dependabot carried it across a major version (#116) and
  CI proved nothing about that, because nothing ran it. **Removed on this branch**, taking 250 lines
  of lockfile with it.
- **`@types/react` and `@types/react-dom`** — type packages, in a project with no TypeScript: no
  `tsconfig.json`, no `.ts` or `.tsx` file anywhere, nothing in the build or the lint reading them.
  They have already drifted apart (`^19.2.18` against `^19.0.4`) with no consequence, which is the
  clearest possible demonstration that nothing consumes them. **Kept**, because an editor's
  JavaScript language service does read them for JSX completion, and that is a real if small
  benefit that removing them would take away silently.

The reason these matter is not tidiness. `@types/react` is *the other half of #115*: Dependabot
titled it "bump react and @types/react", so the pull request that stopped the trunk was a live
dependency travelling with a dead one. Every dead package is one more thing that can share a pull
request with something that matters.

## What changed

Three things, all on this branch, and all aimed at the mechanism rather than at the incident.

1. **`react` and `react-dom` are a Dependabot group.** There is now no pull request that moves one
   without the other, so there is nothing for the auto-merge rule to get right or wrong. Named
   packages rather than a `react*` pattern, which would have swept `react-router-dom`,
   `react-toastify` and `react-i18next` into one bundle and recreated the problem the rest of that
   file avoids on purpose.
2. **A build guard: `frontend/src/__tests__/dependencyPairs.test.js`.** It reads `package.json` and
   fails when `react` and `react-dom` declare different versions. The group is the prevention; this
   is the check that the prevention is working, and it fails on the *branch that proposes* the bad
   bump rather than an hour later on somebody else's pull request. Verified by reproducing #115's
   exact state — `react ^19.2.8`, `react-dom ^19.0.0` — and watching it go red.
   It also asserts that no second test runner has reappeared.
3. **`vitest` removed.**

Only this one pair is guarded. `@types/react` and `@types/react-dom` are published on separate
release trains and are not required to match; asserting that they do would be a rule nobody agreed
to, failing on bumps that are fine.

## Found on the way, and not fixed here

**Issue #96, "ZAP Scan Baseline Report", has been open and unread since 4 September.** The DAST
sweep that files it is one of the six workflows in this range that nothing has ever reviewed, and
its first finding is that the application serves no `Content-Security-Policy` header at all;
`Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy`, `Cross-Origin-Resource-Policy` and
`Permissions-Policy` are absent too. That is a real gap in an application that serves its own
bundle, and it is a different piece of work from this one. It is not addressed on this branch.

That is the second demonstrated cost of the backlog, and unlike the first it was not a dependency at
all — it was a scan result nobody opened.

## The decision

**This item comes off the "tasks left" list.** It has been read. The general form of it — "there are
N pull requests nobody has reviewed" — was not a useful thing to track, because the risk it was
standing in for turned out to have a specific mechanism and a specific fix, and counting pull
requests would never have found either.

What replaces it is narrower and is already in place: majors are held for a person and that has
worked every time; the one coupling semver misrepresents is now a group and a build guard; and the
scheduled sweep plus `trunk-alarm` catch the merged tree regardless of what produced it.

What is deliberately *not* claimed: nobody has read the source diff of all 58 pull requests. What
was read is every title, every changed-file list, who merged each one, and the full diff of the ones
whose shape suggested risk. A line-by-line review of 32 dependency bumps is not a defence against
anything this document found, and saying otherwise would be the same mistake as counting them.
