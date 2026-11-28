#!/bin/bash
# usage: mesheval.sh <label> [only-substring]
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; export MSYS_NO_PATHCONV=1
"$ADB" logcat -c
EXTRA=""; [ -n "$2" ] && EXTRA="-e only $2"
"$ADB" shell am instrument -w $EXTRA -e class com.bastion.terminal.ai.MeshAnswerEvalTest com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -4 | cut -c1-300
"$ADB" logcat -d -s MeshEval:I | cut -c30- > "$TEMP/MeshEval-$1.log"
cut -c1-1200 "$TEMP/MeshEval-$1.log"
