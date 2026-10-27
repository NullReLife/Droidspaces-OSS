package com.droidspaces.app.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

enum class DroidspacesBackendStatus { Checking, Available, UpdateAvailable, NotInstalled, ModuleMissing }

object DroidspacesChecker {
    private const val DROIDSPACES_BINARY_PATH = Constants.DROIDSPACES_BINARY_PATH
    private const val BUSYBOX_BINARY_PATH = Constants.BUSYBOX_BINARY_PATH
    private const val MAGISK_MODULE_PATH = "/data/adb/modules/droidspaces"
    private const val MODULE_PROP_PATH = "$MAGISK_MODULE_PATH/module.prop"

    /**
     * Check if Magisk module is installed.
     * Returns true if both module directory and module.prop exist.
     *
     * Performance: ~10-20ms (runs in background, never blocks UI)
     */
    private suspend fun checkModuleInstalled(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("test -d '$MAGISK_MODULE_PATH' && test -f '$MODULE_PROP_PATH'").exec().isSuccess
    }

    /**
     * Check if both droidspaces and busybox binaries are available and executable (non-blocking).
     * Returns status without blocking the calling thread.
     *
     * Performance: ~10-50ms (runs in background, never blocks UI)
     */
    suspend fun checkBackendStatus(): DroidspacesBackendStatus = withContext(Dispatchers.IO) {
        try {
            if (!checkModuleInstalled()) {
                return@withContext DroidspacesBackendStatus.ModuleMissing
            }

            fun checkBinary(binaryPath: String) =
                Shell.cmd("test -f '$binaryPath' -a -x '$binaryPath'").exec().isSuccess

            if (checkBinary(DROIDSPACES_BINARY_PATH) && checkBinary(BUSYBOX_BINARY_PATH))
                DroidspacesBackendStatus.Available
            else
                DroidspacesBackendStatus.NotInstalled
        } catch (e: Exception) {
            DroidspacesBackendStatus.NotInstalled
        }
    }

    /**
     * Get droidspaces version by executing 'droidspaces version' command (non-blocking).
     * Returns version string or null if unavailable.
     *
     * Performance: ~10-50ms (runs in background, never blocks UI)
     */
    suspend fun getDroidspacesVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("$DROIDSPACES_BINARY_PATH version 2>&1").exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                // Version command outputs "v2.5.0-beta" - trim whitespace
                result.out[0].trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if backend update is available by comparing installed binary hash with asset binary hash.
     * Returns true if hashes differ (update available), false otherwise.
     *
     * Performance: ~50-200ms (reads both files and calculates hashes)
     */
    suspend fun checkUpdateAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val binaryName = "droidspaces-${DeviceArch.suffix(context)}"

            // Calculate hash of installed binary
            val installedHashResult = Shell.cmd("md5sum $DROIDSPACES_BINARY_PATH 2>&1 | cut -d' ' -f1").exec()
            if (!installedHashResult.isSuccess || installedHashResult.out.isEmpty()) {
                return@withContext false
            }
            val installedHash = installedHashResult.out[0].trim()

            // Calculate hash of asset binary
            val assetManager = context.assets
            val assetHash = try {
                assetManager.open("binaries/$binaryName").use { inputStream ->
                    calculateMD5(inputStream)
                }
            } catch (e: Exception) {
                return@withContext false
            }

            // Compare hashes - if different, update is available
            installedHash != assetHash
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Calculate MD5 hash of an InputStream.
     */
    private fun calculateMD5(inputStream: InputStream): String =
        MessageDigest.getInstance("MD5").digest(inputStream.readBytes()).joinToString("") { "%02x".format(it) }

}

