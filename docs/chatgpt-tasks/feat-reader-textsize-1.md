# Task feat-reader-textsize-1: Reader text-size control, A− / A+ (branch `chatgpt/feat-reader-textsize-1`)

**Runnable now, independent.** Emulator for screenshots + persistence check. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/feat-reader-textsize-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones, retrieval or the model. Pure Reader UI + one persisted setting.

## Why

The Reader body is a fixed `bodyMedium` (`ui/ReaderScreen.kt:132`) and the heading span is fixed (`:113`). Outdoors, in bright sun or with gloves, users need larger text **without** changing the whole system font scale. This is an on-theme, self-contained accessibility feature.

## Do

1. Add **A−** / **A+** controls to the Reader (small buttons in the existing top row near "← Back"). They step a scale factor (e.g. 0.85×–1.8× in ~6 steps).
2. Multiply the body `fontSize` (`:132`) and the heading span size (`:113`) by the factor. Keep line height proportional so paragraphs stay readable.
3. Persist one value (a single Float/Int) in a small prefs file so it survives process death; apply it when the Reader opens. Clamp to the min/max.
4. Do not alter the highlight ranges or text content — only sizing.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/ui/ReaderScreen.kt` and a small prefs read/write (reuse an existing prefs helper if the app has one; otherwise a tiny `SharedPreferences` read/write is fine); your report. Nothing in retrieval, `data/`, or the ZIM/library pipeline.

## Done when

The Reader shows working A−/A+ controls; emulator screenshots at min / default / max sizes; the chosen size persists across `am force-stop` + relaunch; headings scale with the body; `:app:assembleDebug` builds; `docs/chatgpt-tasks/feat-reader-textsize-1-report.md` is committed.
