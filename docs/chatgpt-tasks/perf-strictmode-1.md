# Task perf-strictmode-1: StrictMode in debug builds to surface main-thread I/O (branch `chatgpt/perf-strictmode-1`)

**Runnable now, independent.** Emulator to read logcat. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/perf-strictmode-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones. **Release builds must be byte-for-byte unaffected** — this is debug-only instrumentation.

## Why

There is no `StrictMode` anywhere and no custom `Application` (manifest `<application>` has no `android:name`). A debug-gated StrictMode makes main-thread disk/network I/O and leaked closables show up in logcat immediately — it will flag `perf-statfs-1`'s `StatFs` read and guard against future regressions.

## Do

1. Add a `BuildConfig.DEBUG`-guarded StrictMode setup: `setThreadPolicy(detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog())` and `setVmPolicy(detectLeakedClosableObjects().detectActivityLeaks().penaltyLog())`. **`penaltyLog()` only — never `penaltyDeath`** (must never crash a field build).
2. Register it the least-intrusive way: either an `if (BuildConfig.DEBUG) { … }` block at the very top of `MainActivity.onCreate` (`ui/MainActivity.kt:10`), or a new `BuildConfig.DEBUG`-only `Application` wired via a `src/debug/AndroidManifest.xml` `android:name` so release has no custom Application. Prefer the debug-manifest Application if clean.
3. Confirm: under a debug build, logcat shows the policy active and currently flags the HomeScreen `StatFs` read; a release build shows no StrictMode (grep the built release or code-review the gate).

## Files you may change

`ui/MainActivity.kt` and/or a new `android/app/src/debug/AndroidManifest.xml` + a debug `Application` class; your report. Nothing in `src/main` behaviour, nothing in release config.

## Done when

Debug logcat shows StrictMode active and flags the HomeScreen disk read; release build provably has no StrictMode; `:app:assembleDebug` and `:app:assembleRelease` both build; `docs/chatgpt-tasks/perf-strictmode-1-report.md` is committed with the logcat excerpt.
