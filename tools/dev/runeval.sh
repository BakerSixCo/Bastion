#!/bin/bash
# usage: runeval.sh <TestClass> <LogTag>
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; export MSYS_NO_PATHCONV=1
"$ADB" logcat -c
"$ADB" shell am instrument -w -e class com.bastion.terminal.$1 com.bastion.terminal.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -4 | cut -c1-300
"$ADB" logcat -d -s $2:I | cut -c30-330 > "$TEMP/$2.log"
grep -E "HIT |MISS|GAP |SUMMARY|ROUTE|  [0-9].|elevation|dem |pack " "$TEMP/$2.log"
