# Task perf-statfs-1: move free-space StatFs off the composition (branch `chatgpt/perf-statfs-1`)

**Runnable now, independent.** Emulator for the StrictMode check (pairs with `perf-strictmode-1`). Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/perf-statfs-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones. No behaviour change — the same number is shown, just computed off the main thread.

## Why

`ui/HomeScreen.kt:168` `val freeBytes = remember { StatFs(ctx.filesDir.path).availableBytes }` runs **synchronous disk I/O on the main thread** during the first Home composition. That is a StrictMode disk-read violation and measurable cold-start jank (Home is the first screen).

## Do

1. Replace the `remember { StatFs(...) }` with `produceState`/`LaunchedEffect` that reads `StatFs(ctx.filesDir.path).availableBytes` on `Dispatchers.IO`, keyed off `store.changeCounter` (same key the rest of Home uses) so the figure refreshes after an import. Show a neutral placeholder (or the last value) until it resolves — never block the frame.
2. Confirm the displayed free-space string is identical to before once resolved.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/ui/HomeScreen.kt`; your report. Nothing else. (Note: `a11y-semantics-1` also edits `HomeScreen.kt`, in different places — Claude merges the two serially.)

## Done when

Home shows the same free-space value; with `perf-strictmode-1` in place (or a temporary StrictMode in debug) `adb logcat` shows **no** disk-read-on-main violation from HomeScreen; `:app:assembleDebug` builds and the app launches on the emulator; `docs/chatgpt-tasks/perf-statfs-1-report.md` is committed.
