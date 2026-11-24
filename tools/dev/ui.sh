#!/usr/bin/env bash
# Tiny adb UI driver. Usage: source ui.sh; dump; texts; tap "Label"; shot name
export MSYS_NO_PATHCONV=1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
SP="C:/Users/ccie5/AppData/Local/Temp/claude/db1e0671e10103a6/5348af1c-bc17-4fc7-b9f0-e76fbad2477a/scratchpad"
dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" exec-out cat /sdcard/ui.xml > "$SP/ui.xml"; }
# print all visible text / content-desc values
texts() { dump; grep -o -E '(text|content-desc)="[^"]+"' "$SP/ui.xml" | sed -E 's/^[a-z-]+="//; s/"$//' ; }
# tap the first node whose text or content-desc contains $1
tap() {
  dump
  local b
  b=$(tr '>' '\n' < "$SP/ui.xml" | grep -F -e "text=\"$1" -e "content-desc=\"$1" | head -1 | grep -o 'bounds="[^"]*"')
  if [ -z "$b" ]; then echo "NOT FOUND: $1"; return 1; fi
  local n; n=$(echo "$b" | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $n
  "$ADB" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
  sleep 1.5
}
shot() { "$ADB" exec-out screencap -p > "$SP/$1.png"; echo "$SP/$1.png"; }
launch() { "$ADB" shell am start -n com.bastion.terminal/.MainActivity >/dev/null; sleep 2; }
# ask "question with spaces": types, searches, answers, prints sources + answer block
ask() {
  "$ADB" shell am start -n com.bastion.terminal/.MainActivity >/dev/null 2>&1; sleep 1
  tap Ask >/dev/null; sleep 1
  "$ADB" shell input swipe 540 600 540 1800 200; sleep 1
  tap "Question or keywords" >/dev/null
  "$ADB" shell input keyevent KEYCODE_MOVE_END; "$ADB" shell input keyevent --longpress KEYCODE_DEL >/dev/null 2>&1
  for i in $(seq 1 80); do "$ADB" shell input keyevent KEYCODE_DEL; done 2>/dev/null
  "$ADB" shell input text "$(echo "$1" | sed 's/ /%s/g')"; "$ADB" shell input keyevent BACK; sleep 1
  tap "Search library" >/dev/null; sleep 2
  echo "Q: $1"; texts | grep -E "^\[[0-9]\]|No matching passages" | sed 's/^/  src: /'
  "$ADB" shell input swipe 540 1700 540 600 300; sleep 1
  if ! texts | grep -q "Answer from these sources"; then echo "  (no answer button)"; return; fi
  tap "Answer from these sources" >/dev/null; local t0=$(date +%s)
  until texts | grep -q -E "^Generated on this device|Generation failed|failed to load|^Stopped"; do sleep 3; "$ADB" shell input swipe 540 1700 540 600 200; [ $(( $(date +%s) - t0 )) -gt 400 ] && { echo "  TIMEOUT"; break; }; done
  sleep 1; "$ADB" shell input swipe 540 1700 540 400 200; sleep 1
  texts | sed -n '/^Local AI answer/,$p' | grep -v -E "^(Home|Ask|Library|Local AI answer)$" | sed 's/^/  /'
}
# askq: like ask, but waits on the app's BastionAI log line (no UI polling during generation)
askq() {
  "$ADB" shell am start -n com.bastion.terminal/.MainActivity >/dev/null 2>&1; sleep 1
  tap Ask >/dev/null; sleep 1
  "$ADB" shell input swipe 540 600 540 1800 200; sleep 1
  tap "Question or keywords" >/dev/null
  "$ADB" shell input keyevent KEYCODE_MOVE_END
  for i in $(seq 1 80); do "$ADB" shell input keyevent KEYCODE_DEL; done 2>/dev/null
  "$ADB" shell input text "$(echo "$1" | sed 's/ /%s/g')"; "$ADB" shell input keyevent BACK; sleep 1
  tap "Search library" >/dev/null; sleep 2
  echo "Q: $1"; texts | grep -E "^\[[0-9]\]|No matching passages" | sed 's/^/  src: /'
  "$ADB" shell input swipe 540 1700 540 600 300; sleep 1
  if ! texts | grep -q "Answer from these sources"; then echo "  (no answer button)"; return; fi
  "$ADB" logcat -c
  tap "Answer from these sources" >/dev/null; local t0=$(date +%s)
  until "$ADB" logcat -d -s BastionAI:I AndroidRuntime:E | grep -q -E "done |FATAL"; do sleep 2; [ $(( $(date +%s) - t0 )) -gt 400 ] && { echo "  TIMEOUT"; break; }; done
  "$ADB" logcat -d -s BastionAI:I | grep -o "done .*" | sed 's/^/  log: /'
  sleep 1; "$ADB" shell input swipe 540 1700 540 400 200; sleep 1
  texts | sed -n '/^Local AI answer/,/^Generated/p' | grep -v -E "^(Local AI answer)$|^Generated" | sed 's/^/  /'
}
# askz: search, scroll until the Answer button, answer, wait on BastionAI log, print answer
askz() {
  "$ADB" shell am start -n com.bastion.terminal/.MainActivity >/dev/null 2>&1; sleep 1
  tap Ask >/dev/null; sleep 1
  for i in 1 2 3; do "$ADB" shell input swipe 540 600 540 1900 150; done; sleep 1
  tap "Question or keywords" >/dev/null
  "$ADB" shell input keyevent KEYCODE_MOVE_END
  for i in $(seq 1 70); do "$ADB" shell input keyevent KEYCODE_DEL; done 2>/dev/null
  "$ADB" shell input text "$(echo "$1" | sed 's/ /%s/g')"; "$ADB" shell input keyevent BACK; sleep 1
  "$ADB" logcat -c
  tap "Search library" >/dev/null; sleep 7
  echo "Q: $1"; "$ADB" logcat -d -s BastionAI:I | grep -o "retrieved [0-9]*.*" | sed 's/.*| \[/  sources: [/; s/WikiMed Medical Encyclopedia: /WM:/g'
  local found=0
  for i in $(seq 1 8); do texts | grep -q "^Answer from these sources" && { found=1; break; }; "$ADB" shell input swipe 540 1800 540 700 250; sleep 1; done
  [ $found = 1 ] || { echo "  (no answer button)"; return; }
  "$ADB" logcat -c
  tap "Answer from these sources" >/dev/null; local t0=$(date +%s)
  until "$ADB" logcat -d -s BastionAI:I AndroidRuntime:E | grep -q -E "done |FATAL"; do sleep 3; [ $(( $(date +%s) - t0 )) -gt 600 ] && { echo "  TIMEOUT"; break; }; done
  "$ADB" logcat -d -s BastionAI:I | grep -o "done .*" | sed 's/done [^|]*| /  stats: /'
  for i in 1 2 3 4 5; do "$ADB" shell input swipe 540 1800 540 500 200; done; sleep 1
  texts | sed -n '/^Local AI answer/,/^Generated/p' | grep -v -E "^Local AI answer$|^Generated" | sed 's/^/  /'
}
# tapx: exact text match
tapx() {
  dump
  local b
  b=$(tr '>' '\n' < "$SP/ui.xml" | grep -F -e "text=\"$1\"" | tail -1 | grep -o 'bounds="[^"]*"')
  if [ -z "$b" ]; then echo "NOT FOUND: $1"; return 1; fi
  local n; n=$(echo "$b" | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $n
  "$ADB" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
  sleep 1.5
}
