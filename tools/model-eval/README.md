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
