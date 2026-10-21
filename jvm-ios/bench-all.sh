#!/usr/bin/env bash
# S0: run the full JVM comparison matrix with bench.sh (sequentially, RUNS each).
# Output: work/bench/results.jsonl (+ training runs in work/bench/training.jsonl), then prints the table.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
export RUNS="${RUNS:-3}"
JDK21="${JDK21:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
JDK27="$W/bootjdk/Contents/Home"
ZERO="$W/mobile/build/macosx-aarch64-zero-release/images/jdk"
J9="$W/semeru21/Contents/Home"
B="$HERE/bench.sh"
OUT="$W/bench/results.jsonl"; TRAIN="$W/bench/training.jsonl"
mkdir -p "$W/bench"
only() { [ -z "${ONLY:-}" ] || [[ ",$ONLY," == *",$1,"* ]]; }

only jdk21 && { LABEL="hotspot-21 JIT"            "$B" "$JDK21"; LABEL="hotspot-21 -Xint" "$B" "$JDK21" -Xint; }
only jdk27 && { LABEL="hotspot-27 JIT"            "$B" "$JDK27"; LABEL="hotspot-27 -Xint" "$B" "$JDK27" -Xint; }
only zero && {
  LABEL="zero-28 (default CDS)"   "$B" "$ZERO"
  LABEL="zero-28 -Xshare:off"     "$B" "$ZERO" -Xshare:off
  LABEL="zero-28 SerialGC"        "$B" "$ZERO" -XX:+UseSerialGC
}
# CDS/AOT dumping forces BytecodeVerificationRemote=true (cdsConfig.cpp), which the host's startup
# guard rejects, so these rows (and their baseline) start the host via the no-guard launcher.
# The extension install fails verification in the training run; that is expected and harmless.
only zero-cds && (
  export NO_GUARD=1
  LABEL="zero-28 launcher (default CDS)" "$B" "$ZERO"
  rm -f "$W/bench/zero-dyn.jsa"
  # Train without installing an extension: archived (dump-time-verified) extension classes make the
  # later, unverified extension load fail with VerifyError (seen in a first pass).
  STOP_AFTER=repo RUNS=1 OUT="$TRAIN" LABEL="zero-28 train ArchiveClassesAtExit (no ext)" "$B" "$ZERO" -XX:ArchiveClassesAtExit="$W/bench/zero-dyn.jsa"
  ls -la "$W/bench/zero-dyn.jsa"
  LABEL="zero-28 launcher AppCDS dynamic" "$B" "$ZERO" -XX:SharedArchiveFile="$W/bench/zero-dyn.jsa"
  rm -f "$W/bench/zero.aot"
  RUNS=1 OUT="$TRAIN" LABEL="zero-28 train AOTCacheOutput" "$B" "$ZERO" -XX:AOTCacheOutput="$W/bench/zero.aot"
  ls -la "$W/bench/zero.aot"
  LABEL="zero-28 launcher AOTCache" "$B" "$ZERO" -XX:AOTCache="$W/bench/zero.aot"
)
only j9 && (
  export NO_GUARD=1 HOST_FLAGS="-Xverify:none"
  LABEL="openj9-21 -Xint"         "$B" "$J9" -Xint
  rm -rf "$W/bench/j9scc"
  RUNS=1 OUT="$TRAIN" LABEL="openj9-21 train SCC" "$B" "$J9" -Xint -Xshareclasses:name=oneechan,cacheDir="$W/bench/j9scc"
  LABEL="openj9-21 -Xint +SCC"    "$B" "$J9" -Xint -Xshareclasses:name=oneechan,cacheDir="$W/bench/j9scc"
  LABEL="openj9-21 JIT"           "$B" "$J9"
)
python3 -I "$HERE/bench_driver.py" --table "$OUT"
