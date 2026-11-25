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

## To rerun

    adb shell am instrument -w -e class com.bastion.terminal.ai.EvalSetExportTest com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner
    adb pull /sdcard/Android/media/com.bastion.terminal/evalset.json evalset-retrieved.json
    perl build-set.pl
    bash run-model.sh gemma-4-E4B-it-Q4_0.gguf gemma4-e4b-q4 chat
    bash run-model.sh OCC-RAG-1.7B-Q8_0.gguf occ-rag-1.7b-q8 occ
    perl score.pl -v gemma4-e2b-q4 gemma4-e4b-q4 qwen3.5-4b-q4km occ-rag-1.7b-q8

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

## Arc GPU (2026-11-24)

The PC scripts (`run-model.sh`, `restate.sh`, `gate.sh`) now use the official llama.cpp **Vulkan**
build (b12651, same version as the CPU build; SHA-256 checked against GitHub) in
`tools/kit/pc-eval/llama-vulkan/`, with every layer on the laptop's Intel Arc 140V
(`-ngl 99`). `llama-bench`, Gemma 4 E2B Q4_0: prompt 368 → 4,452 tokens/s (12×), generation
30 → 49 tokens/s (1.6×). 20 questions: 185 s → 78 s, identical scores, wording differs only
in phrasing (`results/sub20-llama*.jsonl`). `LLAMA_BIN=../kit/pc-eval/llama` runs on the CPU.
The laptop's NPU is not used (llama.cpp has no Intel NPU backend; the Arc chip is faster for
this anyway). None of this changes the phone: its speed depends on its own chip.
