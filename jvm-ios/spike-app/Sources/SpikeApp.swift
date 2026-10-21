import SwiftUI

@main
struct SpikeApp: App {
    init() {
        // Start the embedded JVM once, off the main thread (see jvm_bridge.c).
        // `simctl launch <udid> dev.oneechan.jvmspike --host <port> <data-name>` runs the extension host.
        let args = CommandLine.arguments.dropFirst().joined(separator: "\n")
        spike_start_jvm(Bundle.main.bundlePath, NSTemporaryDirectory(), args)
    }

    var body: some Scene {
        WindowGroup {
            Text("Oneechan JVM spike\nsee the console for output")
                .multilineTextAlignment(.center)
                .padding()
        }
    }
}
