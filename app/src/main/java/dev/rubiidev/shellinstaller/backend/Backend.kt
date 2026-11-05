package dev.rubiidev.shellinstaller.backend

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Result of running a privileged command.
 *
 * [exitCode] is null for the legacy ADB shell service, which does not report exit codes; in that
 * case success is inferred from the `pm` output ("Success" / "Failure [...]").
 */
data class CommandResult(val exitCode: Int?, val output: String) {
    val looksSuccessful: Boolean
        get() = when (exitCode) {
            0 -> true
            null -> output.contains("Success")
            else -> false
        }
}

/** A privileged execution channel (Shizuku, on-device ADB, or root). */
interface InstallBackend {
    val id: String
    val displayName: String

    /**
     * Run [command]. If [stdin] is provided its bytes are fed to the command's standard input
     * (used for `pm install-write ... -`). [onProgress] reports bytes consumed.
     */
    fun exec(
        command: List<String>,
        stdin: File? = null,
        onProgress: ((Long) -> Unit)? = null
    ): CommandResult
}

/**
 * Shared process plumbing: drain stdout/stderr on a background thread while optionally streaming
 * [file] into the process's stdin, then wait for the exit code.
 */
internal fun runProcess(
    stdout: InputStream,
    stderr: InputStream,
    stdin: OutputStream?,
    waitFor: () -> Int,
    destroy: () -> Unit,
    file: File?,
    onProgress: ((Long) -> Unit)?
): CommandResult {
    val output = StringBuilder()
    val drain = Thread {
        try {
            stdout.use { output.append(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (_: Throwable) {
        }
        try {
            stderr.use { output.append(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (_: Throwable) {
        }
    }
    drain.start()
    try {
        if (file != null && stdin != null) {
            stdin.use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        onProgress?.invoke(n.toLong())
                    }
                    out.flush()
                }
            }
        }
        val exit = waitFor()
        drain.join(5000)
        return CommandResult(exit, output.toString())
    } finally {
        try {
            destroy()
        } catch (_: Throwable) {
        }
    }
}
