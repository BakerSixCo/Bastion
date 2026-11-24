#!/bin/bash
# Starts the reranker in llama.cpp and reranks the candidate pools. Safe to rerun: finished questions are kept.
cd "$(dirname "$0")"; BIN=../kit/pc-eval/llama
"$BIN/llama-server.exe" -m ../kit/pc-eval/models/qwen3-reranker-0.6b-q8_0.gguf -c 4096 -b 4096 -ub 4096 -t 4 --port 8089 --host 127.0.0.1 --reranking --no-webui > results/poolrank.server.log 2>&1 &
for i in $(seq 1 120); do curl -s -m 2 http://127.0.0.1:8089/health | grep -q '"ok"' && break; sleep 1; done
T0=$(date +%s); perl poolrank.pl > results/poolrank.log 2>&1; echo "exit=$? seconds=$(( $(date +%s) - T0 ))" >> results/poolrank.log
powershell -NoProfile -Command "Stop-Process -Name llama-server -Force" 2>/dev/null
tail -3 results/poolrank.log
