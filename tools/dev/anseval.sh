#!/bin/bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; export MSYS_NO_PATHCONV=1
"$ADB" logcat -c
"$ADB" shell am instrument -w -e class com.bastion.terminal.ai.AnswerEvalTest com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -4 | cut -c1-300
"$ADB" logcat -d -s AnswerEval:I | cut -c30- > "$TEMP/AnswerEval-$1.log"
grep -E "PASS|FAIL|SUMMARY|model " "$TEMP/AnswerEval-$1.log" | cut -c1-200
