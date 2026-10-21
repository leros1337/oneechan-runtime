/** JVM flags every extension-host process (tests, desktop harness, embedded iOS) must use. */
object JvmFlags {
    val extensionHost =
        listOf(
            // Run extension bytecode like ART: skip the JVM verifier for non-boot classes
            // (a diagnostic flag since JDK 22-era HotSpot, so it must be unlocked first).
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-BytecodeVerificationRemote",
        )
}
