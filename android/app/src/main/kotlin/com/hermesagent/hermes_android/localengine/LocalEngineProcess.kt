package com.hermesagent.hermes_android.localengine

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * PRoot process manager. Builds proot command lines for two modes:
 *
 *   - Install mode: for apt-get, dpkg, pip install, git clone. Uses --root-id
 *     (fakeroot) but NOT --sysvipc (dpkg fork+abort issues).
 *   - Gateway mode: for long-running Hermes gateway. Uses --change-id=0:0,
 *     --sysvipc, and fake uname structures.
 *
 * The proot binary comes from jniLibs (libproot.so) extracted by Android to
 * nativeLibraryDir with execute permission, bypassing W^X policy.
 *
 * Environment is cleared before launching proot — Android JVM's LD_PRELOAD,
 * CLASSPATH, etc. would break proot's fork+exec.
 *
 * Ported from hermes-mobile ProcessManager.kt (MIT), adapted for Console.
 */
class LocalEngineProcess(
    private val context: Context,
    private val paths: LocalEngineBootstrap.Paths,
) {

    companion object {
        private const val TAG = "LocalEngineProcess"
        const val FAKE_KERNEL_RELEASE = "6.1.0-android"
        const val FAKE_KERNEL_VERSION =
            "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"
    }

    // Directory layout (matches LocalEngineBootstrap.Paths)
    val rootfsDir: String get() = paths.rootfsDir.absolutePath
    val tmpDir: String get() = paths.tmpDir.absolutePath
    val homeDir: String get() = paths.homeDir.absolutePath
    val configDir: String get() = paths.configDir.absolutePath
    val libDir: String get() = paths.libDir.absolutePath

    /** True if proot binary is available in nativeLibraryDir. */
    fun isProotAvailable(): Boolean {
        return File(paths.nativeLibDir, "libproot.so").exists() &&
            File(paths.nativeLibDir, "libtalloc.so").exists() &&
            File(paths.nativeLibDir, "libandroid-shmem.so").exists()
    }

    /** Build proot command for install-mode commands (apt, pip, git). */
    fun buildInstallCommand(command: String): List<String> {
        return listOf(
            File(paths.nativeLibDir, "libproot.so").absolutePath,
            "-r", rootfsDir,
            "-0",                        // fakeroot (uid=0/gid=0)
            "--link2symlink",
            "-w", "/root",
            "-b", "$homeDir:/root/home",  // bind host home into rootfs
            "-b", "$tmpDir:/tmp",
            "-b", "$configDir/resolv.conf:/etc/resolv.conf",
            "-b", "$configDir/proc_fakes:/proc",
            "-b", "$configDir/sys_fakes:/sys/fs/selinux",
            "/bin/sh", "-c", "env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin $command"
        )
    }

    /** Build proot command for gateway-mode (long-running Hermes). */
    fun buildGatewayCommand(command: String): List<String> {
        return listOf(
            File(paths.nativeLibDir, "libproot.so").absolutePath,
            "-r", rootfsDir,
            "-0",
            "--link2symlink",
            "-w", "/root",
            "--change-id=0:0",
            "--sysvipc",
            "-b", "$homeDir:/root/home",
            "-b", "$tmpDir:/tmp",
            "-b", "$configDir/resolv.conf:/etc/resolv.conf",
            "-b", "$configDir/proc_fakes:/proc",
            "-b", "$configDir/sys_fakes:/sys/fs/selinux",
            "-b", "$libDir:/usr/local/lib",
            "/bin/sh", "-c",
            "env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LD_LIBRARY_PATH=/usr/local/lib $command"
        )
    }

    /** Clean environment for proot (no JVM vars leaking in). */
    fun prootEnv(): Map<String, String> {
        return mapOf(
            "PROOT_TMP_DIR" to tmpDir,
            "TMPDIR" to tmpDir,
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "EXTERNAL_STORAGE" to "/sdcard",
            "SECONDARY_STORAGE" to "/sdcard",
        )
    }

    /**
     * Run a command in proot (install mode), wait for completion, return exit
     * code. Output lines are passed to [onProgress].
     *
     * Must be called from a background thread.
     */
    fun runInProotExitCode(
        command: String,
        timeoutSeconds: Long = 900,
        onProgress: ((String) -> Unit)? = null,
    ): Int {
        val cmd = buildInstallCommand(command)
        val env = prootEnv()
        val pb = ProcessBuilder(*cmd.toTypedArray())
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (l.contains("proot warning") || l.contains("can't sanitize")) continue
            onProgress?.invoke(l)
        }
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return -1
        }
        return process.exitValue()
    }

    /**
     * Start a long-running proot process (gateway mode). Returns the Process;
     * caller is responsible for reading streams and managing lifecycle.
     *
     * Must be called from a background thread.
     */
    fun startProotProcess(command: String): Process {
        val cmd = buildGatewayCommand(command)
        val env = prootEnv()
        val pb = ProcessBuilder(*cmd.toTypedArray())
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)
        return pb.start()
    }

    /** Read remaining output from a process until EOF. */
    fun drainOutput(process: Process, onLine: (String) -> Unit) {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (l.contains("proot warning") || l.contains("can't sanitize")) continue
            onLine(l)
        }
    }

    private fun getArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "arm"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> abi
        }
    }
}
