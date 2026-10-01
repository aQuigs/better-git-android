package com.sqftware.safegit.git

import androidx.test.platform.app.InstrumentationRegistry

/** The app under test and its bundled git, installed once per test process since installing rewrites its links. */
object TestDevice {
    val context = InstrumentationRegistry.getInstrumentation().targetContext!!
    val git by lazy { BundledGit(context).install() }
}
