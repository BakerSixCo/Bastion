# Task kit-5: fetch the owner-approved documents, batches 2 and 3 (branch `chatgpt/kit-5`)

**Queue:** after `kit-4`. **Scope:** the two later approvals in `docs/library-plan.md`:
- "Owner approval, second batch (2026-11-26): weapons care, trapping, leather and textiles, metals" (11 rows);
- "Batch 3 keep or kill (2026-11-26)": the **Keep** table only (trades, cradle to grave, law and government).
Nothing from the Kill table, the "Not free" list, or the large ZIMs (Wikipedia, Wikibooks/Wikisource,
Gutenberg, LibreTexts), which wait for the owner's storage decision.

**Previous step:** kit-4 (same method). **Next step:** Claude reviews, moves accepted files into the kit, then
`kit-3` loads and measures the whole new kit once.

```powershell
git fetch origin
# From Claude's branch if kit-1 is merged there; otherwise from your kit-4 branch (say which in the report).
git switch -c chatgpt/kit-5 origin/claude/hello-e9r4rq
```

Rules: as `kit-4` (`AGENTS.md` "Network and downloads", `CLAUDE.md` "Sources of truth"). Downloads go only to your
worktree's `tools/kit/incoming/<date>/`.

## Do

Follow `kit-4` steps 1–5 for every approved row, plus:
1. **Find the official edition.** For each row, use the publisher's own site (armypubs.army.mil or the Central
   Army Registry for Army, NETC/EverySpec/DTIC/Internet Archive NAL or Navy collections for Navy NAVEDTRA,
   aphis.usda.gov, fs.usda.gov, nwcg.gov, cdc.gov, nia.nih.gov, samhsa.gov, archives.gov, congress.gov,
   uscourts.gov, fema.gov, openstax.org, gutenberg.org). Prefer the newest edition that is marked for public
   release; report the edition you took and any newer restricted one you skipped.
2. **Rows with a condition in the plan:** TM 10-268 only if the file itself shows a public-release marking;
   the PAHO/WHO/ICRC dead-bodies manual only if its licence allows free sharing; the AFWA trapping guides
   unchanged with their credit line; Navy manuals' Distribution A checked on each file.
3. **Tier:** OpenStax books go in `sources.json` with `tier: reference`; everything else `own`. Label `historic`:
   TM 10-268 (1948), *Forge Practice*, McGuffey Readers, Ray's Arithmetic, *Robert's Rules* (1915), TM 9-8000
   (1985).
4. **Law texts:** the Constitution, amendments, Declaration, Articles of Confederation and Idaho Constitution as
   plain text or Markdown from the official source (National Archives, Idaho Legislature), each with a source
   header; *Constitution Annotated* and the federal rules as the official PDFs.
5. **Weapons rows:** maintenance, function checks, malfunctions, storage and legal limits only. If a file mixes in
   anything about illegal modification, report it rather than including it.

## Files you may change

`tools/kit/sources.json`, new converter scripts under `tools/kit-build/`, your report. Downloads stay uncommitted.

## Done when

Every approved row is downloaded and checked or rejected with a reason, `kit.py verify` passes on your incoming
folder, and `docs/chatgpt-tasks/kit-5-report.md` (per item: URL, edition, licence or distribution quote, size,
SHA-256, verdict, tier) is committed.
