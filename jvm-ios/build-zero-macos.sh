#!/usr/bin/env bash
# S0: build HotSpot Zero (interpreter only, libffi) for macOS aarch64 from openjdk/mobile.
# Output: work/mobile/build/macosx-aarch64-zero-release/images/jdk
# Prereq: ./fetch.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
LIBFFI_VER=3.8.0
JOBS="${JOBS:-12}"
CONF=macosx-aarch64-zero-release
FFI="$W/libffi-macos-arm64"
MAKE="$W/tools/bin/make"

# --- libffi (static only, so libjvm.dylib links it in; no dylib to ship) ----
if [ ! -f "$FFI/lib/libffi.a" ]; then
  rm -rf "$W/build-libffi-macos" && mkdir -p "$W/build-libffi-macos"
  (cd "$W/build-libffi-macos" && \
    CC="xcrun -sdk macosx clang" CFLAGS="-O2 -arch arm64 -mmacosx-version-min=12.0" \
    "$W/src/libffi-${LIBFFI_VER}/configure" --prefix="$FFI" --host=aarch64-apple-darwin \
      --disable-shared --enable-static --disable-docs --disable-multi-os-directory >/dev/null && \
    "$MAKE" -j"$JOBS" >/dev/null && "$MAKE" install >/dev/null)
fi

# --- patches (idempotent) -------------------------------------------------
cd "$W/mobile"
for p in "$HERE"/patches/*.patch; do
  git apply --check -R "$p" 2>/dev/null || git apply "$p"
done

# --- configure + build ----------------------------------------------------
if [ ! -f "build/$CONF/spec.gmk" ]; then
  bash configure \
    --with-conf-name="$CONF" \
    --with-boot-jdk="$W/bootjdk/Contents/Home" \
    --with-jvm-variants=zero \
    --with-debug-level=release \
    --with-native-debug-symbols=none \
    --with-libffi="$FFI" \
    --disable-warnings-as-errors \
    MAKE="$MAKE"
fi
"$MAKE" CONF="$CONF" JOBS="$JOBS" images
"build/$CONF/images/jdk/bin/java" -version
