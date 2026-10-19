#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-ui
adb install -r SharpLanDiagnostic/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n com.ced.sharplan/.MainActivity
sleep 12
adb shell pidof com.ced.sharplan
adb shell uiautomator dump /sdcard/sharp-ui.xml
adb pull /sdcard/sharp-ui.xml ci-ui/dashboard.xml
adb exec-out screencap -p > ci-ui/dashboard.png
adb logcat -d > ci-ui/logcat.txt
python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse("ci-ui/dashboard.xml").getroot()
nodes = list(root.iter("node"))
texts = [n.get("text", "") for n in nodes]
assert "SHARP // NEXUS" in texts, texts
deep = [n for n in nodes if n.get("text") == "DEEP SCAN TV"]
assert len(deep) == 1 and deep[0].get("enabled") == "false", "Unconfirmed target must not allow deep scan"
with open("ci-ui/logcat.txt") as f:
    logs = f.read()
assert "Process: com.ced.sharplan," not in logs or "FATAL EXCEPTION" not in logs, logs[-4000:]
import base64
print("SHARP_SCREENSHOT=" + base64.b64encode(open("ci-ui/dashboard.png", "rb").read()).decode())
print("Emulator smoke check passed: dashboard launched, unconfirmed deep scan locked, no crash.")
PY
adb shell am force-stop com.ced.sharplan
