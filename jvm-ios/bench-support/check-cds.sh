#!/usr/bin/env bash
# Verify that a CDS/AOT archive is really mapped: start the host (no-guard launcher) until /health,
# then count classes loaded from the shared archive.  Usage: check-cds.sh JAVA_HOME [jvm args...]
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
W="$HERE/work"
JH="$1"; shift
OUT="$(mktemp -d "$W/scratch/cds.XXXXXX")"
PORT=52777
"$JH/bin/java" "$@" -Xlog:class+load=info:file="$OUT/cl.txt" -Xlog:cds=info,aot=info:file="$OUT/cds.txt" \
  -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote \
  -cp "$W/bench/host.jar:$W/bench/launcher.jar" NoGuardHostLauncher --data "$OUT/data" --port $PORT > "$OUT/run.log" 2>&1 &
pid=$!
for _ in $(seq 1 200); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 0.1; done
kill $pid; wait $pid || true
total=$(wc -l < "$OUT/cl.txt" | tr -d ' ')
shared=$(rg -c 'shared objects file' "$OUT/cl.txt" || echo 0)
top=$(rg -c 'shared objects file \(top\)' "$OUT/cl.txt" || echo 0)
echo "args: $* | classes loaded until /health: $total, from archive: $shared (top layer: $top) | logs: $OUT"
rg -i 'mismatch|disabled|unable|not used|fail|error' "$OUT/cds.txt" | head -5 || true
