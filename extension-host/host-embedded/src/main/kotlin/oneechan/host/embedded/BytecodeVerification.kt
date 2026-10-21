package oneechan.host.embedded

/**
 * Whether the running JVM verifies the bytecode of classes from non-boot class loaders (the
 * extensions). Extensions are R8-optimized for ART and fail the JVM verifier, so the host refuses
 * to start while it is on.
 */
internal object BytecodeVerification {
    /**
     * - HotSpot (Server, Zero): the `BytecodeVerificationRemote` flag, as before.
     * - Eclipse OpenJ9: no such flag (its HotSpotDiagnosticMXBean throws), so its `-Xverify` options.
     * - Any other VM: assumed to verify.
     *
     * @param hotSpotFlag reads `BytecodeVerificationRemote`; throws or returns null when the VM has no such flag.
     */
    fun remoteVerificationEnabled(
        vmName: String,
        inputArguments: List<String>,
        hotSpotFlag: () -> String?,
    ): Boolean {
        runCatching(hotSpotFlag).getOrNull()?.let { return it.toBoolean() }
        if (isOpenJ9(vmName)) return openJ9Verifies(inputArguments)
        return true
    }

    fun isOpenJ9(vmName: String): Boolean = vmName.contains("OpenJ9")

    /**
     * OpenJ9 verifies unless the last `-Xverify[:<options>]` option is `-Xverify:none` (vm/jvminit.c
     * takes the last one; `-noverify` is the launcher's spelling of it).
     */
    fun openJ9Verifies(arguments: List<String>): Boolean {
        val last = arguments.lastOrNull { it == "-Xverify" || it.startsWith("-Xverify:") || it == "-noverify" } ?: return true
        return last != "-Xverify:none" && last != "-noverify"
    }
}
