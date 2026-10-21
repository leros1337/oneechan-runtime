#!/usr/bin/env bash
# S0 benchmark: bench.sh JAVA_HOME [extra jvm args...]
#   env LABEL=name     row label (default: basename of JAVA_HOME + args)
#   env RUNS=2         repetitions (fresh data dir each)
#   env HOST_FLAGS=... verifier flags (default HotSpot/Zero diagnostic flags)
#   env JAR=...        host fat jar (default: frozen snapshot work/bench/host.jar)
#   env STOP_AFTER=repo  stop each run after repo.add (CDS training without extension classes)
#   env NO_GUARD=1     start via bench-support/NoGuardHostLauncher (needed for OpenJ9; set HOST_FLAGS=-Xverify:none)
# Results: work/bench/results.jsonl (one JSON object per run); logs + data dirs in work/bench/.
# Table:   python3 -I bench_driver.py --table work/bench/results.jsonl
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JH="${1:?usage: bench.sh JAVA_HOME [jvm args]}"; shift
# The host jar is rebuilt by other work; benchmark a frozen snapshot (work/bench/host.jar) so a
# rebuild mid-run cannot break class loading. Delete the snapshot to refresh it.
SRC_JAR="$HERE/../extension-host/host-desktop/build/libs/oneechan-extension-host.jar"
mkdir -p "$HERE/work/bench"
[ -f "$HERE/work/bench/host.jar" ] || cp "$SRC_JAR" "$HERE/work/bench/host.jar"
JAR="${JAR:-$HERE/work/bench/host.jar}"
HOST_FLAGS="${HOST_FLAGS:--XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote}"
LABEL="${LABEL:-$(basename "$(dirname "$(dirname "$JH")")")${*:+ $*}}"
OUT="${OUT:-$HERE/work/bench/results.jsonl}"
MAIN_ARGS=()
if [ -n "${NO_GUARD:-}" ]; then
  # OpenJ9: the host's HotSpotDiagnosticMXBean guard always fails there, so start it via a launcher
  # that skips only that check (bench-support/NoGuardHostLauncher.java), and pass -Xverify:none.
  JDK21="${JDK21:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
  # A jar, not a class directory: CDS refuses non-empty directories on the class path. Built once:
  # CDS/AOT archives record each class-path jar's mtime/size and are rejected if the jar changes.
  if [ ! -f "$HERE/work/bench/launcher.jar" ] || [ "$HERE/bench-support/NoGuardHostLauncher.java" -nt "$HERE/work/bench/launcher.jar" ]; then
    mkdir -p "$HERE/work/bench/launcher"
    "$JDK21/bin/javac" --release 21 -cp "$JAR" -d "$HERE/work/bench/launcher" "$HERE/bench-support/NoGuardHostLauncher.java"
    "$JDK21/bin/jar" --create --file "$HERE/work/bench/launcher.jar" -C "$HERE/work/bench/launcher" .
  fi
  MAIN_ARGS=(--main NoGuardHostLauncher --cp "$HERE/work/bench/launcher.jar")
fi
# shellcheck disable=SC2086
exec python3 -I "$HERE/bench_driver.py" --java "$JH/bin/java" --jar "$JAR" --label "$LABEL" \
  --runs "${RUNS:-2}" --stop-after "${STOP_AFTER:-}" --out "$OUT" --workdir "$HERE/work/bench/runs" --timeout "${TIMEOUT:-3600}" ${MAIN_ARGS[@]+"${MAIN_ARGS[@]}"} \
  -- "$@" $HOST_FLAGS
