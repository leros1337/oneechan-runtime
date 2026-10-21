# Oneechan runtime

The extension runtime of [Oneechan](https://github.com/leros1337/oneechan), an iOS manga reader. It
runs unmodified Tachiyomi/Mihon extensions (Android APKs or the JVM jars some repos publish) on the
iPhone itself, with no server: repo indexes are fetched, extensions downloaded, verified, converted
from dex with dex2jar and loaded on the device.

## Layout

| Path | What it is |
|---|---|
| `extension-host/` | The Kotlin extension host (Gradle). Reuses Suwayomi's AndroidCompat and source API (submodule `vendor/suwayomi`, plus overlays), installs and trusts extensions the way Mihon does, and answers a JSON-RPC protocol. |
| `extension-host/host-embedded/` | The jar the app embeds; its JNI entry points are called by the app's native shim. |
| `extension-host/host-desktop/` | A development host serving the same protocol over HTTP + Server-Sent Events. |
| `extension-host/android-stubs/` | Android API stub jar generated from AOSP's Apache-2.0 API signature. |
| `jvm-ios/` | Builds Eclipse OpenJ9 (interpreter only) with the OpenJDK 21 class library as static libraries for iOS, and packages the runtime the app bundles. HotSpot Zero is kept for development only. |
| `contract/` | Golden fixtures of the RPC protocol (and of backup interop), shared with the app's Swift tests. |

## Building

The extension host needs JDK 21:

```sh
cd extension-host
./gradlew test                 # unit tests
./gradlew :host-desktop:run    # development host on 127.0.0.1:4568
```

The iOS runtime needs Xcode and the steps in `jvm-ios/README.md`. Packaging writes the runtime the
app bundles (java.home, host jar, link flags, licence files):

```sh
EMBEDDED_DIR=/path/to/app/ios/Embedded jvm-ios/package.sh   # default: build/embedded
```

Inside the app repository this repository is the `runtime/` submodule, and `make jvm-ios` does the
above.

## Licence

MPL-2.0 for Oneechan's own code; see `NOTICE` for the projects this builds on and their licences.
