package com.hermesagent.hermes_android.localengine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Installs and manages the Hermes Agent runtime inside the PRoot container.
 *
 * Lifecycle:
 *   1. ensureProot() — proot .so files present in jniLibs
 *   2. installRootfs() — Ubuntu rootfs downloaded + extracted (LocalEngineBootstrap)
 *   3. installPython() — apt-get install python3, pip, venv, build tools
 *   4. installHermes() — git clone hermes-agent, venv, pip install -e '.[termux]'
 *   5. startHermes() — `hermes gateway --port 9119` long-running process
 *   6. stopHermes() — destroy the gateway process
 *
 * All proot commands run on background threads. Progress is reported via
 * callbacks so the Flutter side can show an install UI.
 *
 * Ported from hermes-mobile HermesServerManager.kt (MIT), adapted for Console.
 *
 * @see LocalEngineBootstrap
 * @see LocalEngineProcess
 */
class LocalHermesManager(private val context: Context) {

    companion object {
        private const val TAG = "LocalHermesManager"
        const val HERMES_PORT = 9119
        const val HERMES_REPO = "https://github.com/NousResearch/hermes-agent.git"
        const val HERMES_GATEWAY_CMD =
            "hermes gateway --port $HERMES_PORT --host 127.0.0.1"
    }

    private val paths: LocalEngineBootstrap.Paths by lazy {
        LocalEngineBootstrap.getPaths(context)
    }
    private val bootstrap = LocalEngineBootstrap
    private val processManager: LocalEngineProcess by lazy {
        LocalEngineProcess(context, paths)
    }

    private var hermesProcess: Process? = null
    @Volatile private var hermesOutputLines = java.util.ArrayDeque<String>(200)

    /** True if proot binaries and rootfs are installed. */
    fun isProotReady(): Boolean {
        return processManager.isProotAvailable() &&
            bootstrap.isBootstrapInstalled(context)
    }

    /** True if Python is installed inside the rootfs. */
    fun isPythonInstalled(): Boolean {
        if (!isProotReady()) return false
        val code = processManager.runInProotExitCode(
            "python3 --version 2>&1", 30
        ) { /* ignore output */ }
        return code == 0
    }

    /** True if Hermes Agent is installed (venv exists + hermes CLI present). */
    fun isHermesInstalled(): Boolean {
        if (!isPythonInstalled()) return false
        val repoDir = File(paths.homeDir, "hermes-agent")
        val venvActivate = File(repoDir, ".venv/bin/activate")
        return repoDir.isDirectory &&
            File(repoDir, "pyproject.toml").exists() &&
            venvActivate.exists()
    }

