#!/usr/bin/env bash
# S1: cross-build OpenJDK (openjdk/mobile, HotSpot Zero) for iOS as static libraries.
#   ./build-jdk-ios.sh [iphoneos|iphonesimulator] [make targets...]
# Default targets: static-libs-image images (images = jmods + jlinked jdk image via the build JDK).
# Output: work/mobile/build/<conf>/images/{static-libs,jdk}
# Prereq: ./fetch.sh and ./build-zero-macos.sh (its image is reused as the exact-version build JDK).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
PLATFORM="${1:-iphoneos}"; shift || true
TARGETS=("$@"); [ ${#TARGETS[@]} -gt 0 ] || TARGETS=(static-libs-image images)
LIBFFI_VER=3.8.0
CUPS_VER=2.4.11
IOS_MIN="${IOS_MIN:-26.0}"
JOBS="${JOBS:-12}"
MAKE="$W/tools/bin/make"
BUILD_JDK="$W/mobile/build/macosx-aarch64-zero-release/images/jdk"
SDK="$(xcrun --sdk "$PLATFORM" --show-sdk-path)"
case "$PLATFORM" in
  iphoneos)        CONF=ios-aarch64-zero-release;    TRIPLE="arm64-apple-ios${IOS_MIN}";           MINFLAG="-miphoneos-version-min=${IOS_MIN}" ;;
  iphonesimulator) CONF=iossim-aarch64-zero-release; TRIPLE="arm64-apple-ios${IOS_MIN}-simulator"; MINFLAG="-mios-simulator-version-min=${IOS_MIN}" ;;
  *) echo "unknown platform $PLATFORM"; exit 1 ;;
esac
FFI="$W/libffi-$PLATFORM-arm64"

# --- libffi for iOS (static; aarch64-apple-* uses the exec trampoline table, no W+X pages) ----------
if [ ! -f "$FFI/lib/libffi.a" ]; then
  rm -rf "$W/build-libffi-$PLATFORM" && mkdir -p "$W/build-libffi-$PLATFORM"
  (cd "$W/build-libffi-$PLATFORM" && \
    CC="$(xcrun --sdk "$PLATFORM" -f clang)" CFLAGS="-O2 -target $TRIPLE -isysroot $SDK" \
    CCASFLAGS="-target $TRIPLE -isysroot $SDK" LDFLAGS="-target $TRIPLE -isysroot $SDK" \
    "$W/src/libffi-${LIBFFI_VER}/configure" --prefix="$FFI" --host=aarch64-apple-darwin \
      --disable-shared --enable-static --disable-docs --disable-multi-os-directory >/dev/null && \
    "$MAKE" -j"$JOBS" >/dev/null && "$MAKE" install >/dev/null)
fi

# --- patches (idempotent) -------------------------------------------------
cd "$W/mobile"
for p in "$HERE"/patches/*.patch; do
  git apply --check -R "$p" 2>/dev/null || git apply "$p"
done

# --- configure ------------------------------------------------------------
if [ ! -f "build/$CONF/spec.gmk" ]; then
  bash configure \
    --with-conf-name="$CONF" \
    --openjdk-target=aarch64-macos-ios \
    --with-sysroot="$SDK" \
    --with-boot-jdk="$W/bootjdk/Contents/Home" \
    --with-build-jdk="$BUILD_JDK" \
    --with-jvm-variants=zero \
    --with-debug-level=release \
    --with-native-debug-symbols=none \
    --with-libffi-include="$FFI/include" --with-libffi-lib="$FFI/lib" \
    --with-cups-include="$W/src/cups-${CUPS_VER}" \
    --with-extra-cflags="-target $TRIPLE $MINFLAG" \
    --with-extra-cxxflags="-target $TRIPLE $MINFLAG" \
    --with-extra-ldflags="-target $TRIPLE $MINFLAG" \
    --disable-warnings-as-errors \
    MAKE="$MAKE"
fi
"$MAKE" CONF="$CONF" JOBS="$JOBS" "${TARGETS[@]}"
