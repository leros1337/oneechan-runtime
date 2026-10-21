#!/usr/bin/env bash
# S1: build the embedded-JVM spike app (spike-app/) and, for the simulator, run it and capture
# the console.
#   ./build-spike.sh iphonesimulator [run]   # build for the simulator (+ install/launch with simctl)
#   ./build-spike.sh iphoneos                # compile + link for generic/platform=iOS (no install)
#   HOST=1 ./build-spike.sh iphonesimulator   # also bundle the extension host (see bench-sim.sh)
# Prereqs: ./build-jdk-ios.sh <platform> static-libs-image java gendata copy
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
PLATFORM="${1:-iphonesimulator}"; ACTION="${2:-build}"
JDK21="${JDK21:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
S="$W/spike"; mkdir -p "$S" "$W/trash"
SIM_NAME="${SIM_NAME:-JvmSpike-iPhone17}"
SIM_TYPE="${SIM_TYPE:-com.apple.CoreSimulator.SimDeviceType.iPhone-17}"
# Static libs linked into the app (-force_load keeps every JNI_OnLoad_<lib>/JNIEXPORT symbol, which
# the static JDK resolves with dlsym(RTLD_DEFAULT) at runtime). libjsig is never linked.
JVM_LIBS="${JVM_LIBS:-java verify zip jimage net nio syslookup fallbackLinker management management_ext prefs extnet}"

conf_of() { case "$1" in iphoneos) echo ios-aarch64-zero-release ;; iphonesimulator) echo iossim-aarch64-zero-release ;; esac; }

# 1. payload jar
mkdir -p "$S/classes"
"$JDK21/bin/javac" --release 21 -d "$S/classes" "$HERE/spike-app/java/Spike.java"
"$JDK21/bin/jar" --create --file "$S/spike.jar" -C "$S/classes" .

# 2. java.home for the app bundle (jlinked; classes are identical for device/simulator).
#    HOST=1 also bundles the extension host fat jar and the modules it needs (jdeps).
HOST_MODULES="java.base,java.desktop,java.instrument,java.naming,java.net.http,java.prefs,java.scripting,java.sql,jdk.dynalink,jdk.httpserver,jdk.javadoc,jdk.management,jdk.unsupported"
mkdir -p "$S/extra"
if [ -n "${HOST:-}" ]; then
  MODULES="$HOST_MODULES"; cp "$W/bench/host.jar" "$S/extra/host.jar"
else
  MODULES="java.base"; [ ! -e "$S/extra/host.jar" ] || mv "$S/extra/host.jar" "$W/trash/host.jar.$$"
fi
if [ ! -f "$S/jre/lib/modules" ] || [ "$(cat "$S/jre.modules" 2>/dev/null)" != "$MODULES" ]; then
  [ ! -e "$S/jre" ] || mv "$S/jre" "$W/trash/jre.$$.$RANDOM"
  "$HERE/jlink-ios.sh" "$PLATFORM" "$S/jre" "$MODULES"
  echo "$MODULES" > "$S/jre.modules"
fi

# 3. per-SDK link settings for whichever iOS builds exist
{
  for p in iphoneos iphonesimulator; do
    B="$W/mobile/build/$(conf_of "$p")"
    L="$B/images/static-libs/lib"
    [ -f "$L/zero/libjvm.a" ] || continue
    sdk=$([ "$p" = iphoneos ] && echo iphoneos || echo iphonesimulator)
    flags="-force_load $L/zero/libjvm.a"
    for l in $JVM_LIBS; do flags="$flags -force_load $L/lib$l.a"; done
    flags="$flags $W/libffi-$p-arm64/lib/libffi.a -lz -lc++"
    echo "JVM_LDFLAGS[sdk=$sdk*] = $flags \$(JVM_FRAMEWORKS)"
    echo "JVM_INCLUDE_DIRS[sdk=$sdk*] = $B/support/modules_include/java.base $B/support/modules_include/java.base/ios"
  done
  # The static JDK libs only need libz, libc++ and (Core)Foundation (verified: links without others).
  echo "JVM_FRAMEWORKS = -framework CoreFoundation -framework Foundation"
} > "$S/libs.xcconfig"
cat "$S/libs.xcconfig"

# 4. project + build
(cd "$HERE/spike-app" && /opt/homebrew/bin/xcodegen generate --quiet)
DD="$S/DerivedData"
if [ "$PLATFORM" = iphoneos ]; then
  xcodebuild -project "$HERE/spike-app/JvmSpike.xcodeproj" -scheme JvmSpike -configuration Debug \
    -destination 'generic/platform=iOS' -derivedDataPath "$DD" CODE_SIGNING_ALLOWED=NO build 2>&1 | xcbeautify
  APP="$DD/Build/Products/Debug-iphoneos/JvmSpike.app"
  ls -la "$APP/JvmSpike"; exit 0
fi

UDID="$(xcrun simctl list devices -j | python3 -I -c 'import json,sys; n=sys.argv[1]; d=json.load(sys.stdin)["devices"]; print(next((x["udid"] for v in d.values() for x in v if x["name"]==n and x["isAvailable"]), ""))' "$SIM_NAME")"
if [ -z "$UDID" ]; then
  # newest iOS runtime that supports the device type
  for RUNTIME in $(xcrun simctl list runtimes -j | python3 -I -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x["platform"]=="iOS" and x["isAvailable"]]; print(" ".join(x["identifier"] for x in sorted(r,key=lambda x:[int(p) for p in x["version"].split(".")], reverse=True)))'); do
    UDID="$(xcrun simctl create "$SIM_NAME" "$SIM_TYPE" "$RUNTIME" 2>/dev/null)" && break
  done
  [ -n "$UDID" ] || { echo "could not create simulator $SIM_TYPE"; exit 1; }
fi
xcodebuild -project "$HERE/spike-app/JvmSpike.xcodeproj" -scheme JvmSpike -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$DD" build 2>&1 | xcbeautify
APP="$DD/Build/Products/Debug-iphonesimulator/JvmSpike.app"
[ "$ACTION" = run ] || exit 0
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl install "$UDID" "$APP"
LOG="$S/console-$(date +%Y%m%d-%H%M%S).log"
# --console-pty streams the app's stdout/stderr; the app keeps running (UI), so stop after RUN_SECS.
perl -e 'alarm shift; exec @ARGV' "${RUN_SECS:-60}" \
  xcrun simctl launch --console-pty --terminate-running-process "$UDID" dev.oneechan.jvmspike > "$LOG" 2>&1 || true
xcrun simctl terminate "$UDID" dev.oneechan.jvmspike 2>/dev/null || true
echo "console: $LOG"; cat "$LOG"
