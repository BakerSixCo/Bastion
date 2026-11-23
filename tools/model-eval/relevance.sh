#!/bin/bash
# bash relevance.sh <model.gguf> <emb|rerank> <tag> [query-task]
cd "$(dirname "$0")"; BIN=../kit/pc-eval/llama
FLAG="--embedding"; [ "$2" = rerank ] && FLAG="--reranking"
"$BIN/llama-server.exe" -m "../kit/pc-eval/models/$1" -c 4096 -b 4096 -ub 4096 -t 4 --port 8089 --host 127.0.0.1 $FLAG --no-webui > "results/relevance-$3.server.log" 2>&1 &
for i in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8089/health | grep -q '"ok"' && break; sleep 1; done
perl relevance.pl "$2" "$3" "$4"
powershell -NoProfile -Command "Stop-Process -Name llama-server -Force" 2>/dev/null
