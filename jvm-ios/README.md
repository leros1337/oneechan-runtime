# jvm-ios: the embedded JVM for iOS

Oneechan runs the extension host (a Java 21 fat jar) inside the iOS app, on a JVM linked into the app
binary. iOS forbids JIT compilation and writable+executable memory, so the JVM runs as an interpreter.

The VM is **Eclipse OpenJ9 0.61.0** + OMR (used under Apache-2.0) with the OpenJDK 21 class library from
ibmruntimes (GPLv2 + Classpath Exception, as are the JDK's native libraries). `make jvm-ios` in the app
repository packages it.

HotSpot Zero (openjdk/mobile) was evaluated first and dropped: HotSpot is GPLv2 without the Classpath
Exception, so an app linking it cannot be distributed. It performed similarly but peaked about 100 MB
higher in memory.

Everything that is downloaded, cloned or built goes to `work/` (gitignored). Nothing is installed system-wide.

## Quick start (OpenJ9)

```sh
cd jvm-ios
./fetch-openj9.sh                 # Semeru 21 (boot/build JDK), GNU make, cups headers; OpenJ9, OMR and
                                  # openj9-openjdk-jdk21 at the pinned commits
./build-openj9-macos.sh           # macOS arm64 sanity build (also the JDK that jlinks the iOS image)
./build-openj9-ios.sh iphonesimulator
./build-openj9-ios.sh iphoneos
./smoke-openj9-ios.sh iphonesimulator vm     # optional: boot the VM in a simulator, no app
cd .. && make jvm-ios && make gen && make build   # in the app repository
```

A full from-scratch build takes about 40 minutes on an M4 Pro (the three builds can run in parallel
once `apply-openj9-patches.sh` has run). `package.sh` writes to `$EMBEDDED_DIR`, which defaults to
`build/embedded` next to `jvm-ios/`; the app's `make jvm-ios` passes its `ios/Embedded`.

## Provenance (exact sources)

| What | Version |
|---|---|
| Eclipse OpenJ9 | tag `openj9-0.61.0`, commit `a1daf225f5cb0ccfb5d7309014701570435a94c4` (github.com/eclipse-openj9/openj9) |
| Eclipse OMR | tag `openj9-0.61.0`, commit `ebd02d9129dc06fa67a6f885f99a3926aa869154` (github.com/eclipse-openj9/openj9-omr) |
| OpenJDK 21 class library | `ibmruntimes/openj9-openjdk-jdk21` branch `v0.61.0-21.0.12.15-release`, commit `2e01e904eaf92c2c2f045763872432cc2053e8cf` (jdk-21.0.12.1+1) |
| Bundled in OpenJ9 | libffi "pre 3.5" (`runtime/libffi`, MIT), zlib 1.3.2 (`runtime/zlib`, libj9zlib, Zlib licence) |
| Boot and build JDK | IBM Semeru 21.0.12.15 (OpenJ9 0.61.0), exactly the release these commits produced |
| Toolchain | Xcode 27.1 (iOS SDK 27.1), deployment target iOS 26.0, GNU make 4.4.1, CMake from Homebrew |
| Patches | `patches/openj9/*.patch`, listed below |

The built VM reports `java.vm.version` `21.0.12.1+1-openj9-0.61.0` (configured with
`--without-version-pre --without-version-opt --with-version-build=1 --with-build-user=oneechan`).

## Scripts

| Script | Purpose |
|---|---|
| `fetch-openj9.sh` | Semeru 21 (boot and build JDK, desktop OpenJ9 for tests), GNU make 4.x, the CUPS headers, and shallow clones of the three trees at the pinned commits plus the release tags. |
| `apply-openj9-patches.sh` | Applies `patches/openj9/*.patch` idempotently (`jdk-*`, `openj9-*`, `omr-*` name the tree). |
| `build-openj9-macos.sh` | macOS arm64 sanity build (`images`, JIT included), non-compressed refs like Semeru. |
| `build-openj9-ios.sh iphoneos\|iphonesimulator` | Cross build: the VM (OpenJ9's CMake build, `j9vm-build`, which the `copy` targets depend on), the classes (`java gendata copy`) and the JDK's static JNI libraries (`<module>-static-libs` for java.base, java.prefs, java.management, jdk.management, jdk.net). After an OpenJ9 or OMR change, the target `java.base-copy` rebuilds just the VM (`j9vm-build`) incrementally; `hotspot` alone does not rebuild it. |
| `prelink-openj9-ios.py PLATFORM BUILD OUT` | Turns the VM's dylibs and the JDK's static libraries into one static archive `libopenj9.a` plus `link-flags.txt` (see "Static linking"). Run by `package.sh`. |
| `jlink-ios.sh` | Packs jmods without native code and jlinks `java.home`. |
| `package.sh` | Writes `$EMBEDDED_DIR` (default `build/embedded`). |
| `smoke-openj9-ios.sh PLATFORM vm\|host` | App-less tests of the static VM; see "Tests". |
| `tools/run-ios-tool` | `OMR_EXE_LAUNCHER` for the cross builds (see below). |
| `tools/mkpatch.sh` | Developer helper: saves edits in a tree as a patch with a header. |

## The package (`$EMBEDDED_DIR`: the app's `ios/Embedded/`, which it bundles)

| Path | Content |
|---|---|
| `jre/` | jlinked `java.home`: `lib/modules`, OpenJ9's `lib/options.default` and NLS catalogues, `conf/`, `legal/<module>/` (every module carries LICENSE, ASSEMBLY_EXCEPTION and ADDITIONAL_LICENSE_INFO, as JDK jmods do; plain files, jlink's symlinks are replaced) |
| `jre/lib/oneechan-jvm.options` | the VM's own options, from `jvm-options/openj9.options` |
| `host/oneechan-host.jar` | the extension host (`extension-host/host-embedded`) |
| `jvm.xcconfig` | `JVM_LDFLAGS[sdk=iphoneos*]` / `[sdk=iphonesimulator*]`: `-force_load libopenj9.a -lz -lc++ -liconv` and CoreFoundation, Foundation, CFNetwork, MobileCoreServices |
| `licenses/{openj9,omr,openjdk,libffi,zlib}/` | licence and notice files plus `component.toml` (read by `Tools/licenses`; bundled as an app resource) |

No frameworks and no dylibs are embedded.

## How the iOS build works

- **Cross build.** OpenJDK's configure sees a cross build to `aarch64-apple-darwin` (target OS
  `macosx`) with the iOS SDK as sysroot. CC/CXX are generated wrappers
  (`work/openj9-ios/<platform>/bin/{cc,c++}`) that always pass `-target arm64-apple-ios26.0[-simulator]`
  and the iOS SDK, and drop macOS flags (`-mmacosx-version-min`, a macOS `-isysroot`). Every object
  is an iOS object; macOS code paths are used where the APIs exist on iOS, and `TARGET_OS_IPHONE`
  guards the rest.
