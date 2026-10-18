package com.sqftware.safegit.about

import android.content.Context

/** This build's version name and code, as the About screen and a bug report show it. */
fun Context.appVersion(): String {
    val info = packageManager.getPackageInfo(packageName, 0)
    return "${info.versionName} (${info.longVersionCode})"
}
