package com.hermesagent.hermes_android.localengine

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and extracts an Ubuntu 24.04 arm64 rootfs into the app's private
 * files directory, producing a complete glibc Linux userspace that PRoot can
 * chroot into. No root, no Termux required.
 *
 * Ported from hermes-mobile (YinGakki/hermes-mobile, MIT) and adapted for
 * Console's package name and structure.
 *
 * Directory layout (host paths):
 *   filesDir/rootfs/ubuntu   <- Ubuntu rootfs (bind --rootfs here)
 *   filesDir/home            <- user home (bind to rootfs /root/home)
 *   filesDir/tmp             <- PROOT_TMP_DIR
 *   filesDir/config          <- resolv.conf + proc_fakes + sys_fakes
 *   filesDir/lib             <- libtalloc.so.2 runtime copy
 *
 * @see LocalEngineProcess
 * @see LocalHermesManager
 */
object LocalEngineBootstrap {

    private const val TAG = "LocalEngineBootstrap"

    // Tar LF_HARDLINK flag byte ('1' = 49). commons-compress TarArchiveEntry
    // does not expose isHardLink(), so we check linkFlag directly.
    private const val LF_HARDLINK: Byte = '1'.code.toByte()

    // Ubuntu 24.04 base rootfs (arm64). Multiple mirrors with fallback.
    private val ROOTFS_URLS = listOf(
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz"
    )

    private const val ROOTFS_TARBALL_NAME = "ubuntu-base-24.04-base-arm64.tar.gz"

    data class Paths(
        val filesDir: File,
        val rootfsDir: File,
        val homeDir: File,
        val tmpDir: File,
        val configDir: File,
        val libDir: File,
        val nativeLibDir: File,
    )

    fun getPaths(context: Context): Paths {
        val filesDir = context.filesDir
        return Paths(
            filesDir = filesDir,
            rootfsDir = File(filesDir, "rootfs/ubuntu"),
            homeDir = File(filesDir, "home"),
            tmpDir = File(filesDir, "tmp"),
            configDir = File(filesDir, "config"),
            libDir = File(filesDir, "lib"),
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }

    /** True if the rootfs has already been extracted and looks complete. */
    fun isBootstrapInstalled(context: Context): Boolean {
        val p = getPaths(context)
        return p.rootfsDir.isDirectory &&
            File(p.rootfsDir, "bin/sh").exists() &&
            File(p.rootfsDir, "usr/bin/python3").exists() ||
            File(p.rootfsDir, "bin/python3").exists()
    }

    /**
     * Downloads the Ubuntu rootfs tarball and extracts it. Progress is reported
     * via [onProgress]. This can take several minutes on slow connections
     * (rootfs ~150 MB compressed).
     *
     * Must be called from a background thread.
     */
    fun installRootfs(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean {
        val p = getPaths(context)
        p.rootfsDir.mkdirs()
        p.homeDir.mkdirs()
        p.tmpDir.mkdirs()
        p.configDir.mkdirs()
        p.libDir.mkdirs()

        val tarball = File(p.filesDir, ROOTFS_TARBALL_NAME)

        // Download if missing or incomplete
        if (!tarball.exists() || tarball.length() < 1024 * 1024) {
            onProgress("Downloading Ubuntu rootfs (~150 MB)…")
            if (!downloadRootfs(tarball, onProgress)) {
                onProgress("ERROR: rootfs download failed")
                return false
            }
        } else {
            onProgress("Rootfs tarball already present, skipping download")
        }

        // Extract
        onProgress("Extracting rootfs…")
        if (!extractTarGz(tarball, p.rootfsDir, onProgress)) {
            onProgress("ERROR: rootfs extraction failed")
            return false
        }

        // Post-extraction setup
        setupProcFakes(p)
        setupSysFakes(p)
        copyLibtalloc(p)

        // Mark ready
        File(p.configDir, ".bootstrap-ready").writeText("ok")

        onProgress("Rootfs ready")
        return true
    }

    private fun downloadRootfs(
        dest: File,
        onProgress: (String) -> Unit
    ): Boolean {
        var lastException: Exception? = null
        for (url in ROOTFS_URLS) {
            lastException = null
            var connection: HttpURLConnection? = null
            var input: java.io.InputStream? = null
            var output: FileOutputStream? = null
            try {
                val urlObj = URL(url)
                connection = urlObj.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true
                connection.requestMethod = "GET"
                connection.connect()

                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    lastException = Exception("HTTP $code for $url")
                    onProgress("Mirror $url returned $code, trying next…")
                    continue
                }

                val contentLength = connection.contentLength
                if (contentLength > 0) {
                    onProgress("Rootfs size: ${contentLength / 1024 / 1024} MB")
                }

                input = BufferedInputStream(connection.inputStream)
                output = FileOutputStream(dest)
                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L
                var lastProgressTime = System.currentTimeMillis()

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime > 5000) {
                        lastProgressTime = now
                        val mb = totalRead / 1024 / 1024
                        val pct = if (contentLength > 0) (totalRead * 100 / contentLength) else 0
                        onProgress("Downloading rootfs: ${mb} MB ($pct%%)…")
                    }
                }
                output.flush()

                onProgress("Download complete: ${totalRead / 1024 / 1024} MB")
                return true
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Download failed from $url: ${e.message}")
                onProgress("Mirror $url failed: ${e.message}, trying next…")
            } finally {
                try { output?.close() } catch (_: Exception) {}
                try { input?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
        Log.e(TAG, "All rootfs mirrors failed", lastException)
        onProgress("ERROR: all download mirrors failed: ${lastException?.message}")
        return false
    }

    private fun extractTarGz(
        tarball: File,
        destDir: File,
        onProgress: (String) -> Unit
    ): Boolean {
        var fis: java.io.FileInputStream? = null
        var bis: BufferedInputStream? = null
        var gzis: GzipCompressorInputStream? = null
        var tais: TarArchiveInputStream? = null
        try {
            fis = java.io.FileInputStream(tarball)
            bis = BufferedInputStream(fis)
            gzis = GzipCompressorInputStream(bis)
            tais = TarArchiveInputStream(gzis)

            // Phase 1: extract directories, files, and hard links (in tar order)
            // Phase 2: extract symbolic links (after targets exist)
            val symlinks = mutableListOf<Pair<TarArchiveEntry, String>>()
            var entryCount = 0L

            while (true) {
                val entry = tais?.nextTarEntry ?: break
                if (entry == null || entry.isDirectory) continue

                val entryName = entry.name
                val destFile = File(destDir, entryName)

                // Security: prevent path traversal outside destDir
                if (!destFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                    Log.w(TAG, "Skipping unsafe tar entry: $entryName")
                    continue
                }

                when {
                    entry.isDirectory -> {
                        destFile.mkdirs()
                    }
                    entry.linkFlag == LF_HARDLINK -> {
                        // Hard link: resolve target within rootfs
                        val linkName = entry.linkName
                        val linkTarget = File(destDir, linkName)
                        if (linkTarget.exists()) {
                            try {
                                Os.link(linkTarget.absolutePath, destFile.absolutePath)
                            } catch (_: Exception) {
                                // Fall back to copying content
                                extractEntryFile(tais, destFile)
                            }
                        } else {
                            extractEntryFile(tais, destFile)
                        }
                    }
                    else -> {
                        destFile.parentFile?.mkdirs()
                        extractEntryFile(tais, destFile)
                    }
                }

                // Preserve entry mode if set
                if (entry.mode != null) {
                    try {
                        destFile.setReadable(true, false)
                        destFile.setWritable(true, false)
                        if ((entry.mode and 0x40) != 0) {
                            destFile.setExecutable(true, false)
                        }
                    } catch (_: Exception) {}
                }

                entryCount++
                if (entryCount % 500L == 0) {
                    onProgress("Extracting rootfs: ${entryCount} entries…")
                }
            }

            // Phase 2: create symbolic links (targets should exist by now)
            for ((entry, linkTarget) in symlinks) {
                val destFile = File(destDir, entry.name)
                destFile.parentFile?.mkdirs()
                try {
                    Os.symlink(linkTarget, destFile.absolutePath)
                } catch (_: Exception) {
                    // Ignore symlink failures
                }
            }

            onProgress("Extraction complete: $entryCount entries")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs extraction failed", e)
            onProgress("ERROR: extraction failed — ${e.message}")
            return false
        } finally {
            try { tais?.close() } catch (_: Exception) {}
            try { gzis?.close() } catch (_: Exception) {}
            try { bis?.close() } catch (_: Exception) {}
            try { fis?.close() } catch (_: Exception) {}
        }
    }

    private fun extractEntryFile(tais: TarArchiveInputStream, destFile: File) {
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(destFile)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = tais.read(buffer)
                if (read == -1) break
                fos.write(buffer, 0, read)
            }
        } finally {
            try { fos?.close() } catch (_: Exception) {}
        }
    }

