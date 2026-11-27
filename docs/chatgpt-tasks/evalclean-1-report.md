# Model-eval cleanup report

Added scoped ignore rules and documented the offline answer-comparison workflow. The branch is based on `d7c7fc0`.

## What Git ignores

| Rule | Generated paths covered |
| --- | --- |
| `/results/` | Run JSONL, score summaries, metadata and logs under the results directory |
| `*.server.log`, `/plans/*.log` | Server logs and top-level planner logs |
| `/evalset-*.json`, `/evalvariants-*.json` | Intermediate passage and variant exports at the harness root |
| `*.summary.txt`, `__pycache__/` | Text summaries and Python interpreter caches |

The active worktree had zero generated untracked files at baseline. A read-only filename inventory from Claude’s model-eval folder found 35 on-disk paths absent from this branch’s index. All 35 matched the new rules. They were 30 files under `results/`, `plans/plan-e2b-v2.server.log`, `evalset-v2-allvariants.json`, `evalset-v3-kw.json`, `evalset-v3-raw.json` and `evalvariants-v3-w0.json`. No scratch files were copied or deleted.

All 124 previously tracked paths remain tracked. That includes `questions-v2.json`, both radio sets, `read-labels-v2.json`, `evalset.json`, historical exports, plans and results, and the Perl and Bash harness scripts. New plan and query JSON stays reviewable because it can be used as an exact replay input.

## Verification

- **Correctness tested on PC.** `git ls-files` identified the tracked set, and a NUL-delimited `git check-ignore --stdin -z` verified all 35 sibling filename probes. The same probe on the 124 tracked paths returned no ignored paths. The active worktree showed only the intended ignore and README changes under the harness.
- **Documentation checked against source.** Matched the Android export method names, authoring inputs, output paths, server setup, per-arm files, and joined results to the existing implementation. All four examples passed Bash syntax-only checks with `bash -n`.
- **Model pipeline and device runs untested.** This task documents them without running them.
