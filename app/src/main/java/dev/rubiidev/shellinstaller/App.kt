package dev.rubiidev.shellinstaller

import android.app.Application
import com.google.android.material.color.DynamicColors
import dev.rubiidev.shellinstaller.backend.AdbBackend
import dev.rubiidev.shellinstaller.backend.ShizukuBackend

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        ShizukuBackend.init(this)
        AdbBackend.init(this)
    }
}
