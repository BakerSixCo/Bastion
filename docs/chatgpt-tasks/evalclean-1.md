# Task evalclean-1: tidy and document the model-eval harness (branch `chatgpt/evalclean-1`)

**Runnable now, independent, low-risk.** PC-only. Start from Claude's branch:

```powershell
git fetch origin
git switch -c chatgpt/evalclean-1 origin/claude/hello-e9r4rq
```

Rules: `AGENTS.md`, `CLAUDE.md`. No network. Do not change the content of any tracked question set or eval result.

## Why

`tools/model-eval/` has ~35 untracked generated files cluttering `git status` (`results/*.server.log`, `results/*.jsonl`, `evalset-*.json`, `evalvariants-*.json`, `*.summary.txt`, `plans/*.log`, `__pycache__`). They are run artifacts, not source, and they hide real changes. The pipeline (restate → search → answer → check → judge, run on the PC's Arc GPU llama.cpp build) also has no single readme. Make the folder clean and the pipeline documented, without losing reproducibility.

## Do

1. **`tools/model-eval/.gitignore`** that ignores the generated artifacts (`results/`, `*.server.log`, `plans/*.log`, intermediate `evalset-*.json` / `evalvariants-*.json` exports, `*.summary.txt`, `__pycache__/`) while **keeping the tracked source** — the hand-written question sets (`questions-v2.json`, `radio-questions*.json`, `read-labels-v2.json`, and the like) and the scripts (`*.pl`, `pipeline.sh`). List exactly what becomes ignored vs stays tracked; if a file's status is unclear, ask in the report rather than guessing.
2. **`tools/model-eval/README.md`**: from the existing scripts, document what the harness does and how to run it — the question sets and their purpose, `restate.pl`, `pipeline.sh`, where the GGUF models live (`tools/kit/pc-eval/models`), the Arc GPU llama.cpp Vulkan build (`tools/kit/pc-eval/llama-vulkan`), and how a run's outputs map to `results/`. Describe, don't re-run.
3. Do not delete tracked files. Do not alter any question set's content or any committed result.

## Files you may change

`tools/model-eval/.gitignore` (new), `tools/model-eval/README.md` (new or updated), your report. Nothing else.

## Done when

`git status` under `tools/model-eval/` is clean of generated clutter (ignored, not deleted), the README explains the pipeline from the real scripts, and `docs/chatgpt-tasks/evalclean-1-report.md` lists what was ignored vs kept.
