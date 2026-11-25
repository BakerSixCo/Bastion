#!/bin/bash
# Full checked-answer pipeline on the PC (Arc GPU): about 5-10 minutes for 156 questions.
#   bash pipeline.sh <tag> <raw set.json> <variants.json> [plans.json]
# raw set: EvalSetExportTest writeTheFixedSet output; variants: searchOtherWordings output (wide 0).
# Steps: build the keyword-wording set, answer both sets with Gemma E2B, run the code checks
# (plaincheck.pl) and the answer judge (ansjudge.pl), then combine like CheckedAnswer.twoPaths.
set -e
cd "$(dirname "$0")"
TAG=$1; RAW=$2; VAR=$3; PLANS=${4:-plans/plan-e2b-v2.json}
BIN=${LLAMA_BIN:-../kit/pc-eval/llama-vulkan}; MODEL=../kit/pc-eval/models/${MODEL_FILE:-gemma-4-E2B-it-Q4_0.gguf}
perl mkarm.pl "$RAW" "$VAR" keyword "evalset-$TAG-kw.json" > /dev/null
[ "$RAW" -ef "evalset-$TAG-raw.json" ] || cp "$RAW" "evalset-$TAG-raw.json"
"$BIN/llama-server.exe" -m "$MODEL" -c 4096 -t 4 --port 8089 --host 127.0.0.1 -ngl 99 --jinja -rea off --no-webui > "results/$TAG.server.log" 2>&1 &
for i in $(seq 1 300); do curl -s -m 2 http://127.0.0.1:8089/health | grep -q '"ok"' && break; sleep 1; done
t0=$(date +%s)
for arm in kw raw; do
  EVALSET="evalset-$TAG-$arm.json" perl run.pl "$TAG-$arm" chat 8089 > "results/$TAG-$arm.run.log" 2>&1
  EVALSET="evalset-$TAG-$arm.json" PLANS="$PLANS" perl plaincheck.pl "$TAG-$arm" > /dev/null
  EVALSET="evalset-$TAG-$arm.json" perl ansjudge.pl "$TAG-$arm-checked" 8089 > /dev/null
done
powershell -NoProfile -Command "Stop-Process -Name llama-server -Force" 2>/dev/null || true
echo "model work: $(( $(date +%s) - t0 )) s"
perl pipeline.pl "$TAG"