- **Build tools.** OMR and OpenJ9 run tools they build (hookgen, tracegen, tracemerge, constgen,
  which computes struct offsets for the target). They are iOS binaries, so `OMR_EXE_LAUNCHER` is
  `tools/run-ios-tool`, which runs a copy retagged as macOS with `vtool` (same CPU and ABI).
- **No JIT.** `OMR_COMPILER=OFF`, `OMR_JIT=OFF`, `J9VM_MODULE_CODERT_VM=OFF`,
  `J9VM_MODULE_J9JIT_VM=OFF`: `runtime/compiler` is not built and there is no `libj9jit29`. The VM
  reports `(JIT disabled, AOT disabled)`; the options also say `-Xint -Xnojit -Xnoaot`.
- **Non-compressed references** (`--with-noncompressedrefs`, as IBM Semeru on macOS arm64).
  Compressed refs need class memory below 4 GB, which arm64 Mach-O executables reserve as
  `__PAGEZERO`. A mixed-refs macOS build failed with "Failed to instantiate compressed references
  metadata".
- No DDR, no JFR, no OpenSSL; JITServer, CRIU and CRaC are off by default.

## Static linking (strategy a)

Chosen over embedding each library as a framework (strategy b). The reasons:
- The result is one signed binary, with nothing loaded at run time and no per-library framework
  packaging, Info.plists or signing.
