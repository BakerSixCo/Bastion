"""Runs the NPU job queue (tools/npu/queue.json) one job at a time, so the NPU always has work.

Owner rule (2026-11-26): the NPU should always be tasked. Jobs run in order; a job starts when every job
in its "after" list is done and it is "approved". Jobs that need the owner's OK (downloads, installs)
stay "approved": false until the owner says yes; Claude flips them. State (running/done/failed, times,
exit codes) is kept in tools/npu/out/queue-state.json; each job logs to tools/npu/out/<id>.log.
When nothing runnable is left the runner exits, so the session that started it is told and can add work.

  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\run_queue.py            # run until the queue is empty
  tools\\.venv-npu\\Scripts\\python -I tools\\npu\\run_queue.py --status   # show the queue
"""
import argparse
import datetime
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
QUEUE = ROOT / "tools" / "npu" / "queue.json"
OUT = ROOT / "tools" / "npu" / "out"
STATE = OUT / "queue-state.json"
LOCK = OUT / "queue.lock"


def now():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M")


def load_state():
    return json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}


def save_state(s):
    STATE.write_text(json.dumps(s, indent=1), encoding="utf-8")


def pid_alive(pid):
    try:
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
        return str(pid) in out
    except OSError:
        return False


def runnable(jobs, state):
    for j in jobs:
        st = state.get(j["id"], {}).get("status", "pending")
        if st in ("done", "failed") or not j.get("approved") or not j.get("cmd"):
            continue
        if all(state.get(d, {}).get("status") == "done" for d in j.get("after", [])):
            return j
    return None


def status(jobs, state):
    for j in jobs:
        s = state.get(j["id"], {})
        flag = "" if j.get("approved") else "  [needs owner OK: " + j.get("needs", "") + "]"
        if not j.get("cmd"):
            flag += "  [to build]"
        print(f"{s.get('status', 'pending'):8} {j['id']:28} ~{j.get('est_hours', '?')} h  {j['title']}{flag}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--status", action="store_true")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = json.loads(QUEUE.read_text(encoding="utf-8"))["jobs"]
    state = load_state()
    if a.status:
        return status(jobs, state)

    if LOCK.exists():
        old = LOCK.read_text().strip()
        if old.isdigit() and pid_alive(int(old)):
            print(f"queue already running (pid {old})")
            return 1
    LOCK.write_text(str(os.getpid()))
    try:
        while True:
            jobs = json.loads(QUEUE.read_text(encoding="utf-8"))["jobs"]   # re-read: Claude may add jobs
            state = load_state()
            j = runnable(jobs, state)
            if j is None:
                print(f"{now()} queue empty: nothing approved and runnable. Add jobs to tools/npu/queue.json.")
                return 0
            state[j["id"]] = {"status": "running", "started": now()}
            save_state(state)
            print(f"{now()} start {j['id']}: {j['title']}", flush=True)
            with (OUT / f"{j['id']}.log").open("a", encoding="utf-8") as log:
                log.write(f"=== {now()} start\n")
                log.flush()
                cmd = [sys.executable if c == "{python}" else c for c in j["cmd"]]
                rc = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT).returncode
            state = load_state()
            state[j["id"]].update({"status": "done" if rc == 0 else "failed", "finished": now(), "exit": rc})
            save_state(state)
            print(f"{now()} {state[j['id']]['status']} {j['id']} (exit {rc})", flush=True)
    finally:
        LOCK.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
