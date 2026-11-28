# Task kit-1: kit manifest, verify/fetch tool, and the owner's PDF trims (branch `chatgpt/kit-1`)

**Queue:** after `device-1`. **Previous step:** Claude gathered ~70 kit documents on 2026-11-26 and the owner
made keep-or-kill decisions (`docs/library-plan.md`, sections "Radio frequencies and operation", "Keep or
kill", "Feeding yourself", "Contingency gap audit"). **Next step:** `kit-2` (bundle exclude list), then
`kit-3` (load the kit on the emulator), `kit-4` (gap-fill downloads) — both use this tool.

```powershell
git fetch origin
git switch -c chatgpt/kit-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. The kit (`tools/kit/docs/` in Claude's checkout) is a managed asset: you
**read** it, you never write to it. All outputs go to your worktree (`tools/kit/out/`, gitignored there too);
Claude moves accepted files into the kit.

**Owner approval for one install, given by handing you this task:** `pypdf` from PyPI (BSD-3-Clause), one
pinned version, `pip install --user pypdf==<version>`; record version, URL and SHA-256 in the report. No
other network use in this task.

## Why

The kit is reproducible only from Claude's notes: hashes in `docs/status.md`, sources in
`docs/library-plan.md`, converters in `tools/kit-build/`. A machine-readable manifest and one tool make it
checkable (S-tier: nothing in the kit whose origin can't be named and re-verified).

## Build

1. **`tools/kit/sources.json`** (committed; JSON Schema in `tools/kit/sources.schema.json`). One entry per kit
   document in `tools/kit/docs/` and `tools/kit/docs/reference/`, seeded from the hash tables in
   `docs/status.md` and the tables in `docs/library-plan.md`:
   `file`, `sha256`, `title`, `publisher`, `edition` (date), `origin` (`url` re-fetchable | `browser-copy`
   (page text read in a browser: fcc.gov, navcen, fsis, ready.gov) | `owner-download` (tak.gov ATAK guide, the
   2025 Army manuals) | `derived` (made by a script in `tools/kit-build/`, with `script` and `inputs`)),
   `url`, `licence` (the document's own statement, short), `tier` (`own` | `reference`), `flags` (e.g.
   `historic`, `third-party-figure`, `older-than-installed-app`), `trim` (see 3). Entries you cannot seed from
   those files: list them in the report, do not guess.
2. **`tools/kit/kit.py`** (Python 3, stdlib + pypdf; `python -I tools/kit/kit.py <cmd>`):
   - `verify <kit-dir>`: hash every file; report missing, unknown (not in the manifest) and mismatched files.
     Exit non-zero on any mismatch.
   - `fetch <id>... --out <dir>`: only for `origin: url` entries; download into a new empty folder, check the
     SHA-256 against the manifest when known, never overwrite, never follow a redirect to another host.
     (Used in kit-3/kit-4 only with the owner's approval; this task does not download kit files.)
   - `trim <id> --out <dir>`: apply the entry's `trim` with pypdf; write the trimmed PDF plus a sidecar
     `.provenance.txt` ("pages X–Y of <file>, SHA-256 <original>").
3. **The owner's three trims** (`docs/library-plan.md`, "Keep or kill"), output to `tools/kit/out/`:
   - NIFOG v2.02: keep **printed** pages 1–120, 139–143, 181–183.
   - AUXFOG v1.1 (2016): keep safety, go kit, amateur band plan and frequencies, Appendix D antennas, E
     connectors, F cables, G coded squelch, I.5 ICS forms, glossary; drop Appendix H (pre-2017 GMRS/FRS),
     Appendix A contact templates, I.1–I.4 ICS org charts and positions, K URL list.
   - ATP 6-02.53 (2025): keep appendices A, E, G, H (plus the cover/title page so the document identifies itself).
   Printed page numbers are not PDF page numbers: find the mapping by text (`pdftotext -f N -l N`), store it in
   the manifest `trim` field as PDF page ranges with the printed ranges beside them, and show in the report the
   first and last line of text of every kept range.
4. **Tests** (`tools/kit/test_kit.py`, `unittest`, no network): manifest validates against the schema; `verify`
   catches a changed byte, a missing file and an unknown file; `trim` keeps exactly the requested pages of a
   small generated PDF; `fetch` refuses a non-`url` entry and an existing output file.

## Files you may change

`tools/kit/sources.json`, `tools/kit/sources.schema.json`, `tools/kit/kit.py`, `tools/kit/test_kit.py`,
`tools/kit/.gitignore` (to keep `out/` ignored), `tools/kit-build/*` (only to make a script importable, no
behaviour change), and your report. Nothing under `android/`.

## Done when

`python -I tools/kit/test_kit.py` passes; `kit.py verify` on Claude's `tools/kit/docs` reports 0 mismatches
(list unknown files rather than adding guesses); the three trimmed PDFs and their provenance files are in your
`tools/kit/out/`; `docs/chatgpt-tasks/kit-1-report.md` (page mappings, pypdf version/hash, what was tested how)
is committed on `chatgpt/kit-1`.
