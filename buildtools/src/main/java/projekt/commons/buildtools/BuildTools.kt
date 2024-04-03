/*
 * Copyright (c) 2019, Projekt Development LLC.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package projekt.commons.buildtools

import android.content.Context
import projekt.commons.shell.Shell
import java.io.File

/**
 * Main class for build tools setup
 */
object BuildTools {
    private fun getBinariesDir(context: Context) = File(context.dataDir, "bin")

    /**
     * Returns AAPT object in form of [File]
     */
    fun getAapt(context: Context) = File(getBinariesDir(context), "aapt")

    /**
     * Returns AAPT2 object in form of [File]
     */
    fun getAapt2(context: Context) = File(getBinariesDir(context), "aapt2")

    /**
     * Returns Zipalign object in form of [File]
     */
    fun getZipalign(context: Context) = File(getBinariesDir(context), "zipalign")

    /**
     * Extracts AAPT and Zipalign binaries and updates them if needed
     *
     * @param context Context
     * @return `true` if binary setup is succeeded
     */
    fun setup(context: Context): Boolean {
        with(getBinariesDir(context)) {
            if (!exists()) {
                mkdirs()
            }
        }
        with(getAapt(context)) {
            delete()
            val lib = File(context.applicationInfo.nativeLibraryDir, "libaapt.so")
            Shell.exec("ln -sf $lib $this")
        }
        with(getAapt2(context)) {
            delete()
            val lib = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
            Shell.exec("ln -sf $lib $this")
        }
        with(getZipalign(context)) {
            delete()
            val lib = File(context.applicationInfo.nativeLibraryDir, "libzipalign.so")
            Shell.exec("ln -sf $lib $this")
        }
        return true
    }

}
