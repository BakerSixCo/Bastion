# Task test-lint-1: turn on Android Lint as a gate + checked-in baseline (branch `chatgpt/test-lint-1`)

**Runnable now, independent.** PC only (Gradle). Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/test-lint-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones. Do **not** fix issues blindly to pass — record them in the baseline.

## Why

There is no `lint{}` config, no `lint-baseline.xml`, and no CI anywhere. Android Lint is the lowest-effort way to catch accessibility (missing `contentDescription`, small touch targets), resource, manifest and API-level regressions automatically — and it will back the a11y tasks (`a11y-insets-1`, `a11y-semantics-1`).

## Do

1. Add to `android/app/build.gradle.kts` a `lint { warningsAsErrors = true; abortOnError = true; checkDependencies = true; baseline = file("lint-baseline.xml") }` block.
2. Generate the baseline (`./gradlew :app:lintDebug` once with the baseline file declared creates it) so the **current** issues are recorded, not fixed blindly. Commit `lint-baseline.xml`.
3. In your report, summarise what the baseline captured by category (how many accessibility, correctness, performance, etc.) so Claude can see the backlog lint found — do not silently bury serious a11y findings.
4. Add one line to `CLAUDE.md` Build & test documenting `./gradlew :app:lintDebug`.

## Files you may change

`android/app/build.gradle.kts` (lint block only), `android/app/lint-baseline.xml` (generated), `CLAUDE.md` (one line under Build & test), your report. Nothing else.

## Done when

`./gradlew :app:lintDebug` is BUILD SUCCESSFUL with the committed baseline; a deliberately-introduced new warning (e.g. a hard-coded string or a missing `contentDescription` added temporarily) makes the build fail, then is reverted; the report lists the baseline's issue counts by category; `docs/chatgpt-tasks/test-lint-1-report.md` is committed.