    /** Full install pipeline: rootfs → Python → Hermes Agent. */
    fun installFull(onProgress: (String) -> Unit): Boolean {
        return try {
            // Step 0: proot binaries
            if (!processManager.isProotAvailable()) {
                onProgress("ERROR: PRoot binaries missing. Run fetch-proot-binaries.sh at build time.")
                return false
            }
            onProgress("PRoot binaries OK")

            // Step 1: rootfs
            onProgress("Step 1/4: Downloading Ubuntu rootfs…")
            if (!bootstrap.installRootfs(context, onProgress)) {
                return false
            }

            // Step 2: Python
            onProgress("Step 2/4: Installing Python inside rootfs…")
            if (!installPython(onProgress)) {
                return false
            }

            // Step 3: Hermes Agent
            onProgress("Step 3/4: Installing Hermes Agent…")
            if (!installHermes(onProgress)) {
                return false
            }

            onProgress("✓ Local Hermes engine ready")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "installFull failed", e)
            onProgress("ERROR: ${e.message}")
            return false
        }
    }

    private fun installPython(onProgress: (String) -> Unit): Boolean {
        if (isPythonInstalled()) {
            onProgress("Python already installed, skipping")
            return true
        }

        return runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000,
            onProgress = onProgress,
            what = "apt-get install python3",
        ) {
            onProgress("apt-get update inside rootfs…")
            val updateCode = processManager.runInProotExitCode(
                "apt-get update 2>&1 | tail -5", 600
            ) { onProgress(it) }
            if (updateCode != 0) {
                onProgress("WARNING: apt-get update had issues, continuing anyway")
            }

            onProgress("Installing python3, pip, venv, build tools…")
            val installCode = processManager.runInProotExitCode(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                    "python3 python3-pip python3-venv " +
                    "build-essential git make pkg-config " +
                    "libffi-dev libssl-dev libsqlite3-dev zlib1g-dev " +
                    "ripgrep 2>&1 | tail -20",
                1800
            ) { onProgress(it) }
            if (installCode != 0) {
                throw Exception("apt-get install exited $installCode")
            }
            true
        }
    }

    private fun installHermes(
        onProgress: (String) -> Unit,
    ): Boolean {
        val homeDir = paths.homeDir
        val repoDir = File(homeDir, "hermes-agent")

        // Prepare directories
        File(homeDir, ".hermes").mkdirs()

        // Git clone (with mirror fallback)
        if (!File(repoDir, "pyproject.toml").exists()) {
            onProgress("Cloning Hermes Agent repository…")
            if (!cloneHermesRepo(onProgress)) {
                onProgress("ERROR: hermes-agent clone failed")
                return false
            }
        } else if (File(repoDir, ".git").isDirectory) {
            onProgress("Hermes Agent already exists, git pull…")
            processManager.runInProotExitCode(
                "cd /root/home/hermes-agent && git pull --ff-only 2>&1", 120
            ) { onProgress(it) }
        }

        // Python venv (works normally inside Ubuntu rootfs — no _sysconfigdata bug)
        val venvActivate = File(repoDir, ".venv/bin/activate")
        if (!venvActivate.exists()) {
            onProgress("Creating Python venv…")
            val venvCode = processManager.runInProotExitCode(
                "cd /root/home/hermes-agent && python3 -m venv .venv 2>&1",
                300
            ) { onProgress(it) }
            if (venvCode != 0 || !venvActivate.exists()) {
                onProgress("ERROR: venv creation failed")
                return false
            }
        } else {
            onProgress("venv already exists, reusing")
        }

        // pip install -e '.[termux]'
        return runWithRetry(
            maxAttempts = 3,
            baseDelayMs = 3000,
            onProgress = onProgress,
            what = "pip install hermes-agent",
        ) {
            onProgress("pip install -e '.[termux]' (may take several minutes)…")
            val cmd = """
                cd /root/home/hermes-agent &&
                . .venv/bin/activate &&
                pip install --progress-bar off --retries 3 --timeout 120 \
                    -i https://pypi.tuna.tsinghua.edu.cn/simple \
                    --trusted-host pypi.tuna.tsinghua.edu.cn \
                    -e '.[termux]' 2>&1
            """.trimIndent()
            val pipCode = processManager.runInProotExitCode(cmd, 1200) { onProgress(it) }
            if (pipCode != 0) {
                onProgress("ERROR: pip install failed (exit=$pipCode)")
                throw Exception("pip install exited $pipCode")
            }
            onProgress("✓ Hermes Agent installed")
            true
        }
    }

    private fun cloneHermesRepo(onProgress: (String) -> Unit): Boolean {
        val homeDir = paths.homeDir
        val repoDir = File(homeDir, "hermes-agent")
        homeDir.mkdirs()

        val mirrors = listOf(
            HERMES_REPO,
            "https://github.com/NousResearch/hermes-agent.git"
        )

        for (mirror in mirrors) {
            onProgress("Cloning from $mirror…")
            val code = processManager.runInProotExitCode(
                "git clone --depth 1 $mirror /root/home/hermes-agent 2>&1", 300
            ) { onProgress(it) }
            if (code == 0 && File(repoDir, "pyproject.toml").exists()) {
                onProgress("✓ Hermes Agent cloned")
                return true
            }
            onProgress("Clone from $mirror failed, trying next mirror…")
        }
        return false
    }

    /**
     * Start the Hermes gateway as a long-running proot process.
     * Returns true if the process started successfully.
     *
     * Must be called from a background thread.
     */
    fun startHermes(): Boolean {
        if (isHermesRunning()) {
            Log.w(TAG, "Hermes already running")
            return true
        }
        if (!isHermesInstalled()) {
            Log.e(TAG, "Hermes not installed yet")
            return false
        }

        try {
            val cmd = """
                cd /root/home/hermes-agent &&
                . .venv/bin/activate &&
                hermes gateway --port $HERMES_PORT --host 127.0.0.1 2>&1
            """.trimIndent()

            Log.i(TAG, "Starting Hermes gateway on port $HERMES_PORT")
            hermesProcess = processManager.startProotProcess(cmd)

            // Start output drain thread
            Thread({
                processManager.drainOutput(hermesProcess!!) { line ->
                    recordOutput(line)
                    Log.d(TAG, "hermes: $line")
                }
            }, "hermes-output-drain").start()

            // Give it a moment to start, then check if still alive
            Thread.sleep(3000)
            if (hermesProcess!! != null && hermesProcess!!.isAlive) {
                Log.i(TAG, "Hermes gateway started successfully")
                return true
            } else {
                Log.e(TAG, "Hermes gateway exited immediately")
                hermesProcess = null
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Hermes", e)
            hermesProcess = null
            return false
        }
    }

    /** True if the Hermes gateway process is currently alive. */
    fun isHermesRunning(): Boolean {
        return hermesProcess?.let {
            try {
                it.exitValue()
                false // process has exited
            } catch (_: IllegalThreadStateException) {
                true // still running
            }
        } ?: false
    }

    /** Stop the Hermes gateway process. */
    fun stopHermes() {
        hermesProcess?.destroyForcibly()
        hermesProcess = null
        Log.i(TAG, "Hermes gateway stopped")
    }

    /** Recent Hermes output (for diagnostics when startup fails). */
    fun getRecentOutput(): String =
        synchronized(hermesOutputLines) {
            hermesOutputLines.joinToString("\n")
        }

    private fun recordOutput(line: String) {
        synchronized(hermesOutputLines) {
            if (hermesOutputLines.size >= 200) {
                hermesOutputLines.pollFirst()
            }
            hermesOutputLines.addLast(line)
        }
    }

    /**
     * Run a retry wrapper for network operations (apt, pip, git).
     */
    private fun <T> runWithRetry(
        maxAttempts: Int = 3,
        baseDelayMs: Long = 3000,
        onProgress: ((String) -> Unit)? = null,
        what: String = "operation",
        block: () -> T,
    ): T {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "$what attempt ${attempt + 1}/$maxAttempts failed: ${e.message}")
                onProgress?.invoke("$what 第 ${attempt + 1} 次失败: ${e.message}")
                if (attempt < maxAttempts - 1) {
                    val delay = baseDelayMs * (attempt + 1)
                    onProgress?.invoke("Retrying in ${delay / 1000}s…")
                    Thread.sleep(delay)
                }
            }
        }
        throw lastError ?: RuntimeException("$what failed after $maxAttempts attempts")
    }
}
