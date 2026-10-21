#!/usr/bin/env bash
# Create a jlinked runtime image (java.home) for an iOS static JVM.
#   [VM=openj9|zero] ./jlink-ios.sh [iphoneos|iphonesimulator] OUTDIR [modules=java.base]
# The iOS builds cannot produce jmods themselves (the JDK's jmods depend on dynamic native
# libraries, which the app does not ship: everything native is linked statically). So jmods are
# packed here from the build's class/conf/lib outputs (`make java gendata copy`) without native
# libraries, and jlinked with a macOS JDK of the exact same version:
#   zero:   openjdk/mobile's macOS Zero build (build-zero-macos.sh)
#   openj9: the macOS OpenJ9 sanity build (build-openj9-macos.sh)
# The OpenJ9 classes are the same for device and simulator.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$HERE/work"
VM="${VM:-zero}"
PLATFORM="${1:-iphoneos}"; OUT="${2:?usage: jlink-ios.sh PLATFORM OUTDIR [modules]}"; MODS="${3:-java.base}"
case "$VM:$PLATFORM" in
  zero:iphoneos)          B="$W/mobile/build/ios-aarch64-zero-release" ;;
  zero:iphonesimulator)   B="$W/mobile/build/iossim-aarch64-zero-release" ;;
  openj9:iphoneos)        B="$W/openj9-jdk21/build/ios-aarch64-openj9-release" ;;
  openj9:iphonesimulator) B="$W/openj9-jdk21/build/iossim-aarch64-openj9-release" ;;
  *) echo "unknown VM/platform $VM/$PLATFORM"; exit 1 ;;
esac
case "$VM" in
  zero)   BJDK="$W/mobile/build/macosx-aarch64-zero-release/images/jdk" ;;
  openj9) BJDK="$W/openj9-jdk21/build/macosx-aarch64-openj9-release/images/jdk" ;;
esac
JM="$B/images/jmods-nolibs"
VER="$(sed -n 's/^VERSION_SHORT *:= *//p' "$B/spec.gmk")"
PLAT="$(sed -n 's/^OPENJDK_MODULE_TARGET_PLATFORM *:= *//p' "$B/spec.gmk")"
if [ ! -d "$B/jdk/modules/java.base" ]; then
  if [ "$VM" = zero ]; then "$HERE/build-jdk-ios.sh" "$PLATFORM" java gendata copy
  else "$HERE/build-openj9-ios.sh" "$PLATFORM" java gendata copy; fi
fi
mkdir -p "$JM"
for d in "$B"/jdk/modules/*/; do
  m="$(basename "$d")"
  # Reuse a jmod unless the module's classes changed since.
  [ -f "$JM/$m.jmod" ] && [ -z "$(find "$d" -newer "$JM/$m.jmod" -print -quit)" ] && continue
  rm -f "$JM/$m.jmod"
  args=(--class-path "$d" --target-platform "$PLAT" --module-version "$VER" --compress zip-6)
  [ -d "$B/support/modules_conf/$m" ] && args+=(--config "$B/support/modules_conf/$m")
  [ -d "$B/support/modules_libs/$m" ] && args+=(--libs "$B/support/modules_libs/$m")
  # As the JDK's own jmods: the common notices (LICENSE, ASSEMBLY_EXCEPTION,
  # ADDITIONAL_LICENSE_INFO) go into every module's legal/ directory.
  legal=()
  for l in "$B/support/modules_legal/common" "$B/support/modules_legal/$m"; do [ -d "$l" ] && legal+=("$l"); done
  [ ${#legal[@]} -eq 0 ] || args+=(--legal-notices "$(IFS=:; echo "${legal[*]}")")
  # No native code (it is linked into the app), no launcher configuration, no debug symbols.
  "$BJDK/bin/jmod" create "${args[@]}" \
    --exclude '**{_the.*,_*.marker*,*.so,*.dylib,*.a,*.dSYM/**,jvm.cfg}' "$JM/$m.jmod"
done
[ ! -e "$OUT" ] || { echo "$OUT exists; move it away first"; exit 1; }
"$BJDK/bin/jlink" --module-path "$JM" --add-modules "$MODS" --output "$OUT" \
  --strip-debug --no-header-files --no-man-pages ${JLINK_EXTRA:-}
# No launcher/bin on iOS; jvm.cfg is only read by the java launcher.
mkdir -p "$W/trash"; [ -d "$OUT/bin" ] && mv "$OUT/bin" "$W/trash/bin.$$.$RANDOM"
du -sh "$OUT"; du -sh "$OUT/lib/modules"
