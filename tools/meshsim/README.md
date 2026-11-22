# Mesh simulator (PC development, no radio hardware)

Two simulated Meshtastic nodes (`meshtasticd --sim`, Meshtastic's official
Linux build) running in WSL Ubuntu, meshed with each other. Simulated — no RF.

- Install once (WSL, as root): `wsl -d Ubuntu -u root -- bash tools/meshsim/install.sh`
- Sim A: `localhost:4403` — the Meshtastic app on the emulator connects here
  (Connection → Network → Add device manually → `10.0.2.2`).
- Sim B: `localhost:4404` — the "other person". Send from it:
  `tools\meshsim\send.cmd "Radio check"`.
- Everything at once: double-click `tools\bastion-pc.cmd`.

Each node runs in its own network namespace on a bridge: the nodes mesh over
Meshtastic's UDP multicast, which only one process per network stack can bind.
