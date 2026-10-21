# jvm-ios: the embedded JVM for iOS

Oneechan runs the extension host (a Java 21 fat jar) inside the iOS app, on a JVM linked into the app
binary. iOS forbids JIT compilation and writable+executable memory, so the JVM runs as an interpreter.

| VM | Licence of the VM | Status |
|---|---|---|
| **Eclipse OpenJ9 0.61.0** + OMR, with the OpenJDK 21 class library (ibmruntimes) | EPL-2.0 / Apache-2.0; class library and JDK native libraries GPLv2 + Classpath Exception | **Ships.** `make jvm-ios` (default `VM=openj9`). |
| HotSpot Zero (openjdk/mobile, JDK 28) | GPLv2 **without** the Classpath Exception | **Development fallback only. Never distribute.** `make jvm-ios VM=zero` prints a warning and marks `jvm.xcconfig`. |

Everything that is downloaded, cloned or built goes to `work/` (gitignored). Nothing is installed system-wide.

## Quick start (OpenJ9)

```sh
cd jvm-ios
./fetch.sh                        # Semeru 21 (boot/build JDK), libffi, GNU make, cups headers
./fetch-openj9.sh                 # OpenJ9, OMR and openj9-openjdk-jdk21 at the pinned commits
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
| `fetch-openj9.sh` | Shallow clones of the three trees at the pinned commits, plus the release tags. |
| `apply-openj9-patches.sh` | Applies `patches/openj9/*.patch` idempotently (`jdk-*`, `openj9-*`, `omr-*` name the tree). |
| `build-openj9-macos.sh` | macOS arm64 sanity build (`images`, JIT included), non-compressed refs like Semeru. |
| `build-openj9-ios.sh iphoneos\|iphonesimulator` | Cross build: the VM (`hotspot` target = OpenJ9's CMake build), the classes (`java gendata copy`) and the JDK's static JNI libraries (`<module>-static-libs` for java.base, java.prefs, java.management, jdk.management, jdk.net). |
| `prelink-openj9-ios.py PLATFORM BUILD OUT` | Turns the VM's dylibs and the JDK's static libraries into one static archive `libopenj9.a` plus `link-flags.txt` (see "Static linking"). Run by `package.sh`. |
| `jlink-ios.sh` | `VM=openj9\|zero`: packs jmods without native code and jlinks `java.home`. |
| `package.sh` | `VM=openj9` (default) or `zero`; writes `$EMBEDDED_DIR` (default `build/embedded`). |
| `smoke-openj9-ios.sh PLATFORM vm\|host` | App-less tests of the static VM; see "Tests". |
| `metrics-ios.sh` | OpenJ9 vs Zero in the same harness on the simulator (`SHIM_DIR` = the app's `OneechanJVM` sources). |
| `tools/run-ios-tool` | `OMR_EXE_LAUNCHER` for the cross builds (see below). |
| `tools/mkpatch.sh` | Developer helper: saves edits in a tree as a patch with a header. |

## The package (`$EMBEDDED_DIR`: the app's `ios/Embedded/`, which it bundles)

| Path | Content |
|---|---|
| `jre/` | jlinked `java.home`: `lib/modules`, OpenJ9's `lib/options.default` and NLS catalogues, `conf/`, `legal/<module>/` (every module carries LICENSE, ASSEMBLY_EXCEPTION and ADDITIONAL_LICENSE_INFO, as JDK jmods do; plain files, jlink's symlinks are replaced) |
| `jre/lib/oneechan-jvm.options` | the VM's own options, from `jvm-options/<vm>.options` |
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
  `-force_load` in `jvm.xcconfig`, and `DEAD_CODE_STRIPPING=NO`, as for Zero.
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
expanding `${dataDir}`, `${cacheDir}`, `${tmpDir}` and `${javaHome}`. Without the file it uses the
HotSpot list as before. For OpenJ9 (`jvm-options/openj9.options`):

```
-Xint -Xnojit -Xnoaot
-Xverify:none                     # extensions are R8-optimized for ART (OpenJ9 21 prints JVMJ9VM193W, a deprecation warning)
-Xgcpolicy:gencon
-Xshareclasses:none
-Dcom.ibm.tools.attach.enable=no  # the attach API needs /tmp and SysV semaphores
-Xdump:directory=${cacheDir}  -Xdump:system:none  -Xdump:heap:none   # javacores only, in Caches
```

`EmbeddedBridge` refuses to start if the JVM verifies extension bytecode. On HotSpot it reads
`BytecodeVerificationRemote` as before, but a HotSpot without that flag now counts as verifying
instead of passing. On OpenJ9, where that MXBean call throws, the last `-Xverify[:...]` input argument
decides (`BytecodeVerification.kt`).

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

Applied to pristine trees, they reproduce the working trees exactly (19 files). `work/` was rebuilt
from scratch with them: fetch, three builds, package, app and UI test.

## Tests

| Test | Command |
|---|---|
| Kotlin: bridge verification check | `cd extension-host && ./gradlew :host-embedded:test` |
| Swift, macOS (HotSpot in-process, OpenJ9 = Semeru's `lib/server/libjvm.dylib` in a child process `EmbeddedHostProbe` because one process holds one JVM; options parsing) | `swift test --package-path ios/Packages/OneechanKit --filter EmbeddedHostTests` (9 tests) |
| Static VM in a simulator, no app: boot, workload, exec-memory audit | `./smoke-openj9-ios.sh iphonesimulator vm` |
| The host through the app's shim in a simulator, with the bundled options (and an inserted library, as XCTest does) | `SHIM_DIR=<app>/ios/Packages/OneechanKit/Sources/OneechanJVM ./smoke-openj9-ios.sh iphonesimulator host` |
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

**OpenJ9 vs Zero** (both static, interpreter only; same app, host jar and 16 jlinked modules)

| | OpenJ9 0.61 | HotSpot Zero (JDK 28) |
|---|---|---|
| App executable, Release, device (14.25 MB without a JVM) | 23.63 MB (+9.38) | 24.51 MB (+10.26) |
| Frameworks / dylibs embedded | none | none |
| `jre/` (apparent size) | 92.4 MB (`lib/modules` 86.6 MB, OpenJ9 NLS catalogues 4.5 MB) | 87.5 MB (`lib/modules` 86.2 MB) |
| Release app bundle | 198.6 MB (jre 92 MB, host jar 71 MB, executable 23.6 MB) | about 4 MB smaller (jre −4.9 MB, executable +0.9 MB) |
| Host ready via the app's shim (JNI_CreateJavaVM + EmbeddedBridge.start), simulator, median of 5 (`metrics-ios.sh`) | 0.533 s | 0.569 s |
| App launch to "runtime booted", simulator; first launch after install, then 3 more | 1.14 s, then 0.97–1.09 s | 1.21 s, then 1.02 s |
| VM + host memory after start (footprint / resident), same harness | 35.4 / 69.5 MB | 36.0 / 65.9 MB |
| App RSS 5 s after the host booted (simulator, whole app) | 322 MB | 322 MB |
| App peak RSS during the release gate (clean install) | 660 MB | 760 MB |
| Release gate duration (clean install; network-bound) | 30.7 s (install 3.6 s) | 30.1 s (install 3.6 s) |

On macOS (S0 table below), OpenJ9 21 `-Xint` was already faster than Zero on every host operation
(for example `repo.add` 1.66 s vs 3.49 s and extension install 3.14 s vs 4.04 s). The simulator
numbers above are dominated by the network and the UI.

## Licences to ship

`package.sh` copies these into `$EMBEDDED_DIR/licenses/` (the app's `ios/Embedded/licenses/`), and the app bundles them as a resource
(`Embedded/licenses` in `project.yml`):
- `openj9/`: OpenJ9 `LICENSE`, `NOTICES.md` and `epl-2.0.html`.
- `omr/`: OMR `LICENSE` and `NOTICE.md`.
- `openjdk/`: `LICENSE`, `ASSEMBLY_EXCEPTION`, `ADDITIONAL_LICENSE_INFO` and `openj9-openjdk-notices`.
  The jlinked image also carries the three OpenJDK files in every `legal/<module>/`, plus the
  third-party notices of each module.
- `libffi/` (MIT) and `zlib/` (Zlib): both are bundled and modified in OpenJ9.

Each directory has a `component.toml` (version, licence choice, how it ships) for `Tools/licenses`.
Licence choices:
- OpenJ9 and OMR: Apache-2.0 (multi-licensed).
- Class library: GPLv2 + Classpath Exception. Its corresponding source, including `patches/openj9/jdk-*`,
  must be offered. OpenJ9 and OMR are modified as well (`openj9-*` and `omr-*` patches); EPL-2.0
  would also require their source, while Apache-2.0 requires that the changes are marked.

With OpenJ9 packaged, `make licenses-check` no longer reports the Zero blocker. The generated notices
must be regenerated with `make licenses`.

## Known limitations and next steps

- Not yet run on a physical device. The device build compiles and links, with no executable memory
  and no JIT symbols. Next step: run `smoke-openj9-ios.sh iphoneos` binaries or the app on a device.
- `os.name` is "Mac OS X" (it is the macOS class library), and system proxy settings are not mapped
  to `http.proxyHost` and so on, because extension HTTP goes through the app.
- No shared classes cache (`-Xshareclasses:none`). On macOS, OpenJ9 with SCC started 0.18 s faster;
  a cache inside the app container is worth trying.
- `jre/lib/modules` is not compressed (parity with Zero). `JLINK_EXTRA="--compress zip-6"` roughly
  halves it, but class loading then pays for the inflation.

# HotSpot Zero (development fallback only)

HotSpot's VM sources are GPLv2 without the Classpath Exception: a build that links Zero must never
be distributed. These are the original spikes (S0, S1) and the Zero build, kept for development and
comparison: `make jvm-ios VM=zero`.

### Pinned inputs

| What | Version |
|---|---|
| openjdk/mobile | `c1ed06aaef34c8dccf71e236d1ffa20918a77cfb` (2026-07-30, "Automatic merge of jdk:master"), JDK **28-internal** |
| Boot JDK | Temurin 27+35, macOS aarch64. It must be N-1, and the tree accepts 26, 27 or 28. |
| libffi | 3.8.0, built from source as static `.a` for macOS, iphoneos and iphonesimulator (all arm64) |
| GNU make | 4.4.1, built into `work/tools` (macOS ships 3.81) |
| cups | 2.4.11 source. Only the headers are used, because configure insists on them for iOS. |
| Xcode | 27.1 (iOS SDK 27.1). Deployment target is iOS 26.0. |
| Comparison JVMs | Homebrew OpenJDK 21.0.13, Temurin 27, IBM Semeru 21.0.12.15 (OpenJ9 0.61.0) |
| Host jar | frozen copy `work/bench/host.jar`, sha256 `0c5c9c6c…e780` |

### Scripts (run in this order)

| Script | Purpose |
|---|---|
| `fetch.sh` | Clones openjdk/mobile at the pinned commit. Downloads the boot JDK, Semeru, libffi, make and cups. Builds GNU make. |
| `build-zero-macos.sh` | S0: libffi (macOS) plus the `macosx-aarch64-zero-release` JDK image. It applies `patches/`. |
| `bench.sh JAVA_HOME [jvm args]` | S0: one benchmark config. It wraps `bench_driver.py` (stdlib Python). Env: `LABEL RUNS NO_GUARD HOST_FLAGS STOP_AFTER`. |
| `bench-all.sh` | S0: the whole matrix (`ONLY=jdk21,jdk27,zero,zero-cds,j9`). It prints a table from `work/bench/results.jsonl`. |
| `build-jdk-ios.sh iphoneos\|iphonesimulator [targets]` | S1: libffi (iOS) plus a cross-build of the Zero JDK. Use `static-libs-image java gendata copy`. |
| `jlink-ios.sh PLATFORM OUT [modules]` | S1: packs jmods without native libs from the iOS build, then jlinks a runtime (`java.home`). |
| `build-spike.sh iphonesimulator run` / `build-spike.sh iphoneos` | S1: XcodeGen, then build. For the simulator it also installs, launches and captures the console (`work/spike/console-*.log`). |
| `bench-sim.sh` | Runs the S0 benchmark against the extension host running inside the simulator spike app. |
| `bench-support/NoGuardHostLauncher.java` | Benchmark-only `main` that matches `MainKt.main` but skips the `HotSpotDiagnosticMXBean` guard. Needed for OpenJ9 and for CDS training. |
| `bench-support/check-cds.sh` | Counts the classes that really come from a CDS/AOT archive. |

### Patches to openjdk/mobile (`patches/`, applied idempotently by the build scripts)

1. **`0001-zero-apple-aarch64-wx-symbols.patch`** fixes a Zero link failure on macOS and iOS aarch64.
   - Shared code now references `DefaultWXWriteMode`, `os::_jit_exec_enabled` and `os::thread_wx_enable_write_impl()`. These come from the lazy W^X change and are only defined in `os_cpu/bsd_aarch64`.
   - Zero compiles `os_cpu/bsd_zero` instead, so the patch adds no-op definitions there.
2. **`0002-ios-embedded-static-jvm.patch`**, under `__IOS__` only:
   - *java.home.* In a static JDK, `os_bsd.cpp` derives the home from the executable, which gives `<App>.app/lib`, and then exits with "Failed setting boot class path."
     - The patch defers that error.
     - `Arguments::parse` now pre-scans the JNI options for `-Djava.home=` before the jimage is opened. The value sets `java.home`, the `lib/modules` boot image and `sun.boot.library.path`.
     - `finalize_vm_init_args` fails cleanly when no home was found.
   - *No executable memory.* `pd_commit_memory(exec=true)` used `mprotect(PROT_EXEC)`, which iOS refuses ("Could not reserve enough space in CodeCache (160K)"). Zero never executes code from the code cache, so the patch commits that memory as RW. `MEM_PROT_RWX` likewise becomes RW.

### Build workarounds

- **iOS `images`/jmods target does not build.** `java.base.jmod` depends on the *dynamic* libjvm, libjava and so on.
  - For iOS, those are linked with GNU-ld flags: `-Wl,-soname`, `-Wl,-z,defs`, `-z,origin` and a `$ORIGIN` rpath.
  - Only `static-libs-image` works, and it is all the README in openjdk/mobile promises.
  - `jlink-ios.sh` builds jmods itself from the build's classes, conf and lib data (`make java gendata copy`), using the macOS build JDK's `jmod`/`jlink`. That macOS build has the exact same version, which is why `build-zero-macos.sh` comes first.
- **iOS configure flags.**
  - `--openjdk-target=aarch64-macos-ios --with-sysroot=$(xcrun --sdk iphoneos --show-sdk-path)`
  - `--with-build-jdk=<macOS zero image> --with-jvm-variants=zero`
  - `--with-libffi-include/lib=work/libffi-<platform>-arm64`
  - `--with-cups-include=work/src/cups-2.4.11`
  - `--with-extra-{c,cxx,ld}flags="-target arm64-apple-ios26.0[-simulator] -m{iphoneos,ios-simulator}-version-min=26.0"`
- **Simulator.** The same configure works with the iPhoneSimulator SDK and an `-simulator` target triple. The resulting objects are `LC_BUILD_VERSION platform 7`.
- **Linking the app.** Use the following:
  - `-force_load` for `libjvm.a` (from `lib/zero`) plus `java verify zip jimage net nio syslookup fallbackLinker management management_ext prefs extnet`
  - libffi.a, `-lz -lc++`, CoreFoundation and Foundation
  - Never `libjsig`.
  - The static JDK resolves `JNI_OnLoad_<lib>`, `JIMAGE_*` and `JDK_Canonicalize` with `dlsym(RTLD_DEFAULT)`, so these must stay exported from the main executable. They do by default. The spike also sets `DEAD_CODE_STRIPPING=NO` and `ENABLE_DEBUG_DYLIB=NO`.
- **Embedder JVM options** (`spike-app/Sources/jvm_bridge.c`): `-Djava.home=<App>/jre -Djava.class.path=… -Xshare:off -XX:-UsePerfData -XX:+UseSerialGC -Xmx… -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote`. `JNI_CreateJavaVM` runs on a pthread with an 8 MB stack.
- **Benchmarks.**
  - The host jar is rebuilt by other work, and once it was replaced mid-run, which caused `NoClassDefFoundError`. `bench.sh` therefore uses a frozen copy.
  - The SSE stream is chunked, and long `ext.progress` frames span chunks.
  - **Any CDS/AOT dump forces `BytecodeVerificationRemote=true`** (`cdsConfig.cpp`). The host's guard then exits, so training runs use the no-guard launcher.
  - Training must not load extension classes. In a first pass, dump-time-verified extension classes in a dynamic archive made the later, unverified extension load fail with `VerifyError`. AppCDS training therefore stops after `repo.add`.
  - CDS also rejects class-path *directories* and jars whose mtime has changed.
- **OpenJ9.** `getVMOption("BytecodeVerificationRemote")` throws on OpenJ9, so at the time of S0 the host guard always exited 2 and OpenJ9 was benchmarked with `NoGuardHostLauncher` and `-Xverify:none`. `EmbeddedBridge` now reads OpenJ9's `-Xverify` options instead (`BytecodeVerification.kt`).

### S0 results (macOS, M4 Pro, median of 3 runs, seconds; RSS in MB)

The columns are:
- **a**: JVM start until `/health` returns OK.
- **b**: first `host.hello` call.
- **c**: `repo.add` keiyoushi.
- **d**: `ext.install` MangaDex with `forceApk`, from request to DONE.
- **conv**: CONVERT→LOAD (dex2jar).
- **load**: LOAD→DONE.
- **e**: first `source.popular`, page 1.
- **f**: page 1 again.
- **f2**: page 2 (warm, uncached).
- **cpu**: total CPU time of the JVM process.

The MangaDex APK is only 98 KB. Raw runs are in `work/bench/results.jsonl` and `python3 -I bench_driver.py --table …` prints them.

| config | a | b | c | d | conv | load | e | f | f2 | RSS | cpu |
|---|---|---|---|---|---|---|---|---|---|---|---|
| HotSpot 21 JIT | 0.23 | 0.02 | 1.16 | 0.68 | 0.29 | 0.04 | 0.27 | 0.13 | 0.13 | 210 | 2.9 |
| HotSpot 21 -Xint | 0.42 | 0.04 | 1.94 | 2.67 | 1.39 | 0.45 | 0.60 | 0.19 | 0.22 | 163 | 4.3 |
| HotSpot 27 JIT | 0.21 | 0.02 | 1.22 | 0.67 | 0.26 | 0.04 | 0.33 | 0.12 | 0.13 | 210 | 3.1 |
| HotSpot 27 -Xint | 0.49 | 0.04 | 2.37 | 2.64 | 1.32 | 0.44 | 0.60 | 0.18 | 0.19 | 141 | 4.9 |
| **Zero 28** (default CDS) | 0.76 | 0.05 | 3.49 | 4.04 | 2.16 | 0.73 | 0.83 | 0.22 | 0.23 | 134 | 7.7 |
| Zero 28 -Xshare:off | 0.80 | 0.05 | 3.62 | 4.12 | 2.13 | 0.73 | 0.64 | 0.20 | 0.21 | 127 | 7.6 |
| Zero 28 SerialGC | 0.76 | 0.05 | 3.65 | 4.08 | 2.16 | 0.73 | 0.66 | 0.20 | 0.21 | 110 | 7.6 |
| Zero 28, launcher (baseline for the next two rows) | 0.77 | 0.05 | 3.28 | 4.16 | 2.15 | 0.73 | 0.63 | 0.18 | 0.18 | 133 | 7.7 |
| Zero 28, launcher + AppCDS dynamic (25 MB .jsa) | 0.56 | 0.01 | 3.31 | 4.02 | 2.13 | 0.73 | 0.94 | 0.06 | 0.18 | 141 | 7.3 |
| Zero 28, launcher + AOTCache (48 MB .aot) | 0.53 | 0.00 | 3.04 | 3.87 | 2.07 | 0.72 | 0.58 | 0.17 | 0.18 | 167 | 7.1 |
| Zero 28, app flags (Xmx512m Serial Xshare:off) | 0.79 | 0.05 | 3.23 | 4.03 | 2.14 | 0.73 | 1.15 | 0.19 | 0.18 | 235 | 7.6 |
| OpenJ9 21 -Xint | 0.66 | 0.06 | 1.66 | 3.14 | 1.80 | 0.50 | 0.32 | 0.15 | 0.15 | 132 | 4.9 |
| OpenJ9 21 -Xint + SCC | 0.48 | 0.02 | 1.49 | 3.00 | 1.72 | 0.49 | 0.31 | 0.14 | 0.15 | 116 | 4.5 |
| OpenJ9 21 JIT | 0.42 | 0.03 | 1.05 | 0.80 | 0.41 | 0.05 | 0.28 | 0.13 | 0.13 | 168 | 2.9 |
| **Zero 28 static, inside the iOS-simulator app** | 1.03* | 0.05 | 3.43 | 3.82 | 2.00 | 0.70 | 0.58 | 0.19 | 0.18 | 415** | 7.8 |

\* Includes `simctl launch` and app launch. `JNI_CreateJavaVM` alone takes about 25–60 ms.
\*\* Whole simulator app. The same app without the JVM uses 179 MB.

### S1 results

- Static libs (device):
  - `libjvm.a` is 27.4 MB. All JDK `.a` files together come to 28 MB.
  - Linked into the app, the JDK adds about 10 MB to the executable.
  - The simulator `libjvm.a` is 26.2 MB.
- jlinked runtime:
  - `java.base` alone is 25 MB (`lib/modules` is 24 MB), or 12 MB with `--compress zip-6`.
  - The host's module set from jdeps is 92 MB, or 43 MB with zip-6, or 58 MB without `jdk.javadoc`. That set is `java.base, java.desktop, java.instrument, java.naming, java.net.http, java.prefs, java.scripting, java.sql, jdk.dynalink, jdk.httpserver, jdk.javadoc, jdk.management, jdk.unsupported`.
- Simulator: `JNI_CreateJavaVM` returns OK. `Spike` prints the version and fetches `https://example.com` over TLS 1.3 (title "Example Domain"). The full extension host also runs in the app and passes the whole S0 flow.
- Device (`generic/platform=iOS`): compiles and links, but has not been installed or run.

The spike created a simulator device named `JvmSpike-iPhone17`. Remove it with `xcrun simctl delete JvmSpike-iPhone17`.
