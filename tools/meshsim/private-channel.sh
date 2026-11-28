#!/usr/bin/env bash
# Adds a private, encrypted channel "Bastion" (random 256-bit key) to both
# simulated nodes. Run as root in WSL. The key stays in /var/lib/meshsim-psk (WSL only).
set -e
M=/opt/meshcli/bin/meshtastic
umask 077
[ -s /var/lib/meshsim-psk ] || { head -c 32 /dev/urandom | base64 -w0 > /var/lib/meshsim-psk; }
K=$(cat /var/lib/meshsim-psk)
for p in 4403 4404; do
  if ! $M --host localhost:$p --info 2>/dev/null | grep -q '"name": "Bastion"'; then
    $M --host localhost:$p --ch-add Bastion >/dev/null 2>&1 || true
    sleep 4
    $M --host localhost:$p --ch-index 1 --ch-set psk "base64:$K" >/dev/null 2>&1 || true
    sleep 4
    # Name it after the key. Channel names are at most 11 characters (a 14-character name was silently dropped, 2026-11-22).
    $M --host localhost:$p --ch-index 1 --ch-set name Bastion >/dev/null 2>&1 || true
    sleep 4
  fi
  echo "node :$p channels: $($M --host localhost:$p --info 2>/dev/null | grep -c -E 'Index [0-9]')" \
       "private=$($M --host localhost:$p --info 2>/dev/null | grep -c '"name": "Bastion"')"
done
echo 'Send on the private channel:  meshtastic --host localhost:4404 --ch-index 1 --sendtext "?question"'
