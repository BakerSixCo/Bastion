# Answer-model comparison

Runs candidate answer models on one fixed set of questions and passages, so a difference in
scores is a difference between models and not between searches.

## What is here

| File | What it is |
|---|---|
| `evalset-retrieved.json` | 38 real questions with the passages BASTION's own search returns, written on the phone by `EvalSetExportTest` |
| `build-set.pl` | Adds hand-written conflicting and partial cases and six planted-instruction cases; writes `evalset.json` |
| `evalset.json` | The fixed set: 50 cases |
| `run-model.sh`, `run.pl` | Load one GGUF model in llama.cpp on the PC and run the set through it |
| `score.pl` | Scores the runs and prints the comparison (`-v` lists every verdict) |
| `overrides.json` | Verdicts set by reading the answer, where word matching got it wrong, each with the reason |
| `show.pl` | Prints what each model answered for given case ids |
| `results/` | Raw answers (`*.jsonl`), run details (`*.meta`) and the last comparison (`summary.md`) |

Models and the llama.cpp build live in `tools/kit/pc-eval/` and are never committed.

## Replay the archived comparison

From `tools/model-eval`, choose fresh run tags and use the checked-in `evalset.json`.

```bash
EVALSET=evalset.json bash run-model.sh gemma-4-E4B-it-Q4_0.gguf replay-e4b chat
EVALSET=evalset.json bash run-model.sh OCC-RAG-1.7B-Q8_0.gguf replay-occ occ
perl score.pl -v replay-e4b replay-occ
```

To rebuild the legacy set, export the default `EvalSetExportTest#writeTheFixedSet` to a scratch
directory as `evalset-retrieved.json`. Run `perl /path/to/build-set.pl` from that directory because
the script writes a fixed `evalset.json` filename.

`chat` sends BASTION's own instructions and source layout. `occ` sends OCC-RAG's own layout
(query and sources in its special tokens, no instructions) and reads its ANSWERABLE /
UNANSWERABLE status and answer section.

## What the numbers are and are not

- Run on a Windows laptop (Intel i5-1135G7, 4 cores, CPU only) in llama.cpp build 12651, context
  4096, temperature 0, thinking off. These are **not** the LiteRT-LM files the phone loads and
  **not** phone measurements. Speed and memory figures say nothing about a phone.
- The peak-memory figure counts the mapped model file and is not a guide to phone memory.
- 50 cases, one run each. A difference of one or two cases between models is not a finding.
- Scoring is by expected words, refusal wording, forbidden words and the planted word, with
  hand corrections in `overrides.json`. It does not check every sentence of every answer.

## Checked answers (2026-11-24)

Measuring whether BASTION can avoid ever showing a wrong answer. The question set is
`questions-v2.json`: 156 backcountry, survival and prepping questions across 16 topics, mixing
plain wording, slang, "do X with Y", look-alikes, dangerous guesses and things the library
cannot know.

| File | What it is |
|---|---|
| `questions-v2.json` | The 156 questions (`cat` answerable/absent, `style`, `expect` words) |
| `evalset-v2-retrieved.json` | Their passages from the app's search (emulator, `EvalSetExportTest -e questions`) |
| `restate.pl`, `restate.sh` | Gemma rewrites each question into a search plan (`EVALSET=… IDS=.`) |
| `evalvariants-v2-w0.json`, `mkarm.pl` | The app's search on each restated wording; `mkarm.pl` builds a set from one wording |
| `gate.pl`, `gate.sh` | Tried and rejected: the model fills a form (quote, verdict, answer) and code decides |
| `cover.pl`, `relcheck.pl` | Tried: subject-word coverage (kept, now in the app) and a sentence-level same-subject check (rejected) |
| `plaincheck.pl`, `cover-lib.pl` | The code checks the app uses (`CheckedAnswer.check`), on free-text answers |
| `ansjudge.pl` | The final answer judge the app uses (`CheckedAnswer.judge`) |
| `agree.pl` | Passage-level agreement between searches (measured, not used) |
| `gscore.pl` | Scores a run: answerable / library gap / trap, with `-v` to read every shown answer |
| `read-labels-v2.json` | Which shown answers were read as bad, and why |

The app's pipeline (`CheckedAnswer.twoPaths`) is: restate → search the restated keywords →
answer → code checks + judge → search the asker's own words → answer → checks + judge → show
the restated path's answer only if both passed. The on-device run of exactly that is
`CheckedAnswerEvalTest` (results in `results/device-v2-twopaths.jsonl`).

The PC harness uses the device's exported passages and a separate llama.cpp runtime. It generates
both answer arms before joining their verdicts. Use `radio-questions.json` for radio-card tuning
and `radio-questions-holdout.json` as the frozen radio holdout. Those sets belong to the radio eval,
while `read-labels-v2.json` records human-reviewed checked-answer verdicts.

## Run the PC checked-answer comparison

