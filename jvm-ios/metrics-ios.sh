#!/usr/bin/env bash
# OpenJ9 vs Zero on the iOS simulator, same harness: smoke/host_smoke.c (the app's JNI shim booting
# the extension host jar, then one host.hello) linked once with each static JVM, run RUNS times.
# Prints the median time to "host ready" (JNI_CreateJavaVM + EmbeddedBridge.start), the memory after
# start and after the call, and the sizes of the linked code and runtime images.
#   ./metrics-ios.sh [RUNS=5]
# Needs: OpenJ9 packaged (OPENJ9_OUT, default $EMBEDDED_DIR or build/embedded), Zero staged with
#   EMBEDDED_DIR=work/zero-embedded VM=zero ./package.sh
# and SHIM_DIR, the app's native shim sources (ios/Packages/OneechanKit/Sources/OneechanJVM).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
W="$HERE/work"
RUNS="${1:-5}"
SIM_NAME="${SIM_NAME:-OpenJ9-iPhone17}"
OPENJ9_OUT="${OPENJ9_OUT:-${EMBEDDED_DIR:-$ROOT/build/embedded}}"
ZERO_OUT="${ZERO_OUT:-$W/zero-embedded}"
SHIM="${SHIM_DIR:?set SHIM_DIR to the app's OneechanJVM sources (ios/Packages/OneechanKit/Sources/OneechanJVM)}"
JAR="$OPENJ9_OUT/host/oneechan-host.jar"
M="$W/metrics"; mkdir -p "$M"
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
CC=(xcrun --sdk iphonesimulator clang -target arm64-apple-ios26.0-simulator -isysroot "$SDK")
UDID="$(xcrun simctl list devices -j | python3 -I -c 'import json,sys; n=sys.argv[1]; d=json.load(sys.stdin)["devices"]; print(next((x["udid"] for v in d.values() for x in v if x["name"]==n and x["isAvailable"]), ""))' "$SIM_NAME")"
[ -n "$UDID" ] || { echo "no simulator named $SIM_NAME"; exit 1; }
xcrun simctl bootstatus "$UDID" -b >/dev/null

ldflags() { # xcconfig -> simulator JVM_LDFLAGS
  sed -n 's/^JVM_LDFLAGS\[sdk=iphonesimulator\*\] = //p' "$1"
}
for vm in openj9 zero; do
  out="$([ $vm = openj9 ] && echo "$OPENJ9_OUT" || echo "$ZERO_OUT")"
  read -r -a flags <<< "$(ldflags "$out/jvm.xcconfig")"
  "${CC[@]}" -O1 -I"$SHIM/include" -I"$SHIM/jni" "$HERE/smoke/host_smoke.c" "$SHIM/oneechan_jvm.c" "${flags[@]}" \
    -o "$M/host_smoke-$vm" 2>&1 | { grep -v 'ignoring duplicate libraries' || true; }
  expect="$([ $vm = openj9 ] && echo OpenJ9 || echo Zero)"
  : > "$M/$vm.log"
  for i in $(seq "$RUNS"); do
    rm -rf "$M/work-$vm"
    SIMCTL_CHILD_EXPECT_JVM="$expect" xcrun simctl spawn "$UDID" "$M/host_smoke-$vm" "$out/jre" "$JAR" "$out/jre/lib/oneechan-jvm.options" "$M/work-$vm" \
      2>/dev/null | grep -E 'host ready|memory|hello answered|HOST SMOKE' >> "$M/$vm.log"
  done
done

python3 -I - "$M" "$RUNS" <<'EOF'
import re, statistics, sys, os
m, runs = sys.argv[1], int(sys.argv[2])
print(f"host_smoke on the simulator, median of {runs} runs")
print(f"{'VM':8} {'ok':>4} {'host ready s':>13} {'hello s':>8} {'resident MB (start/hello)':>27} {'footprint MB (start/hello)':>28}")
for vm in ("openj9", "zero"):
    text = open(os.path.join(m, f"{vm}.log")).read()
    num = lambda pattern: [float(x) for x in re.findall(pattern, text)]
    ready, hello = num(r"host ready in ([\d.]+)"), num(r"hello answered in ([\d.]+)")
    res_s, fp_s = num(r"after start: resident ([\d.]+)"), num(r"after start: resident [\d.]+ MB, footprint ([\d.]+)")
    res_h, fp_h = num(r"after hello: resident ([\d.]+)"), num(r"after hello: resident [\d.]+ MB, footprint ([\d.]+)")
    med = lambda xs: statistics.median(xs) if xs else float("nan")
    print(f"{vm:8} {text.count('HOST SMOKE OK'):>4} {med(ready):>13.3f} {med(hello):>8.3f} {med(res_s):>12.1f} / {med(res_h):<12.1f} {med(fp_s):>13.1f} / {med(fp_h):<12.1f}")
EOF

echo
echo "sizes (KB):"
kb() { du -A -sk "$1" | cut -f1; }  # apparent size (APFS allocation varies)
o9="$(sed -n 's/^JVM_LDFLAGS\[sdk=iphoneos\*\] = -force_load \([^ ]*\).*/\1/p' "$OPENJ9_OUT/jvm.xcconfig")"
echo "  openj9 static archive (iphoneos, all VM + JDK native code): $(kb "$o9")"
echo "  openj9 jre: $(kb "$OPENJ9_OUT/jre") (lib/modules $(kb "$OPENJ9_OUT/jre/lib/modules"))"
echo "  zero   jre: $(kb "$ZERO_OUT/jre") (lib/modules $(kb "$ZERO_OUT/jre/lib/modules"))"
echo "  smoke executables (simulator): openj9 $(kb "$M/host_smoke-openj9"), zero $(kb "$M/host_smoke-zero")"
