# Task a11y-semantics-1: content descriptions, roles & state on cards and status dots (branch `chatgpt/a11y-semantics-1`)

**Runnable now, independent.** Emulator for the Accessibility Scanner / uiautomator check. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/a11y-semantics-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones. No wording changes to card/flow content — this is UI semantics only.

## Why

The whole app has only **2** `contentDescription`s (both nav icons, `ui/BastionApp.kt:130,143`), **0** `semantics`/`Role` uses, and 9 `.clickable`/`.toggleable` with no role. TalkBack users get almost nothing:
- Clickable cards expose no role/label: `ui/SearchUi.kt:25-30` (HitCard), `ui/AskScreen.kt:180-194` (SourceCard, `.clickable` at `:182`), and the library/companion "Open" rows.
- Readiness is conveyed **only** by a `●`/`○` glyph + colour (`ui/HomeScreen.kt:182-185`, repeated in LocalAiCard/ZimCard) — TalkBack reads the bullet character, not "ready", and colour-only state fails WCAG.

## Do

1. On the status cards (Home readiness dots), add `Modifier.semantics { stateDescription = "ready" / "not set up" }` (text, not the glyph) and keep the glyph+colour as the visual. Use `clearAndSetSemantics`/`mergeDescendants` so the card announces one coherent label.
2. On clickable cards (HitCard, SourceCard, Open rows), add `Modifier.semantics { role = Role.Button }` and a meaningful `contentDescription` where the visible text is not self-describing.
3. Audit touch targets: `TextButton`/`FilterChip` rows (e.g. `HomeScreen.kt:149,352`) should meet 48 dp via `Modifier.minimumInteractiveComponentSize()`.
4. Verify with Accessibility Scanner or `adb shell uiautomator dump` on the emulator.

## Files you may change

`ui/SearchUi.kt`, `ui/AskScreen.kt`, `ui/HomeScreen.kt` (incl. LocalAiCard/ZimCard); your report. Nothing outside `ui/`. (Note: `perf-statfs-1` also edits `HomeScreen.kt`, in a different place — Claude merges the two serially.)

## Done when

Each status card exposes a text `stateDescription` (not the bullet glyph); clickable cards announce as buttons with a label; no interactive node is < 48 dp (`uiautomator dump` evidence); if `test-lint-1` has landed, `ContentDescription`/`TouchTargetSizeCheck` are clean; `:app:assembleDebug` builds; `docs/chatgpt-tasks/a11y-semantics-1-report.md` is committed.