- The app keeps its existing integration: the shim's `dlsym(RTLD_DEFAULT, "JNI_CreateJavaVM")`,
  `-force_load` in `jvm.xcconfig`, and `DEAD_CODE_STRIPPING=NO`.
- OpenJ9 already supports statically linked JNI libraries (JEP 178: `JNI_OnLoad_<lib>`).
- It needs only small source patches, because the static-linking work happens after OpenJ9's normal
  dylib build.

How it works:

1. The VM is built as usual as iOS dylibs. `prelink-openj9-ios.py` takes each component's exact
   link inputs from CMake's `link.txt` (objects and the static archives it links privately) and
   prelinks them with `ld -r` into one object. Only the component's **intended exports** stay global:
   CMake's `EXPORTED_SYMBOLS`, which the OMR patch writes to `<target>.exports` on Apple platforms
   (OMR otherwise exports everything on macOS). The private copies of `j9util`, `omrutil` and so on
   in every component become local, as they are in separate dylibs, so they cannot clash.
2. Entry points that several components define (`J9VMDllMain`, `JVM_OnLoad`, `JVM_OnUnload`,
   `JCL_OnLoad`, `JNI_OnLoad`, `JNI_OnUnload`) are renamed `<symbol>_<library>` with `ld -alias`. For
   OpenJ9's `management_ext` that is exactly `JNI_OnLoad_management_ext`. OpenJ9's four
   `jdk.internal.reflect.ConstantPool` natives, which libjava also defines, are renamed the same way.
3. A generated table maps `(library, exported name)` to addresses. A constructor registers it with
   the port library (`omrsl_register_static_libraries`, OMR patch). `j9sl_open_shared_library("j9gc29")`
   then returns a descriptor into the table instead of calling `dlopen`, and `lookup_name("J9VMDllMain")`
   finds `J9VMDllMain_j9gc29`. The JDK libraries are in the table too, because the VM loads
   `libjimage` itself to read `lib/modules`.
4. The JDK's native libraries are built with `STATIC_LIBS=true` (`JNI_OnLoad_<lib>`) and prelinked
   one per library (`ld -r -force_load`). That makes their hidden helpers local: OpenJ9's JDK defines
   `getTraceInterfaceFromVM` in both libjava and libnio. The Java side finds them as built-in
   libraries through the process handle, as on HotSpot.
5. `libjvm` (`runtime/j9vm/jvm.c`) normally derives `java.home` from its own dylib path and `dlopen`s
   `libj9vm29`, `libj9thr29` and `libj9prt29`. When its code is in an `MH_EXECUTE` image, the
   OpenJ9 patch instead does three things: it takes the home from `-Djava.home`, uses
   `<java.home>/lib/default` as the VM directory, and looks the entry points up in the executable.
   The check uses the Mach-O file type, because XCTest's `DYLD_INSERT_LIBRARIES` can put another
   image at index 0.
6. Linked in (14 components): `jvm`, `j9vm29`, `j9thr29`, `j9prt29`, `omrsig`, `j9hookable29`,
   `j9gc29`, `jclse29`, `j9zlib29`, `j9vrb29`, `j9dmp29`, `j9trc29`, `j9jvmti29`, `management_ext`.
   - JDK libraries: `java` (+ `fdlibm`), `zip`, `jimage`, `net`, `nio`, `syslookup`, `management`,
     `prefs` and `extnet`.
   - Not linked: `j9jit29` (not built), `j9shr29` (`-Xshareclasses:none`), and the `-Xcheck`
     libraries `j9vmchk29`, `j9gcchk29` and `j9jnichk29`.
   - `omrsig`'s libc interposers (`signal`, `sigaction`, ...) stay private, so they never replace
     libc's for the rest of the app.

