package dev.rubiidev.shellinstaller.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import dev.rubiidev.shellinstaller.util.shellQuote
import java.io.File
import java.util.concurrent.TimeUnit

object RootBackend : InstallBackend {
    override val id: String = "root"
    override val displayName: String = "Root (su)"

    sealed interface Status {
        data object Unknown : Status
        data object Available : Status
        data object Unavailable : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Unknown)
    val status: StateFlow<Status> = _status

    val isReady: Boolean get() = _status.value is Status.Available

    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = StringBuilder()
            val drain = Thread {
                try {
                    output.append(process.inputStream.readBytes().toString(Charsets.UTF_8))
                } catch (_: Throwable) {
                }
            }
            drain.start()
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                drain.join(1000)
                false
            } else {
                drain.join(2000)
                process.exitValue() == 0 && output.contains("uid=0")
            }
        } catch (t: Throwable) {
            false
        }
        _status.value = if (ok) Status.Available else Status.Unavailable
        ok
    }

    override fun exec(
        command: List<String>,
        stdin: File?,
        onProgress: ((Long) -> Unit)?
    ): CommandResult {
        val cmd = command.joinToString(" ") { shellQuote(it) }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val result = runProcess(
            stdout = process.inputStream,
            stderr = process.errorStream,
            stdin = process.outputStream,
            waitFor = { process.waitFor() },
            destroy = { process.destroy() },
            file = stdin,
            onProgress = onProgress
        )
        if (result.exitCode == 0) _status.value = Status.Available
        return result
    }
}
