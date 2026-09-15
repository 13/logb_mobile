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
# needing a network. From there this types an address nothing answers on and presses Continue. That
# exercises: Retrofit and OkHttp client construction (ApiClient), the generated Hilt graph wiring
# it up to SessionRepository.checkServer, the Compose screens surviving R8, and the resulting error
# path actually rendering.
#
# It does NOT exercise kotlinx-serialization under R8: checkServer's health() call returns
# Response<Unit>, and port 9 refuses the TCP connection before any HTTP response — let alone a body
# to decode — ever arrives. Serialization under R8 is a real gap this step leaves open; a local stub
# server that answers with a body is the follow-up.
# Reaching the sign-in screen is not the point (checkServer is meant to fail here); the app showing
# the unreachable-server error and staying up is.
#
# Usage: tools/release-smoke.sh [serial]
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
    # A phone and an emulator can both be attached at once, and there is no safe way to guess which
    # one a bare run means. Unlike picking "the first" device, more than one without an explicit
    # serial (an argument, or $ANDROID_SERIAL) is a hard refusal.
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
# A previous run's dump/screenshot/log files must never survive into this one: a failed dump below
# falls back to `|| true` (so one bad step doesn't abort the whole script), and a stale file left
# over from an earlier pass would otherwise let its grep check pass on old content.
rm -rf "$OUT"
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
PID=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}' || true)
sleep 8

FAILED=0

# Dumps the current screen to $1 on this machine. Wipes the on-device file first so a dump that
# fails (and is swallowed by `|| true`, so one bad step doesn't abort the whole run) can never leave
# a *previous* run's dump for the pull to find; then, if the pulled file is missing or empty, reports
# a clear FAIL instead of letting the caller's grep silently match nothing — or, worse, match
# something left over from before. Returns non-zero when the screen could not be read, so callers
# skip the content check but the rest of the script still runs.
dump_screen() {
    local out_file="$1"
    "${ADB[@]}" shell rm -f /sdcard/logb-smoke.xml
    "${ADB[@]}" shell uiautomator dump /sdcard/logb-smoke.xml >/dev/null 2>&1 || true
    "${ADB[@]}" pull /sdcard/logb-smoke.xml "$out_file" >/dev/null 2>&1 || true
    if [ ! -s "$out_file" ]; then
        echo "FAIL: could not read the screen; see $out_file"
        FAILED=1
        return 1
    fi
}

# Android string resources escape a handful of characters; decode them so grep sees the same text
# a person would, whichever escaping a translator happened to use. &amp; is decoded last, or an
# entity that was itself escaped (say "&amp;lt;", literal text "&lt;") would be double-decoded.
decode_android_string() {
    printf '%s' "$1" \
        | sed -e "s/\\\\'/'/g" -e 's/\\"/"/g' -e "s/&apos;/'/g" -e 's/&quot;/"/g' \
              -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g'
}

# $1: resource name, $2: strings.xml path. Reading the strings from the resource files, rather
# than hardcoding them, means a future wording change can't silently break these checks — an empty
# extraction (the resource was renamed, or the file moved) fails loudly instead of matching nothing.
extract_string() {
    local raw decoded
    raw=$(sed -n "s/.*name=\"$1\">\\(.*\\)<\\/string>.*/\\1/p" "$2")
    decoded=$(decode_android_string "$raw")
    if [ -z "$decoded" ]; then
        echo "could not read string '$1' from $2 — has it been renamed or moved?" >&2
        exit 1
    fi
    printf '%s' "$decoded"
}

# English and German are the only locales this app ships.
SERVER_URL_EN=$(extract_string server_url app/src/main/res/values/strings.xml)
SERVER_URL_DE=$(extract_string server_url app/src/main/res/values-de/strings.xml)
UNREACHABLE_EN=$(extract_string server_unreachable app/src/main/res/values/strings.xml)
UNREACHABLE_DE=$(extract_string server_unreachable app/src/main/res/values-de/strings.xml)

echo "== reading the server screen back"
if dump_screen "$OUT/ui-server.xml"; then
    if grep -qF -e "$SERVER_URL_EN" -e "$SERVER_URL_DE" "$OUT/ui-server.xml"; then
        echo "  the server screen rendered"
    else
        echo "FAIL: the server address field never showed; see $OUT/ui-server.xml"
        FAILED=1
    fi
fi

echo "== typing an unreachable address"
# The field has to be focused before "input text" reaches it. Compose merges the label into the
# field's own accessibility node rather than a separate one, so the line that matched the label
# above is also the field's line, and its bounds are the field's bounds.
NODE_LINE=$(grep -F -e "$SERVER_URL_EN" -e "$SERVER_URL_DE" "$OUT/ui-server.xml" 2>/dev/null | head -1 || true)
BOUNDS=$(echo "$NODE_LINE" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1 || true)
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
if dump_screen "$OUT/ui.xml"; then
    if grep -qF -e "$UNREACHABLE_EN" -e "$UNREACHABLE_DE" "$OUT/ui.xml"; then
        echo "  the unreachable-server error reached the screen"
    else
        echo "FAIL: no unreachable-server error on screen; see $OUT/ui.xml"
        FAILED=1
    fi
fi
"${ADB[@]}" exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true

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

echo "== checking the widget provider survived"
# `dumpsys appwidget` lists every provider on the device as `cmp:ComponentInfo{pkg/pkg.Class}` —
# the fully-qualified class name, not the manifest's shorthand `.feature.widget.DueWidgetReceiver` —
# so the match has to spell the package out twice, exactly as dumpsys prints it. This is a static
# manifest declaration (like InstallResultReceiver above): it shows up whether or not anyone has
# ever placed the widget on a home screen, so a fresh AVD with nothing pinned still passes.
if "${ADB[@]}" shell dumpsys appwidget 2>/dev/null | grep -q "cmp:ComponentInfo{$PKG/$PKG.feature.widget.DueWidgetReceiver}"; then
    echo "  DueWidgetReceiver is declared"
else
    echo "FAIL: DueWidgetReceiver is not declared; the manifest or the provider lost it"
    FAILED=1
fi

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
