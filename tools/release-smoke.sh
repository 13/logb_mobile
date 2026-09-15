#!/usr/bin/env bash
#
# Runs the *release* APK on a device and checks it survives the things R8 is most likely to break.
#
# The instrumented suite runs against the debug build, which is not the artifact anyone installs.
# Two things in this app only fail once R8 has been over them: kotlinx-serialization resolves
# serializers reflectively, and Hilt's generated graph is stitched together from annotations.
# Assembling the APK exercises neither, so this installs it, drives it, and reads back what the
# screen actually shows.
#
# A fresh install has no server configured and no token, so it lands on the server screen without
# needing a network. From there this types an address nothing answers on and presses Continue,
# which is enough to run Retrofit + kotlinx-serialization end to end (ApiClient) and exercise the
# failure path in SessionRepository.checkServer — the same code a real, unreachable server hits.
# Reaching the sign-in screen is not the point (checkServer is meant to fail here); the app showing
# the unreachable-server error and staying up is.
#
# Usage: tools/release-smoke.sh [serial]
set -euo pipefail

SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
    # A phone and an emulator can both be attached at once, and there is no safe way to guess which
    # one a bare run means. Unlike picking "the first" device, more than one without an explicit
    # serial is a hard refusal.
    COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
    if [ "$COUNT" -gt 1 ]; then
        echo "more than one device attached — pass the emulator serial"
        exit 1
    fi
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
fi
[ -n "$SERIAL" ] || { echo "no device attached"; exit 1; }
ADB=(adb -s "$SERIAL")

APK="app/build/outputs/apk/release/LogB-release.apk"
[ -f "$APK" ] || { echo "missing $APK — run ./gradlew :app:assembleRelease first"; exit 1; }

PKG=dev.logb.android
OUT="${SMOKE_OUT:-build/release-smoke}"
mkdir -p "$OUT"

echo "== installing the release build on $SERIAL"
"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
"${ADB[@]}" install -r "$APK"

# Everything from here on has to appear in a log that starts empty, or an older crash would pass
# for a new one and, worse, a new one could hide in the noise.
"${ADB[@]}" logcat -c 2>/dev/null || true

echo "== launching"
"${ADB[@]}" shell am start -W -n "$PKG/.MainActivity" >/dev/null
# Captured now, while the app is certainly up: every check below that reads the log has to be able
# to tell this app's own output from the rest of the device's.
PID=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
sleep 8

FAILED=0

# Read the strings from the resource files rather than hardcode them, so a future wording change
# can't silently break these checks. English and German are the only locales this app ships.
SERVER_URL_EN=$(sed -n 's/.*name="server_url">\(.*\)<\/string>.*/\1/p' app/src/main/res/values/strings.xml)
SERVER_URL_DE=$(sed -n 's/.*name="server_url">\(.*\)<\/string>.*/\1/p' app/src/main/res/values-de/strings.xml)
UNREACHABLE_EN=$(sed -n 's/.*name="server_unreachable">\(.*\)<\/string>.*/\1/p' app/src/main/res/values/strings.xml)
UNREACHABLE_DE=$(sed -n 's/.*name="server_unreachable">\(.*\)<\/string>.*/\1/p' app/src/main/res/values-de/strings.xml)

echo "== reading the server screen back"
"${ADB[@]}" shell uiautomator dump /sdcard/logb-smoke.xml >/dev/null 2>&1 || true
"${ADB[@]}" pull /sdcard/logb-smoke.xml "$OUT/ui-server.xml" >/dev/null 2>&1 || true

if grep -qF -e "$SERVER_URL_EN" -e "$SERVER_URL_DE" "$OUT/ui-server.xml" 2>/dev/null; then
    echo "  the server screen rendered"
else
    echo "FAIL: the server address field never showed; see $OUT/ui-server.xml"
    FAILED=1
fi