## No executable memory

- Nothing JIT-related is built or linked. `pthread_jit_write_protect_np` (MAP_JIT memory) becomes a
  no-op on iOS, and the icache flush uses `sys_icache_invalidate`.
- The only remaining users of `OMRPORT_VMEM_MEMORY_MODE_EXECUTE` are OpenJ9's FFM (Panama) upcall
  thunks, which the host never uses.
- **Audit at run time:** `smoke-openj9-ios.sh iphonesimulator vm` lists the task's executable VM
  regions before `JNI_CreateJavaVM` and after a workload. The workload covers the interpreter,
  invokedynamic and lambdas, streams, regex, threads, GC, and the zip/net/nio natives. Any new
  executable region outside a loaded image fails the test. Result: `8 before, 8 after, 0 anonymous`.

## JVM options

`JNITransport` sets the common options: class path, `java.io.tmpdir`, `user.home`, `-Xmx`, headless,
UTF-8 and `-Djava.home`. It then appends the VM's own list from `jre/lib/oneechan-jvm.options`,
expanding `${dataDir}`, `${cacheDir}`, `${tmpDir}` and `${javaHome}`. The file is required: if the
bundled jre lacks it, the embedded host does not start and reports an error telling you to rebuild
with `make jvm-ios` (there is no HotSpot option fallback). `package.sh` copies
`jvm-options/openj9.options` into the jre as that file. Its content (OpenJ9):

```
-Xint -Xnojit -Xnoaot
-Xverify:none                     # extensions are R8-optimized for ART (OpenJ9 21 prints JVMJ9VM193W, a deprecation warning)
-Xgcpolicy:gencon
-Xms40m -Xmns8m -Xmnx16m          # 32 MB tenured space and an 8-16 MB nursery from the start
-Xgcthreads2                      # about 30% less GC CPU time than one GC thread per CPU
-Xshareclasses:none
-Dcom.ibm.tools.attach.enable=no  # the attach API needs /tmp and SysV semaphores
-Xdump:directory=${cacheDir}  -Xdump:system:none  -Xdump:heap:none   # javacores only, in Caches
-Xtrace:none                      # no trace engine; javacores keep their content (see below)
```

The heap and GC options come from `-Xverbosegclog` runs of the host in the simulator (boot, repo
index parsing, listing, the JS self-test, and 2.6 MB response bodies), 2026-10-23:
- With OpenJ9's default 8 MB start (2 MB nursery), the host ran about 10 global collections, some
  with compaction, in the first 2 s while the tenured space grew. With the options above it runs at
  most one, and about half as many scavenges overall.
- `-Xms` is committed (`mprotect`), not touched. Footprint is +5-7 MB right after start (the nursery)
  and about 5 MB lower than the default under load, where the default nursery grows past 16 MB.
  `-Xms` must stay at or below the `-Xmx` that `JNITransport` passes (512 MB by default).
- `-Xgcthreads2`: the same collections took about 30% less GC CPU time than with 6 threads, for
  slightly longer pauses, and the VM has 4 fewer threads.
- `-Xtrace:none`: javacores show the same sections, threads and stacks. Their trace history sections
  ("Current thread history", "GC History") are empty either way, because the jre ships no trace
  format files. What is lost is the binary `Snap*.trc` written next to a crash javacore, which
  Semeru's `traceformat` can read on a Mac; drop the option to get it back while debugging.

`EmbeddedBridge` refuses to start if the JVM verifies extension bytecode. On a desktop HotSpot JDK
(the development host and the Kotlin tests) it reads `BytecodeVerificationRemote`, and a HotSpot
without that flag counts as verifying instead of passing. On OpenJ9 (the embedded iOS runtime),
where that MXBean call throws, the last `-Xverify[:...]` input argument decides
(`BytecodeVerification.kt`).

## Patches (`patches/openj9/`)

