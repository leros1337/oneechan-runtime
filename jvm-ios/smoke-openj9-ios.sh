#!/usr/bin/env bash
# Smoke tests of the statically linked OpenJ9 for iOS, without the app. They link with the prelinked
# VM (prelink-openj9-ios.py) and, for the simulator, run in a simulator:
#   vm:   smoke/openj9_smoke.c boots the VM from a runtime image, runs smoke/Smoke.java (interpreter,
#         invokedynamic, threads, GC, zip/net/nio natives) and fails if the VM mapped any executable
#         memory.
#   host: smoke/host_smoke.c boots the extension host jar through the app's JNI shim
#         (OneechanJVM/oneechan_jvm.c) with the bundled VM option file and calls host.hello.
#   ./smoke-openj9-ios.sh iphonesimulator|iphoneos [vm|host] [JAVA_HOME]
# JAVA_HOME defaults to work/openj9-embedded/jre (`EMBEDDED_DIR=work/openj9-embedded ./package.sh`).
# host needs SHIM_DIR: the app's native shim sources (ios/Packages/OneechanKit/Sources/OneechanJVM).
# SIM_NAME (default OpenJ9-iPhone17) names the simulator; it must exist.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
PLATFORM="${1:-iphonesimulator}"
MODE="${2:-vm}"
JRE="${3:-$W/openj9-embedded/jre}"
SHIM="${SHIM_DIR:-}"
HOST_JAR="$HERE/../extension-host/host-embedded/build/libs/oneechan-host-embedded.jar"
SIM_NAME="${SIM_NAME:-OpenJ9-iPhone17}"
SEMERU="$W/semeru21/Contents/Home"
case "$PLATFORM" in
  iphoneos) CONF=ios-aarch64-openj9-release ;;
  iphonesimulator) CONF=iossim-aarch64-openj9-release ;;
  *) echo "usage: smoke-openj9-ios.sh iphonesimulator|iphoneos [vm|host] [JAVA_HOME]"; exit 1 ;;
esac
B="$W/openj9-jdk21/build/$CONF"
P="$W/openj9-ios/$PLATFORM/prelinked"
S="$W/openj9-ios/$PLATFORM/smoke"
mkdir -p "$S/classes"
[ -f "$P/libopenj9.a" ] || python3 -I "$HERE/prelink-openj9-ios.py" "$PLATFORM" "$B" "$P"

read -r -a FLAGS < "$P/link-flags.txt"
CC="$W/openj9-ios/$PLATFORM/bin/cc"
if [ "$MODE" = vm ]; then
  "$SEMERU/bin/javac" --release 21 -d "$S/classes" "$HERE/smoke/Smoke.java"
  "$SEMERU/bin/jar" --create --file "$S/smoke.jar" -C "$S/classes" .
  "$CC" -O1 -I"$B/support/modules_include/java.base" -I"$B/support/modules_include/java.base/darwin" \
    "$HERE/smoke/openj9_smoke.c" "${FLAGS[@]}" -o "$S/openj9_smoke" 2>&1 | { grep -v 'ignoring duplicate libraries' || true; }
  ls -l "$S/openj9_smoke"
else
  [ -f "$SHIM/oneechan_jvm.c" ] || { echo "set SHIM_DIR to the app's OneechanJVM sources (ios/Packages/OneechanKit/Sources/OneechanJVM)"; exit 1; }
  [ -f "$HOST_JAR" ] || (cd "$HERE/../extension-host" && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}" ./gradlew --console=plain -q :host-embedded:shadowJar)
  "$CC" -O1 -I"$SHIM/include" -I"$SHIM/jni" "$HERE/smoke/host_smoke.c" "$SHIM/oneechan_jvm.c" "${FLAGS[@]}" \
    -o "$S/host_smoke" 2>&1 | { grep -v 'ignoring duplicate libraries' || true; }
  ls -l "$S/host_smoke"
fi
[ "$PLATFORM" = iphonesimulator ] || { echo "linked for the device (not run)"; exit 0; }

UDID="$(xcrun simctl list devices -j | python3 -I -c 'import json,sys; n=sys.argv[1]; d=json.load(sys.stdin)["devices"]; print(next((x["udid"] for v in d.values() for x in v if x["name"]==n and x["isAvailable"]), ""))' "$SIM_NAME")"
[ -n "$UDID" ] || { echo "no simulator named $SIM_NAME"; exit 1; }
xcrun simctl bootstatus "$UDID" -b >/dev/null
if [ "$MODE" = vm ]; then
  xcrun simctl spawn "$UDID" "$S/openj9_smoke" "$JRE" "$S/smoke.jar"
else
  rm -rf "$S/host-work"
  # With an inserted library, as under XCTest: the executable is then not dyld's first image.
  SIMCTL_CHILD_DYLD_INSERT_LIBRARIES=/usr/lib/libobjc.A.dylib \
    xcrun simctl spawn "$UDID" "$S/host_smoke" "$JRE" "$HOST_JAR" "$HERE/jvm-options/openj9.options" "$S/host-work"
fi
