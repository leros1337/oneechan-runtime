# Oneechan runtime

The extension runtime of [Oneechan](https://github.com/leros1337/oneechan), an iOS manga reader. It
runs unmodified Tachiyomi/Mihon extensions (Android APKs, or the JVM jars some repos publish) on the
iPhone itself, with no server. Repo indexes are fetched, extensions downloaded, verified, converted
from dex with dex2jar and loaded, all on the device.

## How it works

The app links a JVM, Eclipse OpenJ9, statically. It runs as an interpreter only, because iOS forbids
JIT compilation. On that JVM the app starts the extension host, a Kotlin program built on Suwayomi's
Android compatibility layer and Tachiyomi source API. The app and the host speak one JSON-RPC
protocol: over JNI on the device, and over HTTP with Server-Sent Events during development.

- **Repos and installs.** `index.min.json` and `index.pb` indexes are supported. An extension's
  manifest and signing certificate are read. Its lib version is checked, and unknown signers need
  the user's trust, as in Mihon. The dex is converted with dex2jar, then the extension is loaded
  in its own class loader.
- **Source calls.** Popular, latest, search with filters, details, chapters, pages, images, related
  titles and source preferences.
- **What Android would provide, the app provides.** The host asks the app for these over the same
  protocol:
  - network requests can go through the app's URLSession;
  - Cloudflare challenges are solved in a WKWebView;
  - extensions that drive an Android `WebView` get a WKWebView;
  - the QuickJs API runs on JavaScriptCore through the app's native shim, with Rhino as the
    fallback.

## Layout

| Path | What it is |
|---|---|
| `extension-host/` | The Kotlin extension host (Gradle). Reuses Suwayomi's AndroidCompat and source API (submodule `vendor/suwayomi`, plus overlays), installs and trusts extensions the way Mihon does, and answers the JSON-RPC protocol. |
| `extension-host/host-core/` | Repos, installs, the source registry and the RPC dispatcher. |
| `extension-host/host-embedded/` | The jar the app embeds; its JNI entry points are called by the app's native shim. |
| `extension-host/host-desktop/` | A development host serving the same protocol over HTTP + Server-Sent Events. |
| `extension-host/android-stubs/` | Android API stub jar generated from AOSP's Apache-2.0 API signature. |
| `jvm-ios/` | Builds Eclipse OpenJ9 (interpreter only) with the OpenJDK 21 class library as static libraries for iOS, and packages the runtime the app bundles. |
| `contract/` | Golden fixtures of the RPC protocol (and of backup interop), shared with the app's Swift tests. |

## Building

Clone with submodules (`git clone --recurse-submodules`, or `git submodule update --init` later):
Suwayomi is a submodule of `extension-host/`.

The extension host needs JDK 21:

```sh
cd extension-host
./gradlew test                       # unit tests (offline)
./gradlew :host-desktop:run          # development host on 127.0.0.1:4568
./gradlew :host-desktop:shadowJar    # the desktop host as one jar
```

The iOS runtime needs a Mac with Xcode and the steps in [`jvm-ios/README.md`](jvm-ios/README.md)
(a first build takes about 40 minutes). Packaging writes the runtime the app bundles: java.home,
the host jar, the link flags and the licence files.

```sh
EMBEDDED_DIR=/path/to/app/ios/Embedded jvm-ios/package.sh   # default: build/embedded
```

Inside the app repository this repository is the `runtime/` submodule, and `make jvm-ios` does the
above.

## Tests

| Command (in `extension-host/`) | What it runs |
|---|---|
| `./gradlew test` | Unit tests, offline. |
| `./gradlew :host-core:test -Plive --tests '*ExtensionHostLiveTest*'` | End to end against real repos and sites (network). |
| `./gradlew :host-core:test -Pbulk --tests '*BulkScanTest*'` | Installs every extension of a repo and writes a report of what fails to `host-core/build/reports/bulk-scan/`. `-PbulkRepo=<url>`, `-PbulkLimit=N` and `-PbulkApk=true` adjust it. |
| `./gradlew :host-core:test --tests '*ContractFixturesTest*' -PupdateContract=true` | Regenerates `contract/fixtures` from the Kotlin types. |

`jvm-ios/smoke-openj9-ios.sh` boots the static VM, and then the host, in a simulator without the
app; see `jvm-ios/README.md`.

## The contract

`contract/fixtures/*.json` are examples of every RPC request, response and event. The Kotlin tests
check that the host writes exactly these, and the app's Swift tests check that it reads them. The
protocol only grows: fields and calls are added, never changed or removed.
`contract/fixtures/backup/` holds small synthetic Mihon, Oneechan and Tachimanga backups for the
app's backup tests.

## Licence

MPL-2.0 for Oneechan's own code; see `NOTICE` for the projects this builds on and their licences.
