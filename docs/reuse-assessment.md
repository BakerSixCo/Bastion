# Reuse assessment — what we reuse vs. what we build

**Honesty note:** compiled from engineering knowledge current to ~Jan 2026 in
a cloud session; the pinned claims to re-verify at integration time are
marked ⚠. This assessment decides Milestone 1 architecture only; M2/M3
choices get their own verification pass when they start.

## Components

**Kiwix Android** — mature, maintained ZIM reader; the right owner of
"all of Wikipedia offline." ⚠ Verify current version's intent/content-
provider surface before claiming any programmatic integration; v0.1 only
detects and launches it. Do **not** assume BASTION can read Kiwix's private
ZIM storage — cross-app file access requires SAF grants or a supported API.

**AnythingLLM Mobile** — exists for Android with local-model support;
closest "finished local-AI app." Reasons it is not the BASTION core: its
RAG pipeline and UI are its own product decisions, custom tool/MCP
extensibility on mobile is ⚠ unverified, and BASTION's value is the
integrated field terminal (library + comms status + doctrine), not a chat
app. Worth keeping installed as a benchmark/fallback during M2.

**llama.cpp family** — the de-facto mobile inference base (GGUF, 4-bit
quants, arm64 NEON). Android paths: JNI binding in-app, or Google MediaPipe
LLM Inference API, or MLC-LLM. ⚠ All three move fast; pick at M2 start
against: actively maintained Android artifact, GGUF/model availability,
RAM behavior, license. Not needed for M1.

**ATAK-CIV** — official civilian TAK client (TAK.gov / Play Store). Plugin
SDK exists but plugin development is its own project with its own signing
realities. M1/M3 posture: launch + document supported intents; a BASTION
ATAK plugin only if a demonstrated need survives M3.

**Meshtastic Android** — maintained; owns the BLE connection to the node.
Rule: one app owns the radio connection — BASTION must not compete for BLE.
Supported outward surfaces to evaluate at M3: ⚠ current ATAK-forwarder
status and any intent/IPC surface in the current release. v0.1 detects and
launches only. Meshtastic ↔ ATAK integration claims from older plugin
articles are stale; verify against current docs when M3 starts.

**Organic Maps** — maintained offline OSM navigation; covers "offline maps"
for v0.1 as a launched companion. Deep map embedding (own tiles in-app)
is a later decision; MapLibre would be the candidate if ever justified.

## Architecture options

- **A. Existing apps configured as a kit** — zero code, but nothing provides
  the grounded ask-your-own-documents path with honest states, and nothing
  ties status together. Insufficient for the mission.
- **B. BASTION dashboard + a few owned functions** — custom app owns:
  status/honest states, document import + FTS + reader with provenance;
  companions handle ZIM/maps/comms. **Chosen for M1.**
- **C. Integrated app with embedded inference + retrieval** — B plus a local
  model behind the same Ask screen. This is M2 — an evolution of B, not a
  rewrite; the Ask tab's retrieval path is already the RAG front half.

## What is genuinely custom (and small)

1. The dashboard with honest states (done, v0.1).
2. Import → preserve → extract → FTS index → retrieve → open-source-passage
   path (done for txt/md, v0.1).
3. M2: inference runtime binding + citation assembly from retrieved rows.
4. M3: supported-surface integrations (launch intents now; data exchange
   only where a current, documented interface exists).

Everything else stays in the apps that already do it well.
