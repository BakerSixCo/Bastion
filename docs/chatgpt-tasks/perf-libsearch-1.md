# Task perf-libsearch-1: debounce Library live search (branch `chatgpt/perf-libsearch-1`)

**Runnable now, independent.** Emulator to confirm behaviour. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/perf-libsearch-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not touch the owner/Claude-only zones. Do not change what `store.search` returns — only when it is called.

## Why

`ui/LibraryScreen.kt:84-86` `LaunchedEffect(query, tick){ hits = if (query.isBlank()) emptyList() else store.search(query) }` fires a full FTS `store.search()` (`data/LibraryStore.kt:501`, on `Dispatchers.IO`) on **every keystroke**. For a large library that is wasted work and result flicker. (Ask uses an explicit Search button, so only Library is affected.)

## Do

1. Debounce the query by ~250 ms before searching: e.g. `snapshotFlow { query }.debounce(250).mapLatest { q -> if (q.isBlank()) emptyList() else store.search(q) }.collect { hits = it }`, keeping the `tick`/`changeCounter` refresh. `mapLatest` cancels a superseded search. Blank query still clears immediately.
2. Confirm results are identical to the un-debounced path once typing stops.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/ui/LibraryScreen.kt`; your report. Nothing in `data/` (that is `chunk-tables-1`'s area).

## Done when

Typing a query quickly runs `store.search()` **once** after typing stops (log the call count before/after); results match the old behaviour; blank clears instantly; no retrieval eval is affected (this is the Library search path, not AI retrieval); `:app:assembleDebug` builds and runs on the emulator; `docs/chatgpt-tasks/perf-libsearch-1-report.md` is committed.
