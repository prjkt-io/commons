/*
 * Copyright (c) 2019, Projekt Development LLC.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package projekt.commons.theme

import android.os.Build
import android.util.ArrayMap
import android.util.Xml
import com.topjohnwu.superuser.Shell
import projekt.commons.buildtools.BuildTools.getAapt
import projekt.commons.buildtools.BuildTools.getAapt2
import projekt.commons.buildtools.BuildTools.getZipalign
import projekt.commons.theme.ThemeApp.OVERLAY_PERMISSION
import projekt.commons.theme.ThemeApp.SAMSUNG_OVERLAY_PERMISSION
import projekt.commons.theme.ThemeApp.isAtleastR
import projekt.commons.theme.internal.METADATA_INSTALL_TIMESTAMP
import projekt.commons.theme.internal.attribute
import projekt.commons.theme.internal.document
import projekt.commons.theme.internal.element
import projekt.commons.theme.internal.signApk
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.util.*

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
    private val manifest = File(workDir, "AndroidManifest.xml")

    private var extraBasePackagePath = emptyArray<String>()
    private var resourceDirs = emptyArray<File>()
    private var assetDir: String? = null

    private val unsigned = File(outDir, "$packageName-unsigned.apk")
    private val aligned = File(outDir, "$packageName-unsigned-aligned.apk")
    private val signed = File(outDir, "$packageName.apk")

    private var useAapt2 = false

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
        resourceDirs += resDir
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

    fun setUseAapt2(_useAapt2: Boolean) {
        useAapt2 = _useAapt2
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
        convertResourceIds()
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

        FileWriter(manifest).use { fw ->
            fw.write(str)
        }
    }

    /**
     * Converts any explicit reference ID names inside [resourceDirs]. Will only run on API 30+
     * Example conversion:
     * @*org.mozilla.firefox:color/background_material_dark -> @7F06003C
     */
    private val resourceNameRegex = "@\\*((?:[A-Za-z][A-Za-z\\d_]*\\.)+[A-Za-z][A-Za-z\\d_]*):(anim|color|drawable|layout|menu|string|style|font|bool|dimen|id|integer|array)/([A-Za-z_]+)".toRegex()
    private fun convertResourceIds() {
        if (!isAtleastR) return

        resourceDirs.forEach { dir ->
            dir.walkTopDown().forEach { toCheck ->
                if (toCheck.isFile && toCheck.extension == "xml") {
                    var contentToCheck = toCheck.readText()
                    val replacedStrings = mutableListOf<String>()

                    // Where the magic happens
                    for (matchResult in resourceNameRegex.findAll(contentToCheck)) {
                        if (replacedStrings.contains(matchResult.value)) {
                            // Replaced this before
                            continue
                        }

                        val resourcePackage = matchResult.groups[1]?.value ?: continue
                        val resourceType = matchResult.groups[2]?.value ?: continue
                        val resourceName = matchResult.groups[3]?.value ?: continue
                        val id = ThemeApplication.instance
                                .createPackageContext(resourcePackage, 0).resources
                                .getIdentifier(resourceName, resourceType, resourcePackage)
                        if (id != 0) {
                            val newRef = "resourceId:0x${Integer.toHexString(id).uppercase(Locale.ROOT)}"
                            contentToCheck = contentToCheck.replace(matchResult.value, newRef)
                            replacedStrings += matchResult.value
                        }
                    }

                    if (replacedStrings.isNotEmpty()) {
                        // Overwrite new content to file
                        toCheck.writeText(contentToCheck)
                    }
                }
            }
        }
    }

    private fun compileOverlay(): Result {
        if (!outDir.isDirectory) {
            if (!outDir.mkdirs()) {
                return Result.Failure("Failed to create overlay cache directory")
            }
        }

        if (resourceDirs.isEmpty()) {
            return Result.Failure("Resource directory cannot be empty!")
        }

        // Compile unsigned APK
        val result = if (useAapt2) aapt2CompileUnsignedApk() else aaptCompileUnsignedApk()
        if (result is Result.Failure) {
            return result
        }

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

    private fun aaptCompileUnsignedApk(): Result {
        // Compile unsigned APK
        var doLegacyCompile = false
        loop@ do {
            val command = StringBuilder()
            // Make sure this will call AAPT duh
            command.append(getAapt(ThemeApplication.instance).absolutePath).append(" p ")

            // The manifest
            command.append("-M ").append(manifest.absolutePath).append(" ")

            // Add resource directories
            resourceDirs.forEach { dir ->
                command.append("-S ").append(dir.absolutePath).append(" ")
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
                        if (!doLegacyCompile) {
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
        return Result.Success(unsigned.absolutePath)
    }

    private fun aapt2CompileUnsignedApk(): Result {
        var command: String
        var error: String
        var process: Process

        // Run compile for each resource dir
        val flatPacks = arrayOfNulls<File>(resourceDirs.size)
        resourceDirs
            .reversed() // AAPT2 resource priority list is the opposite of AAPT
            .forEachIndexed { i, dir ->
                flatPacks[i] = File(workDir, "${dir.absolutePath.split("/").last()}.zip")
                command = StringBuilder().apply {
                    append("${getAapt2(ThemeApplication.instance).absolutePath} compile ")

                    // Add resource directories
                    append("--dir $dir ")

                    // Output
                    append("-o ${flatPacks[i]}")
                }.toString()
                error = ""
                process = Runtime.getRuntime().exec(command)
                process.waitFor()
                BufferedReader(InputStreamReader(process.errorStream)).use { err ->
                    err.forEachLine { line ->
                        // If output exists then compilation is failed
                        error = "$error\n${line}"
                    }
                }
                process.destroy()
                if (error.isNotEmpty()) {
                    return Result.Failure("compile error:\n$error")
                }
            }

        // Run link
        var doLegacyCompile = false
        loop@ do {
            command = StringBuilder().apply {
                append("${getAapt2(ThemeApplication.instance)} link ")

                // Add manifest
                append("--manifest ${manifest.absolutePath} ")

                // Compile against framework by default and target package
                append("-I /system/framework/framework-res.apk ")
                if (!doLegacyCompile) {
                    extraBasePackagePath.forEach { path ->
                        if (File(path).exists()) {
                            append("-I $path ")
                        }
                    }
                }

                // Add asset directory if set
                if (!assetDir.isNullOrEmpty()) {
                    append("-A $assetDir ")
                }

                // Add flat packs
                flatPacks.forEach {
                    append("-R $it ")
                }

                // Output
                append("--auto-add-overlay ")
                append("--no-resource-removal ")
                append("-o $unsigned")
            }.toString()
            error = ""
            process = Runtime.getRuntime().exec(command)
            process.waitFor()
            BufferedReader(InputStreamReader(process.errorStream)).use { err ->
                err.forEachLine { line ->
                    if (line.contains("types not allowed")) {
                        if (!doLegacyCompile) {
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
                return Result.Failure("link error:\n$error")
            }
        } while (doLegacyCompile)
        return Result.Success(unsigned.absolutePath)
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