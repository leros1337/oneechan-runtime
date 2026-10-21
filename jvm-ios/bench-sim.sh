#!/usr/bin/env bash
# S1 bonus: run the S0 benchmark against the extension host running *inside* the iOS-simulator
# spike app (static Zero JVM, ios-simulator platform). Builds the app with HOST=1 first.
#   RUNS=3 ./bench-sim.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
SIM_NAME="${SIM_NAME:-JvmSpike-iPhone17}"
HOST=1 "$HERE/build-spike.sh" iphonesimulator >/dev/null
UDID="$(xcrun simctl list devices -j | python3 -I -c 'import json,sys; n=sys.argv[1]; d=json.load(sys.stdin)["devices"]; print(next(x["udid"] for v in d.values() for x in v if x["name"]==n))' "$SIM_NAME")"
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl install "$UDID" "$W/spike/DerivedData/Build/Products/Debug-iphonesimulator/JvmSpike.app"
mkdir -p "$W/bench/runs"
python3 -I "$HERE/bench_driver.py" --java none --jar none --label "${LABEL:-zero-28 iOS-simulator static (in app)}" \
  --runs "${RUNS:-3}" --out "${OUT:-$W/bench/results.jsonl}" --workdir "$W/bench/runs" --sim-udid "$UDID"