Run the wrappers from `tools/model-eval` in Windows Bash with Perl, curl, standard shell utilities
and Windows PowerShell on PATH. Place the existing GGUF files in `tools/kit/pc-eval/models/` and
the Windows `llama-server.exe` Vulkan build and its libraries in `tools/kit/pc-eval/llama-vulkan/`.
`pipeline.sh` defaults to `gemma-4-E2B-it-Q4_0.gguf`. `MODEL_FILE` selects another model in the
same folder. `LLAMA_BIN` selects the server directory. The wrappers use loopback port 8089 and
stop processes named `llama-server` on exit, so reserve the server for one eval at a time.

1. **Restate.** Set a fresh tag for this run and generate query plans and alternate search wordings.

   ```bash
   run_tag=fresh-v2-run
   plan_tag="$run_tag-plan"
   EVALSET=questions-v2.json IDS=. bash restate.sh gemma-4-E2B-it-Q4_0.gguf "$plan_tag"
   ```

   With a server already listening, the equivalent planner invocation is
   `EVALSET=questions-v2.json IDS=. perl restate.pl "$plan_tag" 8089`.
   It writes `plans/<tag>.json` for the checker and `plans/<tag>-queries.json` for Android search.
   The wrapper also records the planner and server logs.

2. **Search on Android.** Install the debug and instrumentation APKs with `adb install -r`.
   Use the same library and ZIMs for both exports. The commands below use the usual app media path.
   The `EvalSet` log records the exact output path.
   In Git Bash, set `MSYS_NO_PATHCONV=1` for the `adb` commands if it translates `/sdcard` paths.

   ```bash
   media=/sdcard/Android/media/com.bastion.terminal
   eval_class=com.bastion.terminal.ai.EvalSetExportTest
   runner=com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner
   adb push questions-v2.json "$media/questions-$run_tag.json"
   adb push "plans/$plan_tag-queries.json" "$media/$plan_tag-queries.json"
   adb shell am instrument -w -e class "$eval_class#writeTheFixedSet" \
     -e questions "questions-$run_tag.json" -e wide 0 "$runner"
   adb pull "$media/evalset-questions-$run_tag.json" "evalset-$run_tag-input.json"
   adb shell am instrument -w -e class "$eval_class#searchOtherWordings" \
     -e queries "$plan_tag-queries.json" -e wide 0 "$runner"
   adb pull "$media/evalvariants-w0.json" "evalvariants-$run_tag-w0.json"
   ```

3. **Answer, check and judge on the PC.** Pass the plan that generated the variant export.

   ```bash
   bash pipeline.sh "$run_tag" "evalset-$run_tag-input.json" \
     "evalvariants-$run_tag-w0.json" "plans/$plan_tag.json"
   ```

   `mkarm.pl` builds the keyword arm from the variant export and falls back to the raw export
   when a keyword variant record is absent. Both arms go through `run.pl` for generation,
   `plaincheck.pl` for the code checks, and `ansjudge.pl` for the answer judge. `pipeline.pl`
   emits the keyword answer for cases where both paths pass and reports the stage for each case.

| Run file | Contents |
| --- | --- |
| `evalset-<tag>-kw.json`, `evalset-<tag>-raw.json` | Exact passages supplied to each arm |
| `results/<tag>.server.log`, `results/<tag>-{kw,raw}.run.log` | Server and answer-run diagnostics |
| `results/<tag>-{kw,raw}.jsonl` | Raw model answers, timings and token usage |
| `results/<tag>-{kw,raw}-checked.jsonl`, `results/<tag>-{kw,raw}-checked-judge.jsonl` | Code-check and judge verdicts |
| `results/<tag>.jsonl` | Joined decision and the answer shown or withheld per case |

Use fresh tags to preserve checked-in evidence. Git ignores newly generated results, intermediate
exports, logs, summaries and Python bytecode. Existing tracked question sets, exports and historical
results remain visible to Git. New plan JSON stays visible so it can be kept as a replay input.

## Arc GPU (2026-11-24)

The PC scripts (`run-model.sh`, `restate.sh`, `gate.sh`) now use the official llama.cpp **Vulkan**
build (b12651, same version as the CPU build; SHA-256 checked against GitHub) in
`tools/kit/pc-eval/llama-vulkan/`, with every layer on the laptop's Intel Arc 140V
(`-ngl 99`). `llama-bench`, Gemma 4 E2B Q4_0: prompt 368 → 4,452 tokens/s (12×), generation
30 → 49 tokens/s (1.6×). 20 questions: 185 s → 78 s, identical scores, wording differs only
in phrasing (`results/sub20-llama*.jsonl`). `LLAMA_BIN=../kit/pc-eval/llama` runs on the CPU.
The laptop's NPU is not used (llama.cpp has no Intel NPU backend; the Arc chip is faster for
this anyway). None of this changes the phone: its speed depends on its own chip.
