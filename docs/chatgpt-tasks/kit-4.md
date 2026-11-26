# Task kit-4: fetch the owner-approved gap-fill documents, batch 1 (branch `chatgpt/kit-4`)

**Queue:** after `kit-2`. **Scope: batch 1 only**, approved by the owner on 2026-11-26 (Claude recorded the
approval, two holds and three conditions under "Owner approval" in `docs/library-plan.md`, "Contingency gap
audit"; only the items approved there may be downloaded, and the conditions apply). Batches 2 and 3 are
`kit-5`. **Previous step:** kit-1 (the `kit.py` tool). **Next step:** `kit-5`; then Claude reviews, moves the
files into the kit, and `kit-3` measures the whole new kit once.

```powershell
git fetch origin
# Branch from Claude's branch if kit-1 is merged there; otherwise from your kit-1 branch (say which in the report).
git switch -c chatgpt/kit-4 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md` ("Network and downloads"), `CLAUDE.md` ("Sources of truth": only government works or
material published free to share). Downloads go to your worktree only (`tools/kit/incoming/<date>/`), never to
Claude's checkout, never to the phone.

## Do, per approved item

1. Download from the official URL in the audit (or the publisher page it names) with `kit.py fetch` after
   adding a `url` entry to `sources.json`. Use the newest edition the audit names; if the publisher lists a
   newer one, stop and report rather than switching.
2. Check the file itself: title page, edition date, and its distribution or licence statement (quote it in the
   report). **Reject** anything marked FOUO, CUI on the document itself, "distribution authorized to …", or
   needing permission; reject a scanned PDF with no text layer (report it; do not OCR).
3. Web pages (FDA, CDC, CHEMM, EPA Burn Wise, ready.gov): save the page text as Markdown with a source header,
   using the approach in `tools/kit-build/` (README). If a site refuses command-line access (403), list it for
   Claude to copy through the browser; do not work around the block.
4. Record SHA-256, licence quote, edition, flags (`historic` for anything the publisher marks archived, e.g. EPA
   Burn Wise; `third-party-sections` for Bowditch) in `sources.json`.
5. Never download the "Not free" items (Hesperian, WHO PFA, Where There Is No Animal Doctor, FAO 1994).

## Files you may change

`tools/kit/sources.json`, converter scripts under `tools/kit-build/` (new files only), your report. Downloads stay
uncommitted in your worktree's `tools/kit/incoming/`.

## Done when

Every approved item is downloaded and checked or rejected with a reason, `kit.py verify` passes on your
incoming folder against `sources.json`, and `docs/chatgpt-tasks/kit-4-report.md` (per item: URL, edition,
licence quote, size, SHA-256, verdict) is committed.
