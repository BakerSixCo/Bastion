# Kit converter regression report

Six offline suites exercise the real converters against small UTF-8/LF goldens. Every suite reruns the conversion and checks the bytes. The checked-in fixture directory pins LF line endings. The work starts from `d7c7fc0` and uses Python’s standard library.

## Coverage

| Converter | Fixture and regression |
| --- | --- |
| `ecfr2md.py` | Named GPOTABLE and HTML TABLE cases cover captions, headers, notes, subpart filtering, inline breaks and padded short rows. The two excerpts were reconstructed from the accepted Parts 95 and 97 Markdown because the exact source XML was unavailable locally. Synthetic notes and rows are identified in the fixture. |
| `ncvec2md.py` | Small records reconstructed from the accepted NCVEC question-and-answer Markdown cover bracketed FCC references, a keyed answer with no space before the bracket, records without a rule reference, and omission of the distractors. The synthetic distractors and source formatting are identified in the fixture. |
| `meshdocs.py` | Short excerpts of the accepted guide reconstruct two Docusaurus pages. A synthetic serial-driver page exercises sorting. The test reverses creation order and compares LF and CRLF inputs while checking the provenance and the authoritative body heading. |
| `meshtrim.py` | The two accepted Meshtastic excerpts contain a site label that differs from its `###` page heading. Trimming retains both labels in their original roles and drops the synthetic out-of-scope pages. The actual accepted guide uses the same distinction. |
| `nccih.py` | A reconstructed local index and six short NCCIH excerpts test fixed-page order, deduplication, skipped navigation links, page-title precedence, entities and removal of navigation and footers. All HTTP responses are mocked. |
| `bodyhash.py` | An FCC consumer-guide excerpt and a typographic-quote variant test tabs, CRLF, reflowed whitespace and console-independent UTF-8 output. Straight and curly quote glyphs remain distinct in the content hash. |

## Changes and verification

- **Compiled and ran on PC.** All 12 converter and test files syntax-compiled. Nine tests passed across the six modules via `python -I tools/kit-build/test_<name>.py`. The README documents the all-suite command. Fixture outputs reproduced byte for byte.
- **Body-hash encoding bug reproduced and fixed.** With Windows-style stdout, the old preview encoded curly quote glyphs as bytes `93` and `94` in CP1252 and printed CRLF. The new preview always emits the UTF-8 encodings and LF. Both old digest values matched their new counterparts. The test exercises this exact console setup.
- **NCCIH file handling bug reproduced and fixed.** Running the old script within the fixture harness generated an unclosed-file warning for both input and output. Context managers close both handles. The same fixture still matches its golden byte for byte.
- **Local NCVEC reconstruction tested.** The approved 409-item Markdown was converted back into raw-shaped records by preserving its questions, headings, references and keyed answers and inserting three synthetic distractors per record. Two isolated CLI conversions reported `409 questions; 0 problems` and each reproduced the original 63,066 bytes. SHA-256 of the accepted artifact matches the manifest. The original pre-conversion official pool was unavailable locally, so an official-input full rerun remains untested.
- **Local kit artifact integrity tested.** The two eCFR documents, NCVEC answer Markdown, NCCIH document and Meshtastic guide all matched their pinned SHA-256. Re-trimming the accepted 77-page Meshtastic guide also reproduced all 636,115 bytes. The raw eCFR XML and full raw NCCIH page set were unavailable locally.
- **App launch and device tests were untested.** This was an offline PC converter task.
