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
            Shell.enableVerboseLogging = instance.isApplicationDebugable
            val builder = Shell.Builder.create()
                .setTimeout(SHELL_TIMEOUT)
            // TODO: Choose used backend if multiple supported
            backend = if (andromedaSamsungSupported && isSamsung && !isAtleastPie && isAndromeda &&
                    AndromedaClient.initialize(instance)) {
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                AndromedaSamsungBackend()
            } else if (andromedaSupported && !isAtleastPie && isAndromeda && AndromedaClient.initialize(instance)) {
                builder
                    .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                AndromedaBackend()
            } else if (substratumServiceSupported && isSubstratumService) {
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                SubstratumServiceBackend()
            } else if (pieRootSupported && isRooted && isAtleastPie) {
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR)
                PieRootBackend()
            } else if (rootSupported && isRooted && !isAtleastPie) {
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR)
                RootBackend()
            } else if (synergySupported && isSynergyInstalled) {
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                SynergyBackend()
            } else {
                return false
            }
            Shell.setDefaultBuilder(builder)
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
            val builder = Shell.Builder.create().setTimeout(SHELL_TIMEOUT)
            // TODO: Choose used backend if multiple supported
            backend = if (andromedaSamsungSupported && isSamsung && !isAtleastPie && isAndromeda &&
                    AndromedaClient.initialize(instance)) {
                if (instance.checkSelfPermission(ACCESS_PERMISSION) != PERMISSION_GRANTED) {
                    return false
                }
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                AndromedaSamsungBackend()
            } else if (andromedaSupported && !isAtleastPie && isAndromeda &&
                    AndromedaClient.initialize(instance)) {
                if (instance.checkSelfPermission(ACCESS_PERMISSION) != PERMISSION_GRANTED) {
                    return false
                }
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                AndromedaBackend()
            } else if (substratumServiceSupported && isSubstratumService) {
                // Should've check for permissive settings too but we already catch everything so
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                SubstratumServiceBackend()
            } else if (pieRootSupported && isRooted && isAtleastPie) {
                // Root will ask permission if needed
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR)
                PieRootBackend()
            } else if (rootSupported && isRooted  && !isAtleastPie) {
                // Root will ask permission if needed
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR)
                RootBackend()
            } else if (synergySupported && isSynergyInstalled) {
                builder.setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_NON_ROOT_SHELL)
                SynergyBackend()
            } else {
                return false
            }
            return true
        }
    }

    /**
     * @suppress
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}