/*
 * Copyright (c) 2019, Projekt Development LLC.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package projekt.commons.theme

import android.os.Build
import android.util.ArrayMap
import android.util.Xml
import androidx.preference.PreferenceManager
import com.topjohnwu.superuser.Shell
import projekt.commons.buildtools.BuildTools.getAapt
import projekt.commons.buildtools.BuildTools.getZipalign
import projekt.commons.theme.ThemeApp.OVERLAY_PERMISSION
import projekt.commons.theme.ThemeApp.SAMSUNG_OVERLAY_PERMISSION
import projekt.commons.theme.internal.*
import projekt.commons.theme.internal.METADATA_INSTALL_TIMESTAMP
import java.io.*

/**
 * A class for building overlays.
 * @param packageName Overlay package name.
 * @param targetPackageName Overlay target package name.
 * @param timestamp install timestamp, [System.currentTimeMillis] can be used for this.
 * @param versionCode Overlay version code.
 * @param versionName Overlay version name.
 * @param label Overlay package label.
 * @param metaData Collections of metadata to be added to the overlay manifest.
 * @param outDir target directory to put the generated overlay.
 */
class OverlayBuilder(
    private val packageName: String,
    private val targetPackageName: String,
    private val timestamp: Long,
    private val versionCode: Int? = null,
    private val versionName: String? = null,
    private val label: String? = null,
    private val metaData: ArrayMap<String, String>? = null,
    private val outDir: File = File(ThemeApplication.instance.externalCacheDir, "overlays")
) {

    private val workDir = File(ThemeApplication.instance.cacheDir, "overlay_builder")

    private var extraBasePackagePath = emptyArray<String>()
    private var resourceDirs = emptyArray<String>()
    private var assetDir: String? = null

    // These packages will be exempt from having the SAMSUNG_OVERLAY_PERMISSION added onto it
    private val needSamsungPermission: Boolean
        get() {
            if (ThemeApp.isSamsung) {
                val standaloneOverlay = listOf(
                        "com.sec.android.app.music",
                        "com.sec.android.app.voicenote"
                )
                return !standaloneOverlay.contains(targetPackageName)
            }
            return false
        }

    /**
     * Adds extra base package (APK) to compile the overlays with. Equivalent
     * with the -I modifiers on AAPT.
     *
     * This method can be called multiple times as multiple base package
     * is supported.
     *
     * @param basePackage Absolute path of the package.
     */
    fun addExtraBasePackage(basePackage: String) {
        extraBasePackagePath += basePackage
    }

    /**
     * Adds directory of resources to be compiled. Equivalent with the -S
     * modifiers on AAPT.
     *
     * This method can be called multiple times as multiple resource
     * directories is supported.
     *
     * @param resDir directory of resources.
     */
    fun addResourceDir(resDir: File) {
        resourceDirs += resDir.absolutePath
    }

    /**
     * Sets directory of asset to be compiled. Equivalent with the -A
     * modifiers on AAPT.
     *
     * AAPT only supports one asset directory so this method will
     * replace the previously set asset directory.
     *
     * @param _assetDir directory of asset.
     */
    fun setAssetDir(_assetDir: File) {
        assetDir = _assetDir.absolutePath
    }

    /**
     * Runs the overlay builder.
     *
     * @return Result of builder in form of [Result].
     * @see Result
     */
    fun exec(): Result {
        workDir.mkdirs()
        generateManifest()
        val result = compileOverlay()
        workDir.deleteRecursively()
        return result
    }

    /**
     * Generate AndroidManifest.xml to workDir root.
     */
    private fun generateManifest() {
        val serializer = Xml.newSerializer()
        val str = serializer.document {
            // Root manifest element
            element("manifest") {
                attribute("xmlns:android", "http://schemas.android.com/apk/res/android")
                attribute("package", packageName)
                versionCode?.let {
                    attribute("android:versionCode", it.toString())
                }
                versionName?.let {
                    attribute("android:versionName", it)
                }

                // Overlay package attributes
                element("overlay") {
                    attribute("android:targetPackage", targetPackageName)
                }

                // Unrooted Samsung (Synergy) Q overlays needs to "target" Q
                if (ThemeApp.isSynergy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    element("uses-sdk") {
                        attribute("android:targetSdkVersion", Build.VERSION.SDK_INT.toString())
                    }
                }

                // Proper permission for Samsung devices to utilize the overlay
                if (needSamsungPermission) {
                    element("uses-permission") {
                        attribute("android:name", SAMSUNG_OVERLAY_PERMISSION)
                    }
                }

                // "permission" for easy overlay listing
                element("uses-permission") {
                    attribute("android:name", OVERLAY_PERMISSION)
                }

                // Application attributes (for metadata)
                element("application") {
                    attribute("android:allowBackup", "false")
                    attribute("android:hasCode", "false")
                    label?.let {
                        attribute("android:label", it)
                    }

                    // Metadata
                    metaData?.forEach { name, value ->
                        element("meta-data") {
                            attribute("android:name", name)
                            attribute("android:value", value)
                        }
                    }

                    // Install timestamp
                    element("meta-data") {
                        attribute("android:name", METADATA_INSTALL_TIMESTAMP)
                        attribute("android:value", timestamp.toString())
                    }
                }
            }
        }

        FileWriter(File(workDir, "AndroidManifest.xml")).use { fw ->
            fw.write(str)
        }
    }

    private fun compileOverlay(): Result {
        val unsigned = File(outDir, "$packageName-unsigned.apk")
        val aligned = File(outDir, "$packageName-unsigned-aligned.apk")
        val signed = File(outDir, "$packageName.apk")

        if (!outDir.isDirectory) {
            if (!outDir.mkdirs()) {
                return Result.Failure("Failed to create overlay cache directory")
            }
        }

        // Compile unsigned APK
        var doLegacyCompile = false
        loop@ do {
            val command = StringBuilder()
            // Make sure this will call AAPT duh
            command.append(getAapt(ThemeApplication.instance).absolutePath).append(" p ")

            // The manifest
            command.append("-M ").append(workDir.absolutePath).append("/AndroidManifest.xml ")

            // Add resource directories
            if (resourceDirs.isEmpty()) {
                return Result.Failure("Resource directory cannot be empty!")
            }
            resourceDirs.forEach { dir ->
                command.append("-S ").append(dir).append(" ")
            }

            // Add asset directory if set
            if (!assetDir.isNullOrEmpty()) {
                command.append("-A ").append(assetDir).append(" ")
            }

            // Compile against framework by default and target package
            // when we're not legacy compiling
            command.append("-I /system/framework/framework-res.apk ")
            if (!doLegacyCompile) {
                extraBasePackagePath.forEach { path ->
                    if (File(path).exists()) {
                        command.append("-I ").append(path).append(" ")
                    }
                }
            }

            // Specify the output dir
            command.append("-F ").append(unsigned.absolutePath).append(" ")
            command.append("--auto-add-overlay ")
            command.append("-f ")
            command.append('\n')

            // Run command and see
            var error = ""
            val process = Runtime.getRuntime().exec(command.toString())
            process.waitFor()
            BufferedReader(InputStreamReader(process.errorStream)).use { err ->
                err.forEachLine { line ->
                    if (line.contains("types not allowed")) {
                        val forceNewCompiler = PreferenceManager
                            .getDefaultSharedPreferences(ThemeApplication.instance)
                            .getBoolean("force_new_compiler", false)
                        if (!doLegacyCompile && !forceNewCompiler) {
                            doLegacyCompile = true
                        } else {
                            // Still failed with legacy compile, throw error
                            error = "$error\n${line}"
                        }
                    } else {
                        // If output exists then compilation is failed
                        error = "$error\n${line}"
                    }
                }
            }
            process.destroy()
            if (doLegacyCompile) {
                break@loop
            }
            if (error.isNotEmpty()) {
                return Result.Failure(error)
            }
        } while (doLegacyCompile)

        // Just to make sure the compile is going fine so far
        if (!unsigned.isFile) {
            return Result.Failure("Failed to compile overlay")
        }

        // Zipalign the compiled overlay
        Shell.sh("${getZipalign(ThemeApplication.instance).absolutePath} 4 " +
                "${unsigned.absolutePath} ${aligned.absolutePath}").exec()
        if (!aligned.isFile) {
            return Result.Failure("Failed to zipalign overlay")
        }

        // Sign the zipaligned overlay
        if (!signApk(ThemeApplication.instance, aligned, signed)) {
            return Result.Failure("Failed to sign overlay")
        }

        // Delete unsigned APK
        unsigned.delete()
        aligned.delete()

        return Result.Success(signed.absolutePath)
    }

    /**
     * This class represents the result of [exec].
     *
     * @see exec
     */
    sealed class Result {
        /**
         * Operation is success
         *
         * @param path Path of generated overlay.
         */
        class Success(val path: String) : Result()

        /**
         * Operation is failed
         *
         * @param message The error message from the operation.
         */
        class Failure(val message: String) : Result()
    }
}