package dev.rubiidev.shellinstaller.backend

import android.content.Context
import dev.rubiidev.shellinstaller.R
import dev.rubiidev.shellinstaller.install.Prefs
import java.io.IOException

object Backends {

    fun isReady(backend: InstallBackend): Boolean = when (backend.id) {
        "shizuku" -> ShizukuBackend.isReady
        "adb" -> AdbBackend.isPaired
        "root" -> RootBackend.isReady
        else -> false
    }

    /** Pick the backend to use, honoring the user's preference and falling back in Auto mode. */
    fun resolve(context: Context): InstallBackend {
        val candidates: List<InstallBackend> = when (Prefs.backendPreference(context)) {
            Prefs.BackendPreference.SHIZUKU -> listOf(ShizukuBackend)
            Prefs.BackendPreference.ADB -> listOf(AdbBackend)
            Prefs.BackendPreference.ROOT -> listOf(RootBackend)
            Prefs.BackendPreference.AUTO -> listOf(ShizukuBackend, AdbBackend, RootBackend)
        }
        for (backend in candidates) {
            if (isReady(backend)) return backend
        }
        throw IOException(context.getString(R.string.err_no_backend))
    }

    fun readyBackendOrNull(context: Context): InstallBackend? = try {
        resolve(context)
    } catch (e: Exception) {
        null
    }
}
