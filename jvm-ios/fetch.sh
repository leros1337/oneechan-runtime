#!/usr/bin/env bash
# Fetch everything the jvm-ios spikes need into jvm-ios/work/ (nothing system-wide).
#   work/dl/        downloaded tarballs
#   work/mobile/    openjdk/mobile clone (shallow, pinned below)
#   work/bootjdk/   Temurin 27 (boot JDK for JDK 28 sources, N-1)
#   work/semeru21/  IBM Semeru (OpenJ9) 21 for the -Xint comparison
#   work/src/       libffi + GNU make + cups sources
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
mkdir -p "$W/dl" "$W/src"

MOBILE_COMMIT="${MOBILE_COMMIT:-c1ed06aaef34c8dccf71e236d1ffa20918a77cfb}"
BOOT_URL="https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27%2B35/OpenJDK27U-jdk_aarch64_mac_hotspot_27_35.tar.gz"
SEMERU_URL="https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.12.15/ibm-semeru-open-jdk_aarch64_mac_21.0.12.15.tar.gz"
LIBFFI_VER=3.8.0
LIBFFI_URL="https://github.com/libffi/libffi/releases/download/v${LIBFFI_VER}/libffi-${LIBFFI_VER}.tar.gz"
GMAKE_VER=4.4.1
GMAKE_URL="https://ftp.gnu.org/gnu/make/make-${GMAKE_VER}.tar.gz"
CUPS_VER=2.4.11
CUPS_URL="https://github.com/OpenPrinting/cups/releases/download/v${CUPS_VER}/cups-${CUPS_VER}-source.tar.gz"

dl() { # url file
  [ -s "$W/dl/$2" ] || curl -fL --retry 3 -o "$W/dl/$2" "$1"
}

# --- openjdk/mobile -------------------------------------------------------
if [ ! -d "$W/mobile/.git" ]; then
  git clone --depth 1 https://github.com/openjdk/mobile.git "$W/mobile"
fi
if [ "$(git -C "$W/mobile" rev-parse HEAD)" != "$MOBILE_COMMIT" ]; then
  git -C "$W/mobile" fetch --depth 1 origin "$MOBILE_COMMIT"
  git -C "$W/mobile" checkout --detach "$MOBILE_COMMIT"
fi

# --- boot JDK (Temurin 27, N-1 for the JDK 28 mobile tree) ------------------
dl "$BOOT_URL" bootjdk.tar.gz
if [ ! -x "$W/bootjdk/Contents/Home/bin/java" ]; then
  rm -rf "$W/bootjdk" "$W/bootjdk.tmp"; mkdir -p "$W/bootjdk.tmp"
  tar -xzf "$W/dl/bootjdk.tar.gz" -C "$W/bootjdk.tmp"
  mv "$W/bootjdk.tmp"/*/ "$W/bootjdk"; rmdir "$W/bootjdk.tmp"
fi

# --- Semeru 21 (OpenJ9) ---------------------------------------------------
dl "$SEMERU_URL" semeru21.tar.gz
if [ ! -x "$W/semeru21/Contents/Home/bin/java" ]; then
  rm -rf "$W/semeru21" "$W/semeru21.tmp"; mkdir -p "$W/semeru21.tmp"
  tar -xzf "$W/dl/semeru21.tar.gz" -C "$W/semeru21.tmp"
  mv "$W/semeru21.tmp"/*/ "$W/semeru21"; rmdir "$W/semeru21.tmp"
fi

# --- sources --------------------------------------------------------------
dl "$LIBFFI_URL" "libffi-${LIBFFI_VER}.tar.gz"
[ -d "$W/src/libffi-${LIBFFI_VER}" ] || tar -xzf "$W/dl/libffi-${LIBFFI_VER}.tar.gz" -C "$W/src"
dl "$GMAKE_URL" "make-${GMAKE_VER}.tar.gz"
[ -d "$W/src/make-${GMAKE_VER}" ] || tar -xzf "$W/dl/make-${GMAKE_VER}.tar.gz" -C "$W/src"
dl "$CUPS_URL" "cups-${CUPS_VER}.tar.gz"
[ -d "$W/src/cups-${CUPS_VER}" ] || tar -xzf "$W/dl/cups-${CUPS_VER}.tar.gz" -C "$W/src"

# --- GNU make 4.x (macOS ships 3.81; JDK recommends >= 4.0) ----------------
if [ ! -x "$W/tools/bin/make" ]; then
  (cd "$W/src/make-${GMAKE_VER}" && ./configure --prefix="$W/tools" --disable-dependency-tracking >/dev/null \
     && /usr/bin/make -j8 >/dev/null && /usr/bin/make install >/dev/null)
fi
echo "fetch.sh: done"
"$W/bootjdk/Contents/Home/bin/java" -version 2>&1 | head -1
"$W/semeru21/Contents/Home/bin/java" -version 2>&1 | head -1
"$W/tools/bin/make" --version | head -1
