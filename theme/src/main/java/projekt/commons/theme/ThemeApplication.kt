/*
 * Copyright (c) 2019, Projekt Development LLC.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package projekt.commons.theme

import android.app.Application
import android.content.pm.PackageManager.PERMISSION_GRANTED
import com.topjohnwu.superuser.Shell
import projekt.andromeda.client.AndromedaClient
import projekt.andromeda.client.AndromedaClient.ACCESS_PERMISSION
import projekt.commons.theme.ThemeApp.isAtleastPie
import projekt.substratum.platform.SubstratumServiceBridge
import projekt.commons.theme.ThemeApp.isSamsung
import projekt.commons.theme.ThemeApp.isSynergyInstalled
import projekt.commons.theme.backend.AndromedaBackend
import projekt.commons.theme.backend.AndromedaSamsungBackend
import projekt.commons.theme.backend.Backend
import projekt.commons.theme.backend.PieRootBackend
import projekt.commons.theme.backend.RootBackend
import projekt.commons.theme.backend.SynergyBackend
import projekt.commons.theme.backend.SubstratumServiceBackend
import projekt.commons.theme.internal.isApplicationDebugable
import java.io.File

/**
 * Base [Application] class for theme app.
 */
open class ThemeApplication : Application() {

    companion object {
        private const val SHELL_TIMEOUT = 10L

        internal lateinit var instance: ThemeApplication
            private set
        internal var backend: Backend? = null

        private val isSubstratumService: Boolean by lazy { SubstratumServiceBridge.get() != null }
        private val isAndromeda: Boolean by lazy {
            AndromedaClient.doesServerExist(instance)
        }
        private val isRooted: Boolean
            get() {
                val path = System.getenv("PATH")
                if (!path.isNullOrEmpty()) {
                    path.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray().forEach { dir ->
                        if (File(dir, "su").canExecute()) {
                            return true
                        }
                    }
                }
                return false
            }

        internal fun initBackend(
            andromedaSamsungSupported: Boolean = false,
            synergySupported: Boolean = false,
            andromedaSupported: Boolean = false,
            substratumServiceSupported: Boolean = false,
            pieRootSupported: Boolean = false,
            rootSupported: Boolean = false
        ): Boolean {
            if (backend != null) return true

            Shell.enableVerboseLogging = instance.isApplicationDebugable
            // TODO: Choose used backend if multiple supported
            backend = if (andromedaSamsungSupported && isSamsung && !isAtleastPie && isAndromeda &&
                    AndromedaClient.initialize(instance)) {
                AndromedaSamsungBackend()
            } else if (andromedaSupported && !isAtleastPie && isAndromeda && AndromedaClient.initialize(instance)) {
                AndromedaBackend()
            } else if (substratumServiceSupported && isSubstratumService) {
                SubstratumServiceBackend()
            } else if (pieRootSupported && isRooted && isAtleastPie) {
                PieRootBackend()
            } else if (rootSupported && isRooted && !isAtleastPie) {
                RootBackend()
            } else if (synergySupported && isSynergyInstalled) {
                SynergyBackend()
            } else {
                return false
            }
            return true
        }

        /**
         * Silently init backend duh.
         *
         * This method should find for any compatible backend system like initBackend is,
         * but this method also check for any extra runtime permission if needed.
         */
        internal fun silentInitBackend(
            andromedaSamsungSupported: Boolean = false,
            synergySupported: Boolean = false,
            andromedaSupported: Boolean = false,
            substratumServiceSupported: Boolean = false,
            pieRootSupported: Boolean = false,
            rootSupported: Boolean = false
        ): Boolean {
            Shell.enableVerboseLogging = instance.isApplicationDebugable
            // TODO: Choose used backend if multiple supported
            backend = if (andromedaSamsungSupported && isSamsung && !isAtleastPie && isAndromeda &&
                    AndromedaClient.initialize(instance)) {
                if (instance.checkSelfPermission(ACCESS_PERMISSION) != PERMISSION_GRANTED) {
                    return false
                }
                AndromedaSamsungBackend()
            } else if (andromedaSupported && !isAtleastPie && isAndromeda &&
                    AndromedaClient.initialize(instance)) {
                if (instance.checkSelfPermission(ACCESS_PERMISSION) != PERMISSION_GRANTED) {
                    return false
                }
                AndromedaBackend()
            } else if (substratumServiceSupported && isSubstratumService) {
                // Should've check for permissive settings too but we already catch everything so
                SubstratumServiceBackend()
            } else if (pieRootSupported && isRooted && isAtleastPie) {
                // Root will ask permission if needed
                PieRootBackend()
            } else if (rootSupported && isRooted  && !isAtleastPie) {
                // Root will ask permission if needed
                RootBackend()
            } else if (synergySupported && isSynergyInstalled) {
                SynergyBackend()
            } else {
                return false
            }
            return true
        }

        private fun initShell() {
            if (Shell.getCachedShell() == null) {
                val builder = Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(SHELL_TIMEOUT)
                Shell.setDefaultBuilder(builder)
            }
        }
    }

    /**
     * @suppress
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
        initShell()
    }
}