    private fun setupProcFakes(p: Paths) {
        // Android restricts /proc access inside proot. Write fake /proc files
        // that proot-distro uses to bypass the restriction.
        val procFakes = File(p.configDir, "proc_fakes")
        procFakes.mkdirs()

        // Fake kernel release
        File(procFakes, "kernel_osrelease").writeText("6.1.0-android\n")
        File(procFakes, "sys/kernel/osrelease").writeText("6.1.0-android\n")

        Log.i(TAG, "Proc fakes written to ${procFakes.absolutePath}")
    }

    private fun setupSysFakes(p: Paths) {
        // Empty dir to satisfy SELinux checks inside proot (bind to /sys/fs/selinux)
        val sysFakes = File(p.configDir, "sys_fakes/empty")
        sysFakes.mkdirs()
    }

    /**
     * PRoot links against libtalloc.so.2, but jniLibs only allows *.so names.
     * Copy libtalloc.so -> libtalloc.so.2 into the writable libDir so PRoot's
     * dynamic linker can find it via LD_LIBRARY_PATH.
     */
    fun copyLibtalloc(p: Paths) {
        val src = File(p.nativeLibDir, "libtalloc.so")
        val dst = File(p.libDir, "libtalloc.so.2")
        if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
            try {
                src.copyTo(dst, overwrite = true)
                Log.i(TAG, "Copied libtalloc.so -> libtalloc.so.2")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy libtalloc: ${e.message}")
            }
        }
    }

    /** Remove the rootfs and all associated data. Frees ~500 MB+. */
    fun uninstallRootfs(context: Context): Boolean {
        val p = getPaths(context)
        fun deleteRecursive(fileOrDir: File): Boolean {
            if (!fileOrDir.exists()) return true
            val isSymlink = try {
                Os.readlink(fileOrDir.absolutePath); true
            } catch (_: Exception) { false }
            if (isSymlink) {
                return fileOrDir.delete()
            }
            if (fileOrDir.isDirectory) {
                fileOrDir.listFiles()?.forEach { deleteRecursive(it) } ?: run {}
            }
            return fileOrDir.delete()
        }
        return try {
            deleteRecursive(p.rootfsDir.parentFile)
            deleteRecursive(p.configDir)
            deleteRecursive(p.libDir)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs cleanup failed", e)
            false
        }
    }
}
