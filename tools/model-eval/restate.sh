#!/bin/bash
# bash restate.sh <model.gguf> <tag>     loads the model in llama.cpp (CPU) and writes plans/<tag>.json
cd "$(dirname "$0")"; BIN=../kit/pc-eval/llama
"$BIN/llama-server.exe" -m "../kit/pc-eval/models/$1" -c 4096 -t 4 --port 8089 --host 127.0.0.1 --jinja -rea off --no-webui > "plans/$2.server.log" 2>&1 &
for i in $(seq 1 300); do curl -s -m 2 http://127.0.0.1:8089/health | grep -q '"ok"' && break; sleep 1; done
EVALSET="${EVALSET:-evalset.json}" IDS="$IDS" perl restate.pl "$2" > "plans/$2.log" 2>&1; echo "exit=$?" >> "plans/$2.log"
powershell -NoProfile -Command "Stop-Process -Name llama-server -Force" 2>/dev/null
tail -2 "plans/$2.log"
