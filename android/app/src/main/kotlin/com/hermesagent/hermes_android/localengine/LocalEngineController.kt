package com.hermesagent.hermes_android.localengine

import android.content.Context
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Kotlin-side handler for the `hermes/local_engine` MethodChannel.
 *
 * Exposes the optional local Hermes engine (PRoot + Hermes Agent) to Flutter:
 *   - isArm64        → device architecture check
 *   - isProotReady   → proot binaries + rootfs present
 *   - isPythonInstalled
 *   - isHermesInstalled
 *   - isHermesRunning
 *   - installFull    → run the full install pipeline (rootfs → Python → Hermes)
 *   - startHermes    → start the gateway on localhost:9119
 *   - stopHermes     → stop the gateway
 *   - foregroundStart → start the foreground service
 *   - foregroundStop  → stop the foreground service
 *   - recentOutput   → get recent Hermes logs for diagnostics
 *   - uninstallRootfs → remove rootfs and free space
 *
 * All long-running operations are executed on background threads to avoid
 * blocking the Flutter UI thread.
 *
 * @see LocalHermesManager
 * @see LocalEngineForegroundService
 */
class LocalEngineController private constructor(
    private val context: Context,
    private val channel: MethodChannel,
) {

    companion object {
        private const val TAG = "LocalEngineController"
        private const val CHANNEL = "hermes/local_engine"

        @Volatile private var instance: LocalEngineController? = null

        fun attach(context: Context, channel: MethodChannel) {
            instance = LocalEngineController(context, channel)
            channel.setMethodCallHandler { call, result ->
                instance?.handleMethodCall(call, result)
                    ?: result.notImplemented()
            }
        }

        fun detach() {
            instance = null
        }
    }

    private val hermesManager = LocalHermesManager(context)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-engine-${r.id}")
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isArm64" -> {
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                result.success(abi.startsWith("arm64"))
            }
            "isProotReady" -> {
                result.success(hermesManager.isProotReady())
            }
            "isPythonInstalled" -> {
                result.success(hermesManager.isPythonInstalled())
            }
            "isHermesInstalled" -> {
                result.success(hermesManager.isHermesInstalled())
            }
            "isHermesRunning" -> {
                result.success(hermesManager.isHermesRunning())
            }
            "installFull" -> {
                val onProgress = { msg: String ->
                    channel.invokeMethod("onProgress", msg)
                }
                executor.submit {
                    try {
                        val ok = hermesManager.installFull(onProgress)
                        result.success(ok)
                    } catch (e: Exception) {
                        Log.e(TAG, "installFull failed", e)
                        result.error("install_failed", e.message, null)
                    }
                }
            }
            "startHermes" -> {
                executor.submit {
                    try {
                        val ok = hermesManager.startHermes()
                        if (ok) {
                            // Start foreground service to keep it alive
                            LocalEngineForegroundService.start(context)
                        }
                        result.success(ok)
                    } catch (e: Exception) {
                        Log.e(TAG, "startHermes failed", e)
                        result.error("start_failed", e.message, null)
                    }
                }
            }
            "stopHermes" -> {
                executor.submit {
                    hermesManager.stopHermes()
                    LocalEngineForegroundService.stop(context)
                    result.success(null)
                }
            }
            "foregroundStart" -> {
                LocalEngineForegroundService.start(context)
                result.success(null)
            }
            "foregroundStop" -> {
                LocalEngineForegroundService.stop(context)
                result.success(null)
            }
            "recentOutput" -> {
                result.success(hermesManager.getRecentOutput())
            }
            "uninstallRootfs" -> {
                executor.submit {
                    try {
                        val ok = LocalEngineBootstrap.uninstallRootfs(context)
                        result.success(ok)
                    } catch (e: Exception) {
                        result.error("uninstall_failed", e.message, null)
                    }
                }
            }
            else -> result.notImplemented()
        }
    }
}
