package dev.rubiidev.shellinstaller.backend

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

sealed interface ShizukuStatus {
    data object NotInstalled : ShizukuStatus
    data object NotRunning : ShizukuStatus
    data object NoPermission : ShizukuStatus
    data class Ready(val uid: Int, val version: Int) : ShizukuStatus
}

/**
 * Runs `pm` through Shizuku. We talk to the Shizuku service binder directly via its published AIDL
 * ([IShizukuService.newProcess]) because the high-level `Shizuku.newProcess` helper was removed from
 * the public API. The server still enforces the per-app permission grant.
 */
object ShizukuBackend : InstallBackend {
    override val id: String = "shizuku"
    override val displayName: String = "Shizuku"

    private const val REQUEST_CODE = 4201
    private const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    private val _status = MutableStateFlow<ShizukuStatus>(ShizukuStatus.NotRunning)
    val status: StateFlow<ShizukuStatus> = _status

    private var initialized = false

    val isReady: Boolean get() = _status.value is ShizukuStatus.Ready

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh(app) }
        Shizuku.addBinderDeadListener { refresh(app) }
        Shizuku.addRequestPermissionResultListener { code, _ ->
            if (code == REQUEST_CODE) refresh(app)
        }
        refresh(app)
    }

    fun refresh(context: Context) {
        _status.value = computeStatus(context.applicationContext)
    }

    private fun computeStatus(context: Context): ShizukuStatus = try {
        when {
            !Shizuku.pingBinder() ->
                if (isManagerInstalled(context)) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled

            Shizuku.isPreV11() -> ShizukuStatus.NotRunning

            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ShizukuStatus.NoPermission

            else -> ShizukuStatus.Ready(Shizuku.getUid(), Shizuku.getVersion())
        }
    } catch (t: Throwable) {
        ShizukuStatus.NotRunning
    }

    private fun isManagerInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (_: Throwable) {
        }
    }

    fun openManager(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun exec(
        command: List<String>,
        stdin: File?,
        onProgress: ((Long) -> Unit)?
    ): CommandResult {
        val binder = Shizuku.getBinder() ?: throw IllegalStateException("Shizuku is not running")
        val service = IShizukuService.Stub.asInterface(binder)
        val process = service.newProcess(command.toTypedArray(), null, null)
        return runProcess(
            stdout = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream),
            stderr = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream),
            stdin = ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream),
            waitFor = { process.waitFor() },
            destroy = { process.destroy() },
            file = stdin,
            onProgress = onProgress
        )
    }
}
