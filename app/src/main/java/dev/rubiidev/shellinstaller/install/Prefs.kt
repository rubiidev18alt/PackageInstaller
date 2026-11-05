package dev.rubiidev.shellinstaller.install

import android.content.Context

object Prefs {
    private const val NAME = "settings"
    private const val KEY_DOWNGRADE = "allow_downgrade"
    private const val KEY_GRANT = "grant_all"
    private const val KEY_BYPASS = "bypass_low_sdk"
    private const val KEY_BACKEND = "backend_preference"

    enum class BackendPreference { AUTO, SHIZUKU, ADB, ROOT }

    data class Options(
        val allowDowngrade: Boolean,
        val grantAll: Boolean,
        val bypassLowTargetSdk: Boolean
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun options(context: Context): Options = Options(
        allowDowngrade = prefs(context).getBoolean(KEY_DOWNGRADE, false),
        grantAll = prefs(context).getBoolean(KEY_GRANT, false),
        bypassLowTargetSdk = prefs(context).getBoolean(KEY_BYPASS, true)
    )

    fun setDowngrade(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DOWNGRADE, value).apply()

    fun setGrant(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_GRANT, value).apply()

    fun setBypass(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BYPASS, value).apply()

    fun backendPreference(context: Context): BackendPreference {
        val ordinal = prefs(context).getInt(KEY_BACKEND, 0)
        return BackendPreference.entries.getOrElse(ordinal) { BackendPreference.AUTO }
    }

    fun setBackendPreference(context: Context, value: BackendPreference) =
        prefs(context).edit().putInt(KEY_BACKEND, value.ordinal).apply()
}