| Patch | What |
|---|---|
| `jdk-cross-cmake-darwin` | Cross builds to Apple targets: CMake system `Darwin`, not `Macosx` |
| `jdk-copy-without-jit` | Copy `libj9jit29` only if it was built |
| `jdk-ios-native-libraries` | libjava `java_props_macosx.c`: iOS ObjC headers, no SCDynamicStore (system proxies are not mapped to properties); libnet: CFNetwork header, no `net/if_arp.h`; no libosxsecurity / libjli in STATIC_LIBS builds |
| `omr-ios-port-library` | iOS: no system core dumps (no fork/mach_vm), no libproc queries, JIT write-protect toggles are no-ops, `sys_icache_invalidate` |
| `omr-static-shared-libraries` | the static library table in `omrsl.c`; intended export lists written as `<target>.exports` |
| `openj9-static-vm-in-executable` | `jvm.c` for a VM linked into the executable (`-Djava.home`); export of the table registration from j9prt |
| `openj9-build-without-jit` | `OMR_COMPILER=OFF` skips `runtime/compiler` |
| `openj9-libffi-no-cfi-on-apple` | Xcode 26+ rejects the bundled libffi's aarch64 CFI directives |
| `openj9-jit-honour-disable-warnings-as-errors` | macOS sanity build: newer clang warnings in the JIT |
| `openj9-static-ras-struct-on-apple-arm64` | `rasdump.c`: the static RAS struct on macOS and iOS arm64. Without compressed references OpenJ9 probes every page below 4 GB for it, which is all `__PAGEZERO` on arm64, then uses the static struct anyway (about 165 ms per boot on an iPhone 18 Pro) |

Applied to pristine trees, they reproduce the working trees exactly (20 files). `work/` was rebuilt
from scratch with the first nine: fetch, three builds, package, app and UI test.
`openj9-static-ras-struct-on-apple-arm64` was added later with an incremental VM rebuild
(`./build-openj9-ios.sh <platform> java.base-copy`, then `make jvm-ios`).

## Tests

