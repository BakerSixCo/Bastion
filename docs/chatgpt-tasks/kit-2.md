# Task kit-2: leave unvetted documents out of reference bundles (branch `chatgpt/kit-2`)

**Queue:** after `kit-1`. **Previous step:** kit-1 made the kit manifest. **Next step:** `kit-3` loads the
kit on the emulator, where this exclude list must already work.

```powershell
git fetch origin
git switch -c chatgpt/kit-2 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md` ("Sources of truth", "Packs"). No network use.

## Why

`LibraryStore.importKit` adds every PDF inside a bundle ZIM (e.g. `zimgit-post-disaster_en_2024-05.zim`) to
the library at the bundle's tier. `docs/library-plan.md` says to vet these one by one; some have no nameable
origin. Today they cannot be left out individually.

## Build

1. An exclude list, as data, in the app: `android/app/src/main/assets/library/exclude.json`
   (`[{ "zim": "<file-name prefix>", "match": "title" | "path", "value": "...", "reason": "..." }]`).
   Seed it with exactly these from `zimgit-post-disaster`, reason "origin unknown (owner/Claude audit
   2026-11-26)": the documents titled "under the tongue diagnosis.doc", "Berkeley, California, USA",
   "Personal Wilderness EMS", "Survival and Austere", "uta4394.tmp", and the commercial "Wound Closure Manual".
   Match on the titles BASTION shows (the emulator's library lists them; see `docs/library-plan.md`).
2. `importKit` skips excluded documents, and on the next import **removes** ones already in the library
   (document, chunks and FTS rows), reporting "Left out N document(s) on the exclude list" on Home.
3. The list never affects the owner's own docs folder or file-picker imports.
4. Tests (JVM, Robolectric not required if you keep matching pure): parsing the list, matching by title and by
   path, a bundle PDF that is excluded is never indexed and an already-indexed one is removed; the docs folder is
   untouched. Keep `PackScopeTest`, `SourceTierTest`, `NetworkGuardTest` passing unchanged.

## Files you may change

`android/app/src/main/java/com/bastion/terminal/data/LibraryStore.kt` (import only), a new small file in
`data/` for the list, the asset above, tests under `android/app/src/test/`, your report.

## Done when

`./gradlew :app:testDebugUnitTest :app:assembleDebug` (JDK 21) pass; on the emulator (`adb install -r`, Home →
Rescan) the six documents are gone from the library and everything else from the bundle is still there (count
before/after in the report; never `connectedAndroidTest`); `docs/chatgpt-tasks/kit-2-report.md` committed.
