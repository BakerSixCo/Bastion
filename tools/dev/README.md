# Development helper scripts

Git Bash scripts used while testing on the emulator. They used to live in the temp folder of the PC; they are kept here so a new PC has them.

- `ui.sh`: source it for emulator screen helpers (`texts`, `tap "Label"`, `shot`, `dump`, `launch`). Change `SP` at the top if its scratch folder does not exist.
- `sw.sh`: source it for `sw "<Layer label>"`, which toggles a switch in the Layers dialog of the map.
- `runeval.sh <pkg.TestClass> <LogTag>`: runs one instrumented evaluation and prints its log lines.
- `anseval.sh <label>`: runs AnswerEvalTest and saves the log.
- `mesheval.sh <label> [only]`: runs MeshAnswerEvalTest and saves the log.