| Test | Command |
|---|---|
| Kotlin: bridge verification check | `cd extension-host && ./gradlew :host-embedded:test` |
| Swift, macOS (HotSpot in-process, OpenJ9 = Semeru's `lib/server/libjvm.dylib` in a child process `EmbeddedHostProbe` because one process holds one JVM; options parsing) | `swift test --package-path ios/Packages/OneechanKit --filter EmbeddedHostTests` (9 tests) |
| Static VM in a simulator, no app: boot, workload, exec-memory audit | `./smoke-openj9-ios.sh iphonesimulator vm` |
| The host through the app's shim in a simulator, with the bundled options (and an inserted library, as XCTest does), plus two bodies through the shim's binary channel for extension HTTP | `SHIM_DIR=<app>/ios/Packages/OneechanKit/Sources/OneechanJVM ./smoke-openj9-ios.sh iphonesimulator host` |
| Release gate in the app: add the keiyoushi repo, install MangaDex, load popular, open a manga | `TEST_RUNNER_ONEECHAN_EMBEDDED_UI=1 xcodebuild test ... -only-testing:OneechanUITests/EmbeddedHostUITests` |
| More names the OpenJ9 runtime (screenshot attached) | `TEST_RUNNER_ONEECHAN_EMBEDDED_UI=1 xcodebuild test ... -only-testing:OneechanUITests/EmbeddedRuntimeUITests` |

On a **fresh** simulator, iOS first asks "Open in “Oneechan”?" when a test opens the app's URL scheme,
and that prompt stalls the release gate. Approve it once with
`TEST_RUNNER_ONEECHAN_APPROVE_URL_OPEN=1 xcodebuild test ... -only-testing:OneechanUITests/SimulatorSetupUITests`.

## Results

Measured 2026-10-22 on an M4 Pro: Xcode 27.1, iOS 27.0 simulator "OpenJ9-iPhone17", Release device builds.

**Acceptance**
- `swift test --package-path ios/Packages/OneechanKit --filter EmbeddedHostTests`: 9/9 pass. That is
  HotSpot over JNI, OpenJ9 (Semeru) over JNI, OpenJ9 refusing to run with verification on, and the
  option parsing.
- App on the simulator: the console prints `Extension host runtime booted`, and More shows
  "Runtime: Eclipse OpenJ9 VM 21.0.12.1" (`EmbeddedRuntimeUITests`).
- `EmbeddedHostUITests` (keiyoushi repo, MangaDex install, popular, details) passes on a clean
  install in 30.7 s.
- `xcodebuild -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build` links Debug and
  Release for the device.

**Footprint and speed** (static, interpreter only; 16 jlinked modules)

| | OpenJ9 0.61 |
|---|---|
| App executable, Release, device (14.25 MB without a JVM) | 23.63 MB (+9.38) |
| Frameworks / dylibs embedded | none |
| `jre/` (apparent size) | 92.4 MB (`lib/modules` 86.6 MB, OpenJ9 NLS catalogues 4.5 MB) |
| Release app bundle | 198.6 MB (jre 92 MB, host jar 71 MB, executable 23.6 MB) |
| Host ready via the app's shim (JNI_CreateJavaVM + EmbeddedBridge.start), simulator, median of 5 | 0.533 s |
| App launch to "runtime booted", simulator; first launch after install, then 3 more | 1.14 s, then 0.97–1.09 s |
| VM + host memory after start (footprint / resident) | 35.4 / 69.5 MB |
| App RSS 5 s after the host booted (simulator, whole app) | 322 MB |
| App peak RSS during the release gate (clean install) | 660 MB |
| Release gate duration (clean install; network-bound) | 30.7 s (install 3.6 s) |

## Licences to ship

`package.sh` copies these into `$EMBEDDED_DIR/licenses/` (the app's `ios/Embedded/licenses/`), and the app bundles them as a resource
(`Embedded/licenses` in `project.yml`):
- `openj9/`: OpenJ9 `LICENSE`, `NOTICES.md` and `epl-2.0.html`.
- `omr/`: OMR `LICENSE` and `NOTICE.md`.
- `openjdk/`: `LICENSE`, `ASSEMBLY_EXCEPTION`, `ADDITIONAL_LICENSE_INFO` and `openj9-openjdk-notices`.
  The jlinked image also carries the three OpenJDK files in every `legal/<module>/`, plus the
  third-party notices of each module, except those of native code the app does not link
  (`NOT_LINKED_NOTICES` in `jlink-ios.sh`: java.desktop's FreeType, HarfBuzz, libjpeg, LittleCMS,
  libpng, giflib, Mesa, PipeWire and xwd, and java.base's SipHash).
- `libffi/` (MIT) and `zlib/` (Zlib): both are bundled and modified in OpenJ9.

Each directory has a `component.toml` (version, licence choice, how it ships) for `Tools/licenses`.
Licence choices:
- OpenJ9 and OMR: Apache-2.0 (multi-licensed).
- Class library: GPLv2 + Classpath Exception. Its corresponding source, including `patches/openj9/jdk-*`,
  must be offered. OpenJ9 and OMR are modified as well (`openj9-*` and `omr-*` patches); EPL-2.0
  would also require their source, while Apache-2.0 requires that the changes are marked.

After repackaging, regenerate the app's notices with `make licenses`.

## Known limitations and next steps

- Not yet run on a physical device. The device build compiles and links, with no executable memory
  and no JIT symbols. Next step: run `smoke-openj9-ios.sh iphoneos` binaries or the app on a device.
- `os.name` is "Mac OS X" (it is the macOS class library), and system proxy settings are not mapped
  to `http.proxyHost` and so on, because extension HTTP goes through the app.
- No shared classes cache (`-Xshareclasses:none`). On macOS, OpenJ9 with SCC started 0.18 s faster;
  a cache inside the app container is worth trying.
- `jre/lib/modules` is not compressed. `JLINK_EXTRA="--compress zip-6"` roughly
  halves it, but class loading then pays for the inflation.
