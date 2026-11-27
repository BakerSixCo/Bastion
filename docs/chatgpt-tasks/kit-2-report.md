# Kit-2 report

Branch `chatgpt/kit-2`, based on `origin/claude/hello-e9r4rq` at `49125b5`.

## Changes

- `android/app/src/main/assets/library/exclude.json` defines the six owner-audited title rules with the requested reason. `BundleExclusions.kt` parses the four-field records, matches the selected ZIM filename prefix, and checks the complete normalized title or literal archive path.
- `LibraryStore.importKit` checks the archive path before reading a matching entry and checks its resolved display title before indexing. Existing matches require bundle provenance. Cleanup reuses the document, chunks, and FTS deletion transaction. Home shows the number of distinct documents left out. The import reconsiders an owner-folder file that had been skipped only because it duplicated a bundle copy.
- The tested post-disaster archive contains numbered PDF paths. The six reviewed PDFs were identified by the PDF's cover or title metadata. Two paths contain identical copies of Personal Wilderness EMS. The matching table binds the numbered paths to the exact May 2024 archive and the active title rules, and binds older copies to their exact original archive and PDF hash.

## Validation

- **Compile and package, tested on PC.** Temurin JDK 21 and the local Android SDK ran `.\gradlew.bat --offline --no-daemon '-Pandroid.builder.sdkDownload=false' :app:testDebugUnitTest :app:assembleDebug` from `android`.
- **JVM correctness, tested on PC.** There were 252 passing tests and two state-pack skips with `BASTION_MAPS` unset. All eight new `BundleExclusionsTest` cases passed, along with the unchanged `SourceTierTest` and `NetworkGuardTest`. The bundle tests simulate reads, indexing, and removal eligibility, including exact legacy digest binding and identical titles from owner and picker sources.
- **Pack scope, tested on the API 37 emulator.** Built `:app:assembleDebugAndroidTest` offline, installed the test APK with `adb install -r --streaming`, and ran `adb shell am instrument -w -e class com.bastion.terminal.data.PackScopeTest com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner`. One test passed.

## Emulator evidence

The API 37 emulator had six SHA-identical library rows from the two earlier constituent archives, `zimgit-medicine_en_2024-08.zim` and `zimgit-knots_en_2024-08.zim`. The baseline app scanned a read-only copy of the existing May 2024 post-disaster ZIM, staged over USB from Claude's local kit. It indexed 81 additional documents. The new bundle recognized the six existing rows as duplicates. The baseline includes all 87 distinct bundle documents from that test, with 89 other library rows.

| Reviewed title | Path in the post-disaster archive | Existing chunks |
| --- | --- | ---: |
| Berkeley, California, USA | `files/First Aid and Medicine (2).pdf` | 1,710 |
| Wound Closure Manual | `files/First Aid and Medicine (5).pdf` | 631 |
| Survival and Austere | `files/First Aid and Medicine (7).pdf` | 901 |
| Personal Wilderness EMS | `files/First Aid and Medicine (8).pdf` and `files/Emergency Preparedness (9).pdf` | 100 |
| under the tongue diagnosis.doc | `files/First Aid and Medicine (9).pdf` | 30 |
| uta4394.tmp | `files/Knots (6).pdf` | 61 |

**Migration and retention, tested on the API 37 emulator.** After `adb install -r --streaming` of the x86_64 debug APK, the existing app was restarted with `adb shell am force-stop com.bastion.terminal` and `adb shell am start -W -n com.bastion.terminal/.MainActivity`. The app started normally and ran the import. Read-only SQLite checks via `adb exec-out run-as com.bastion.terminal cat databases/bastion.db` checked the prior document and chunk IDs. The output below is from those checks.

| Rows | Before | After |
| --- | ---: | ---: |
| Distinct documents from this bundle test | 87 | 81 |
| Reviewed documents | 6 | 0 |
| Reviewed document FTS rows | 6 | 0 |
| Reviewed chunks | 3,433 | 0 |
| Reviewed chunk FTS rows | 3,433 | 0 |
| Other library rows | 89 | 89 |

The ID and SHA-256 fingerprints matched for the 81 retained bundle documents and the 89 other library rows.

**Home, tested on the API 37 emulator.** The Library card showed 170 indexed documents and “Left out 6 document(s) on the exclude list.” Tapped Home → Offline reference → Rescan and repeated the SQLite checks. The same 170 documents remained and the reviewed documents, chunks, and FTS rows stayed absent.

## Limits

The Home Rescan handler refreshes the ZIM inventory. The normal app launch invokes `importKit` after that inventory refreshes. The task confines changes to the import and rule files, so the two actions are still wired separately. A generic future path-only rule can identify legacy rows by digest when its source archive is available. The six audited compatibility hashes can find old rows even when those constituent archives are detached. Physical phones were not tested.
