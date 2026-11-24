#!/bin/bash
# bash gate.sh <model.gguf> <tag> [plans.json]    runs gate.pl over $EVALSET with the model loaded in llama.cpp (CPU)
cd "$(dirname "$0")"; BIN=../kit/pc-eval/llama
"$BIN/llama-server.exe" -m "../kit/pc-eval/models/$1" -c 4096 -t 4 --port 8089 --host 127.0.0.1 --jinja -rea off --no-webui > "results/$2.server.log" 2>&1 &
for i in $(seq 1 300); do curl -s -m 2 http://127.0.0.1:8089/health | grep -q '"ok"' && break; sleep 1; done
PLANS="$3" perl gate.pl "$2" > "results/$2.run.log" 2>&1; echo "exit=$?" >> "results/$2.run.log"
powershell -NoProfile -Command "Stop-Process -Name llama-server -Force" 2>/dev/null
tail -1 "results/$2.run.log"
