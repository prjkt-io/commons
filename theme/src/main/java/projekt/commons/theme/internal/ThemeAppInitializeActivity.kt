/*
 * Copyright (c) 2019, Projekt Development LLC.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package projekt.commons.theme.internal

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.topjohnwu.superuser.Shell
import projekt.andromeda.client.AndromedaClient
import projekt.commons.buildtools.BuildTools
import projekt.commons.theme.ThemeApp.EXTRA_RESULT_CODE
import projekt.commons.theme.ThemeApp.EXTRA_WITH_ANDROMEDA
import projekt.commons.theme.ThemeApp.EXTRA_WITH_ANDROMEDA_SAMSUNG
import projekt.commons.theme.ThemeApp.EXTRA_WITH_PIE_ROOT
import projekt.commons.theme.ThemeApp.EXTRA_WITH_ROOT
import projekt.commons.theme.ThemeApp.EXTRA_WITH_SYNERGY
import projekt.commons.theme.ThemeApp.EXTRA_WITH_SUBSTRATUM_SERVICE
import projekt.commons.theme.ThemeApp.RESULT_ANDROMEDA_DENIED
import projekt.commons.theme.ThemeApp.RESULT_ANDROMEDA_INACTIVE
import projekt.commons.theme.ThemeApp.RESULT_MAGISK_DISABLED
import projekt.commons.theme.ThemeApp.RESULT_NO_SUPPORTED_BACKEND
import projekt.commons.theme.ThemeApp.RESULT_PASS
import projekt.commons.theme.ThemeApp.RESULT_ROOT_DENIED
import projekt.commons.theme.ThemeApp.RESULT_ROOT_NOT_SUPPORTED
import projekt.commons.theme.ThemeApp.RESULT_SUBSTRATUM_SERVICE_DENIED
import projekt.commons.theme.ThemeApplication
import projekt.commons.theme.backend.AndromedaBackend
import projekt.commons.theme.backend.AndromedaSamsungBackend
import projekt.commons.theme.backend.PieRootBackend
import projekt.commons.theme.backend.RootBackend
import projekt.commons.theme.backend.SynergyBackend
import projekt.commons.theme.backend.SubstratumServiceBackend

internal class ThemeAppInitializeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Try to extract binaries
        BuildTools.setup(this)

        // More preparation to use the backend
        val andromedaSamsungSupported = intent.getBooleanExtra(EXTRA_WITH_ANDROMEDA_SAMSUNG, false)
        val synergySupported = intent.getBooleanExtra(EXTRA_WITH_SYNERGY, false)
        val andromedaSupported = intent.getBooleanExtra(EXTRA_WITH_ANDROMEDA, false)
        val substratumServiceSupported = intent.getBooleanExtra(EXTRA_WITH_SUBSTRATUM_SERVICE, false)
        val pieRootSupported = intent.getBooleanExtra(EXTRA_WITH_PIE_ROOT, false)
        val rootSupported = intent.getBooleanExtra(EXTRA_WITH_ROOT, false)
        if (ThemeApplication.initBackend(andromedaSamsungSupported, synergySupported, andromedaSupported,
                        substratumServiceSupported, pieRootSupported, rootSupported)) {
            when (val backend = ThemeApplication.backend) {
                is AndromedaSamsungBackend -> {
                    Log.d(TAG, "Prepare for using Sungstromeda backend")
                    requestPermissions(
                            arrayOf(AndromedaClient.ACCESS_PERMISSION),
                            ANDROMEDA_REQUEST_CODE_PERMISSION
                    )
                    return
                }
                is SynergyBackend -> {
                    finishPassed()
                }
                is AndromedaBackend -> {
                    Log.d(TAG, "Prepare for using Andromeda backend")
                    requestPermissions(
                            arrayOf(AndromedaClient.ACCESS_PERMISSION),
                            ANDROMEDA_REQUEST_CODE_PERMISSION
                    )
                    return
                }
                is SubstratumServiceBackend -> {
                    Log.d(TAG, "Prepare for using Substratum Service backend")
                    // TODO: Proper check
                    if (Settings.Secure.getString(contentResolver, SUBS_SERVICE_PERM_PREF) != "1") {
                        finishWithIntentResult(RESULT_SUBSTRATUM_SERVICE_DENIED)
                    }
                    finishPassed()
                }
                is RootBackend -> {
                    Log.d(TAG, "Prepare for using Root backend")
                    // Request and check root permission
                    // Close cached shell to refresh root access status
                    Shell.getShell().close()
                    if (Shell.rootAccess()) {
                        finishPassed()
                    } else {
                        finishWithIntentResult(RESULT_ROOT_DENIED)
                    }
                }
                is PieRootBackend -> {
                    Log.d(TAG, "Prepare for using Android P backend")
                    // Request and check root permission
                    if (backend.checkMagisk() || backend.checkKsu() || backend.checkApatch()) {
                        // Close cached shell to refresh root access status
                        Shell.getShell().close()
                        if (Shell.rootAccess()) {
                            if (backend.magiskModuleDisabled) {
                                finishWithIntentResult(RESULT_MAGISK_DISABLED)
                            }
                            // TODO: This doesn't seem right but for now it is
                            backend.installMagiskModule()
                            finishPassed()
                        } else {
                            finishWithIntentResult(RESULT_ROOT_DENIED)
                        }
                    } else {
                        finishWithIntentResult(RESULT_ROOT_NOT_SUPPORTED)
                    }
                }
                else -> finishNotSupported()
            }
        } else {
            finishNotSupported()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            ANDROMEDA_REQUEST_CODE_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED) {
                    if (AndromedaClient.isServerActive) {
                        finishPassed()
                    } else {
                        finishWithIntentResult(RESULT_ANDROMEDA_INACTIVE)
                    }
                } else {
                    // halt launch
                    finishWithIntentResult(RESULT_ANDROMEDA_DENIED)
                }
            }
        }
    }

    private fun finishWithIntentResult(resultCode: Int) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_CODE, resultCode))
        finish()
    }

    private fun finishPassed() {
        finishWithIntentResult(RESULT_PASS)
    }

    private fun finishNotSupported() {
        finishWithIntentResult(RESULT_NO_SUPPORTED_BACKEND)
    }

    companion object {
        private const val TAG = "ThemeAppInitializeActivity"
        private const val ANDROMEDA_REQUEST_CODE_PERMISSION = 1023
        private const val SUBS_SERVICE_PERM_PREF = "force_authorize_substratum_packages"
    }
}