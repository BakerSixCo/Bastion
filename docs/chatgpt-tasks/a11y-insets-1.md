# Task a11y-insets-1: fix edge-to-edge insets on Reader / ZIM reader / Lock screens (branch `chatgpt/a11y-insets-1`)

**Runnable now, independent.** Needs the emulator (API 35) for a screenshot check. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/a11y-insets-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones (card/flow text, `ai/CheckedAnswer.kt`, `ai/QueryPlan.kt`, Meshtastic device pages, route math). No behaviour change beyond layout insets.

## Why

`targetSdk 35` (`android/app/build.gradle.kts:16`) means Android 15 draws the app edge-to-edge (the comment at `ui/BastionApp.kt:77` already says so). But only the **Emergency** branch wraps its content in `Box(Modifier.safeDrawingPadding())` (`BastionApp.kt:80`). The **Lock** (`BastionApp.kt:71-74`), **Reader** (`:93-106`) and **ZIM-reader** (`:85-91`) branches `return` *before* the `Scaffold` and render in a bare `Surface` with only downstream `.padding(16.dp)` (`ui/ReaderScreen.kt:76-80`), so the Reader "← Back"/title row and the Lock PIN field draw **under the status bar**. The Scaffold tab screens are fine (Scaffold applies insets).

## Do

1. Reproduce on the emulator (API 35, `Medium_Phone_API_37.0`): open a document (Reader), a ZIM article (ZIM reader), and the Lock screen; screenshot each — the top controls sit under the status bar.
2. Wrap the content of those three branches in `Modifier.safeDrawingPadding()` (mirror the Emergency branch at `BastionApp.kt:80`), or give each its own `Scaffold` that applies `innerPadding`. Pick whichever keeps the existing `.padding(16.dp)` from double-insetting. Leave the Scaffold tab path untouched.
3. Re-screenshot: Back/title row and PIN box fully clear of the status bar and nav bar, content not clipped, no doubled padding.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/ui/BastionApp.kt` (the Lock/Reader/ZIM branches), `ui/ReaderScreen.kt` (padding only if needed); your report. **Not** the Scaffold tab screens' layout, and nothing outside `ui/`.

## Done when

Before/after emulator screenshots of all three screens show the top controls below the status bar and content clear of the nav bar; the Scaffold tabs are visually unchanged; `:app:assembleDebug` builds; `docs/chatgpt-tasks/a11y-insets-1-report.md` is committed with the screenshots described.
