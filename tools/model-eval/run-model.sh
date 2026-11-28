#!/bin/bash
# Loads one model in llama.cpp on this PC (CPU) and runs the fixed set through it.
#   bash run-model.sh <model.gguf> <tag> <chat|occ> [only-id-prefix]
# Writes results/<tag>.jsonl and results/<tag>.meta (load time, peak memory, build).
cd "$(dirname "$0")"
BIN=${LLAMA_BIN:-../kit/pc-eval/llama-vulkan};  # Arc GPU build (2.6x faster, 2026-11-24); LLAMA_BIN=../kit/pc-eval/llama for CPU
 MODEL=../kit/pc-eval/models/$1; TAG=$2; MODE=$3; PORT=8089
EXTRA=""; [ "$MODE" = occ ] && EXTRA="--special"
T0=$(date +%s)
"$BIN/llama-server.exe" -m "$MODEL" -c 4096 -t 4 --port $PORT --host 127.0.0.1 -ngl 99 --jinja -rea off --no-webui $EXTRA > "results/$TAG.server.log" 2>&1 &
for i in $(seq 1 300); do curl -s -m 2 "http://127.0.0.1:$PORT/health" | grep -q '"ok"' && break; sleep 1; done
LOAD=$(( $(date +%s) - T0 ))
perl run.pl "$TAG" "$MODE" $PORT "$4"
PEAK=$(powershell -NoProfile -Command "[int]((Get-Process llama-server | Sort-Object PeakWorkingSet64 -Descending | Select-Object -First 1).PeakWorkingSet64/1MB)")
{ echo "model=$1"; echo "size_bytes=$(stat -c %s "$MODEL")"; echo "mode=$MODE"; echo "load_s=$LOAD"; echo "peak_working_set_mb=$PEAK";
  echo "runtime=llama.cpp $("$BIN/llama-server.exe" --version 2>&1 | head -1)"; echo "context=4096 threads=4 backend=$(basename $BIN) temperature=0 reasoning=off"; } > "results/$TAG.meta"
powershell -NoProfile -Command "Stop-Process -Name llama-server -Force" 2>/dev/null
cat "results/$TAG.meta"
