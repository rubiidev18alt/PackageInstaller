package dev.rubiidev.shellinstaller.util

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.text.format.Formatter

fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

fun Context.formatSize(bytes: Long): String = Formatter.formatFileSize(this, bytes)

private val SAFE_SHELL_ARG = Regex("[A-Za-z0-9_./:=@%+-]+")

/** Quote a single argument for a POSIX shell (used for the root / adb shell paths). */
fun shellQuote(arg: String): String =
    if (arg.isNotEmpty() && SAFE_SHELL_ARG.matches(arg)) arg
    else "'" + arg.replace("'", "'\\''") + "'"
