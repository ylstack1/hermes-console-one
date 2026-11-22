package com.hermesagent.hermes_android.localengine

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * blocking the Flutter UI thread. All MethodChannel responses and callbacks
 * are marshaled to the UI thread via Handler(Looper.getMainLooper()) to
 * avoid @UiThread violations when Dart calls setState.
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val threadCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-engine-${threadCounter.incrementAndGet()}")
    }

    /** Marshals [result.success] to the UI thread. */
    private fun success(result: MethodChannel.Result, value: Any?) {
        mainHandler.post { result.success(value) }
    }

    /** Marshals [result.error] to the UI thread. */
    private fun error(result: MethodChannel.Result, code: String, message: String?, details: Any?) {
        mainHandler.post { result.error(code, message, details) }
    }

    /** Marshals [result.notImplemented] to the UI thread. */
    private fun notImplemented(result: MethodChannel.Result) {
        mainHandler.post { result.notImplemented() }
    }

    /** Sends an onProgress message to Dart on the UI thread. */
    private fun sendProgress(msg: String) {
        mainHandler.post { channel.invokeMethod("onProgress", msg) }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isArm64" -> {
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                success(result, abi.startsWith("arm64"))
            }
            "isProotReady" -> {
                success(result, hermesManager.isProotReady())
            }
            "isPythonInstalled" -> {
                success(result, hermesManager.isPythonInstalled())
            }
            "isHermesInstalled" -> {
                success(result, hermesManager.isHermesInstalled())
            }
            "isHermesRunning" -> {
                success(result, hermesManager.isHermesRunning())
            }
            "installFull" -> {
                executor.submit {
                    try {
                        val ok = hermesManager.installFull { msg ->
                            sendProgress(msg)
                        }
                        success(result, ok)
                    } catch (e: Exception) {
                        Log.e(TAG, "installFull failed", e)
                        error(result, "install_failed", e.message, null)
                    }
                }
            }
            "startHermes" -> {
                executor.submit {
                    try {
                        val ok = hermesManager.startHermes()
                        if (ok) {
                            LocalEngineForegroundService.start(context)
                        }
                        success(result, ok)
                    } catch (e: Exception) {
                        Log.e(TAG, "startHermes failed", e)
                        error(result, "start_failed", e.message, null)
                    }
                }
            }
            "stopHermes" -> {
                executor.submit {
                    hermesManager.stopHermes()
                    LocalEngineForegroundService.stop(context)
                    success(result, null)
                }
            }
            "foregroundStart" -> {
                LocalEngineForegroundService.start(context)
                success(result, null)
            }
            "foregroundStop" -> {
                LocalEngineForegroundService.stop(context)
                success(result, null)
            }
            "recentOutput" -> {
                success(result, hermesManager.getRecentOutput())
            }
            "uninstallRootfs" -> {
                executor.submit {
                    try {
                        val ok = LocalEngineBootstrap.uninstallRootfs(context)
                        success(result, ok)
                    } catch (e: Exception) {
                        error(result, "uninstall_failed", e.message, null)
                    }
                }
            }
            else -> notImplemented(result)
        }
    }
}