echo "== typing an unreachable address"
# The field has to be focused before "input text" reaches it. Compose merges the label into the
# field's own accessibility node rather than a separate one, so the line that matched the label
# above is also the field's line, and its bounds are the field's bounds.
NODE_LINE=$(grep -F -e "$SERVER_URL_EN" -e "$SERVER_URL_DE" "$OUT/ui-server.xml" 2>/dev/null | head -1)
BOUNDS=$(echo "$NODE_LINE" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
if [ -n "$BOUNDS" ]; then
    read -r X1 Y1 X2 Y2 <<<"$(echo "$BOUNDS" | grep -oE '[0-9]+' | tr '\n' ' ')"
    "${ADB[@]}" shell input tap $(( (X1 + X2) / 2 )) $(( (Y1 + Y2) / 2 ))
else
    echo "  could not find the address field's bounds; typing blind"
fi
# ":" and "/" are not among the characters "input text" has to escape — only spaces and shell
# metacharacters are — and this string has neither.
"${ADB[@]}" shell input text 'https://127.0.0.1:9'
"${ADB[@]}" shell input keyevent 66
# Long enough for checkServer's HTTP client to fail against a closed port and come back through
# ApiClient's error mapping.
sleep 5

echo "== reading the screen back after Continue"
"${ADB[@]}" shell uiautomator dump /sdcard/logb-smoke.xml >/dev/null 2>&1 || true
"${ADB[@]}" pull /sdcard/logb-smoke.xml "$OUT/ui.xml" >/dev/null 2>&1 || true
"${ADB[@]}" exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true

if grep -qF -e "$UNREACHABLE_EN" -e "$UNREACHABLE_DE" "$OUT/ui.xml" 2>/dev/null; then
    echo "  the unreachable-server error reached the screen"
else
    echo "FAIL: no unreachable-server error on screen; see $OUT/ui.xml"
    FAILED=1
fi

echo "== checking the update receiver survived"
# InstallResultReceiver has no intent-filter — it is only ever targeted by an explicit PendingIntent
# from a real install session — so plain "dumpsys package <pkg>" never lists it: that view is the
# resolver tables, keyed by intent-filter action, and this receiver has none. --all-components asks
# for the full manifest-declared list instead, which is where it shows up.
if "${ADB[@]}" shell dumpsys package --all-components "$PKG" 2>/dev/null | grep -q "$PKG/.feature.update.InstallResultReceiver"; then
    echo "  InstallResultReceiver is declared"
else
    echo "FAIL: InstallResultReceiver is not declared; the manifest lost it"
    FAILED=1
fi
# The manifest surviving is a different question from the class surviving it: fire the same explicit
# broadcast a real install session would, so the platform actually has to load the class. A missing
# keep rule shows up as a ClassNotFoundException crash in the app's own log, caught below.
"${ADB[@]}" shell am broadcast -n "$PKG/$PKG.feature.update.InstallResultReceiver" -a "$PKG.INSTALL_RESULT" >/dev/null 2>&1 || true
sleep 2

echo "== checking the log"
if ! "${ADB[@]}" shell pidof "$PKG" >/dev/null; then
    echo "FAIL: the app is not running any more"
    FAILED=1
fi

"${ADB[@]}" logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
# Only this app's own lines. The full log is full of other processes' problems — a CI emulator's
# Settings app throws ClassNotFoundException on its own slice controllers at boot — and a smoke test
# that greps all of it reports someone else's trouble as ours.
if [ -n "$PID" ]; then
    "${ADB[@]}" logcat -d --pid="$PID" > "$OUT/logcat-app.txt" 2>/dev/null || true
else
    : > "$OUT/logcat-app.txt"
fi

# FATAL EXCEPTION is a crash; the other four are how a missing keep rule reports itself, and this
# app catches enough of its own exceptions that one can be logged without taking the process down.
MISSING=$(grep -E "FATAL EXCEPTION|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|SerializationException" "$OUT/logcat-app.txt" || true)
if [ -n "$MISSING" ]; then
    echo "FAIL: something R8 removed or renamed was looked up at runtime, or the app crashed"
    echo "$MISSING" | head -40
    FAILED=1
fi

echo "installed: $("${ADB[@]}" shell dumpsys package "$PKG" | grep -m1 versionName= | tr -d ' \r')"

if [ "$FAILED" -eq 0 ]; then
    echo "release smoke: OK"
else
    echo "release smoke: FAILED (artifacts in $OUT)"
    exit 1
fi
