# Task kitconv-1: tests and hardening for the kit-build converters (branch `chatgpt/kitconv-1`)

**Runnable now, independent.** PC-only, no device. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/kitconv-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. No new dependency beyond `tools/requirements.txt` (pypdf is allowed per kit-1).

## Why

`tools/kit-build/` holds the converters that turn source material into kit Markdown: `ecfr2md.py` (FCC Parts 95/97 XML → MD, handles GPOTABLE and HTML TABLE), `ncvec2md.py` (ham question pool → question + correct answer only, all 409 parsed), `nccih.py` (NCCIH TCM + herbs), `meshtrim.py` / `meshdocs.py` (Meshtastic docs), `bodyhash.py` (whitespace-normalised page hash). They were written fast, under real bugs (the NCVEC `(?:\[...\] )?` bracket and the `(A)[` space; the Meshtastic site-name-vs-heading title; GPOTABLE vs HTML TABLE). None has a test. The kit is only reproducible if these converters are **deterministic and re-runnable** — a re-run must byte-match. Lock that in.

## Do

1. **Golden-file tests.** For each converter add `test_<name>.py` (stdlib `unittest`, no network). Commit a **small** fixture input under `tools/kit-build/testdata/<name>/` — a trimmed snippet of the real input that exercises the tricky path (one GPOTABLE and one HTML TABLE for ecfr2md; a NCVEC item with a rule-ref bracket and one without; a Meshtastic page whose site name differs from its `### ` heading; a page for bodyhash). Store the expected output as a golden `.md`/`.txt`. The test runs the converter on the fixture and asserts the output equals the golden file.
2. **Harden the known edge cases** as explicit, named test cases so a regression is caught: the NCVEC bracket/space cases (409/409), ecfr GPOTABLE vs TABLE, meshtrim title from the `### ` heading, bodyhash whitespace/quote normalisation.
3. **Determinism:** make each converter's output stable across runs (stable ordering, normalised line endings) so re-running on the same input byte-matches. If a converter already is, say so and add a test that proves it.
4. Only no-behaviour-change edits to the converters themselves (make a `main()` importable, factor a function to test it) — do not change what accepted kit output would be. If you find a real bug, fix it and show the before/after in the report.

## Files you may change

`tools/kit-build/test_*.py`, `tools/kit-build/testdata/**` (new small fixtures + goldens), small importability edits to the `tools/kit-build/*.py` converters, `tools/kit-build/README.md` (document how to run the tests), your report. **Not** any kit document under `tools/kit/`.

## Done when

Each `python -I tools/kit-build/test_<name>.py` passes; the report lists what each test covers, any bug found and fixed (before/after), and confirms a re-run byte-matches; `docs/chatgpt-tasks/kitconv-1-report.md` is committed.
