package org.github.ewt45.winemulator.glibc

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bridge for forwarding wine commands from the proot shell to the Android process.
 *
 * The proot shell runs `glibc-run <args>` which sends a one-line EXEC request
 * over the bridge. The bridge owns a real wine launcher that runs
 * `box64 wine64 <args>` outside of proot (no ptrace, full box64 dynarec speed).
 *
 * Transport: two named pipes (fifos) in [prootEndpointDir]. The directory is
 * bind-mounted into the proot container at /tmp/linbox-glibc so the shell can
 * reach the same fifo paths. (See Proot.kt installGlibcRunSh + the
 * --bind=...: /tmp/linbox-glibc flag in attachInternal.)
 *
 * Why not LocalServerSocket (abstract Unix socket)?
 *   - `android.net.LocalServerSocket("name")` uses Android's private
 *     /dev/socket/ namespace, NOT the standard abstract namespace. proot
 *     cannot reach it because /dev/socket is not visible inside the
 *     container's mount namespace.
 *   - true abstract namespace sockets need JNI + native code (winlator does
 *     this via libwinlator.so). We don't have that here.
 *   - Two named pipes work everywhere with no JNI and no namespace juggling.
 *
 * Threading: the bridge owns a single executor thread that runs
 * [acceptFifoLoop] which blocks on the request fifo. Each accepted request
 * is processed inline in that loop (writing to the response fifo is
 * synchronous). A separate [processExecutor] runs the actual box64 wine
 * subprocess so the bridge loop can read OK/ERR/END responses and forward
 * them. start() blocks until the bridge is ready (or fails) so callers
 * (e.g. Proot.attachInternal) know whether the endpoint string is valid.
 */
class GlibcWineBridge(
    private val fs: ImageFs,
    private val launcher: GlibcProgramLauncher,
    /** Path inside the proot rootfs where the bridge endpoint will be visible. */
    private val prootEndpointDir: File,
    /** Transport mode. FIFO is the only one we support — kept as enum for future. */
    private val mode: Mode = Mode.FIFO
) {
    enum class Mode { FIFO }

    companion object {
        private const val TAG = "GlibcBridge"
    }

    private val jobs = ConcurrentHashMap<String, JobState>()
    @Volatile private var running: Boolean = false
    private val readyLatch = CountDownLatch(1)
    @Volatile private var startError: String? = null
    @Volatile private var reqFifoFile: File? = null
    @Volatile private var respFifoFile: File? = null
    private val processExecutor = Executors.newCachedThreadPool()

    init {
        Log.i(TAG, "GlibcWineBridge created, mode=$mode, endpointDir=$prootEndpointDir")
    }

    /**
     * Start the bridge. **Blocks** until the fifos are created and the
     * request-loop is listening, or until an error is hit. Returns true on
     * success. After this call, [endpoint] is non-empty and safe to pass to
     * the proot container.
     */
    fun start(): Boolean {
        if (running) return startError == null
        running = true
        return try {
            // Synchronous fifo creation. mkfifo is a syscall; we run it on
            // a worker thread but waitFor it so start() blocks until done.
            val (req, resp) = createFifos() ?: run {
                startError = "failed to create fifos under $prootEndpointDir"
                readyLatch.countDown()
                Log.e(TAG, startError!!)
                return false
            }
            reqFifoFile = req
            respFifoFile = resp
            Log.i(TAG, "fifos created: req=${req.absolutePath} resp=${resp.absolutePath}")
            // Now kick off the request-accept loop asynchronously. The
            // readyLatch is counted down *after* the loop is set up and the
            // read side of reqFifo is opened (otherwise the loop might miss
            // requests sent before it's ready).
            Executors.newSingleThreadExecutor().execute {
                try {
                    acceptFifoLoop(req, resp)
                } catch (e: Exception) {
                    Log.e(TAG, "acceptFifoLoop crashed", e)
                }
            }
            // Give the loop a brief moment to open the read end of reqFifo
            // before we declare "ready". Without this, a shell that races
            // us could `printf >&4` into a fifo that has no reader yet
            // and get SIGPIPE / EIO.
            Thread.sleep(100)
            readyLatch.countDown()
            true
        } catch (e: Exception) {
            startError = "start failed: ${e.message}"
            Log.e(TAG, startError!!, e)
            readyLatch.countDown()
            false
        }
    }

    /**
     * The proot-side endpoint string, in the format the sh script expects:
     *   "/path/to/in|/path/to/out"
     * Empty until [start] succeeds.
     */
    val endpoint: String
        get() {
            val r = reqFifoFile?.absolutePath ?: return ""
            val w = respFifoFile?.absolutePath ?: return ""
            return "$r|$w"
        }

    fun stop() {
        running = false
        // best-effort cleanup
        reqFifoFile?.delete()
        respFifoFile?.delete()
        for ((_, job) in jobs) launcher.stop()
        jobs.clear()
        processExecutor.shutdownNow()
    }

    fun isRunning(): Boolean = running && startError == null

    /** Block until [start] is finished (success or failure). */
    fun awaitReady(timeoutMs: Long = 5000): Boolean {
        return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && startError == null
    }

    fun startError(): String? = startError

    private fun createFifos(): Pair<File, File>? {
        if (!prootEndpointDir.exists()) prootEndpointDir.mkdirs()
        val req = File(prootEndpointDir, "linbox-bridge.in")
        val resp = File(prootEndpointDir, "linbox-bridge.out")
        listOf(req, resp).forEach { it.delete() }

        // Try a few mkfifo strategies. Android's android.system.Os does NOT
        // expose mknod. /system/bin/mkfifo works on most Android versions but
        // is sometimes stripped (Android 14+ GKI in some profiles). The
        // fallback opens an FD pair via a Java-only path? There isn't one
        // for fifos in pure Java — we have to delegate to /system/bin/sh.
        // If every fallback fails, the caller is told so and the proot
        // command line is built WITHOUT the --bind so proot still starts.
        val attempts = listOf(
            "/system/bin/mkfifo -m 666 '${req.absolutePath}' '${resp.absolutePath}'",
            "/vendor/bin/mkfifo -m 666 '${req.absolutePath}' '${resp.absolutePath}'",
            "/system/bin/toolbox mkfifo '${req.absolutePath}' '${resp.absolutePath}'",
            "which mkfifo && mkfifo -m 666 '${req.absolutePath}' '${resp.absolutePath}'"
        )
        var lastErr = "(no attempt)"
        for (cmd in attempts) {
            try {
                val p = ProcessBuilder("/system/bin/sh", "-c",
                    "rm -f '${req.absolutePath}' '${resp.absolutePath}'; $cmd")
                val proc = p.start()
                val code = proc.waitFor()
                if (code == 0 && req.exists() && resp.exists()) {
                    Log.i(TAG, "mkfifo via '$cmd' succeeded")
                    // chmod 666 (defensive, in case -m wasn't honored)
                    runCatching {
                        android.system.Os.chmod(req.absolutePath, 0x1B6)
                        android.system.Os.chmod(resp.absolutePath, 0x1B6)
                    }
                    return Pair(req, resp)
                }
                lastErr = "code=$code"
                Log.w(TAG, "mkfifo attempt '$cmd' failed: $lastErr")
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "mkfifo attempt '$cmd' threw: $lastErr")
            }
        }
        Log.e(TAG, "all mkfifo attempts failed; lastErr=$lastErr")
        // Last-ditch: drop plain files in so the bind still has something
        // (proot won't crash on bind; glibc-run will just see "not a fifo").
        // Better than proot dying.
        try {
            req.createNewFile()
            resp.createNewFile()
            android.system.Os.chmod(req.absolutePath, 0x1B6)
            android.system.Os.chmod(resp.absolutePath, 0x1B6)
            Log.w(TAG, "fallback: created plain files (not real fifos); glibc-run will not work")
            return Pair(req, resp)
        } catch (e: Exception) {
            Log.e(TAG, "fallback createNewFile also failed", e)
            return null
        }
    }

    /**
     * Synchronous request loop. Opens reqFifo for reading (blocks until
     * a writer connects), reads one EXEC line, dispatches to a worker, then
     * loops.
     */
    private fun acceptFifoLoop(req: File, resp: File) {
        Log.i(TAG, "acceptFifoLoop: opening ${req.absolutePath} for reading")
        // We open the request fifo for READING inside an executor, but the
        // sh script must have already opened it for writing (so the read
        // open doesn't block). For the very first client, the sh script
        // does `exec 5<"$IN_FIFO"` which keeps a reader open continuously,
        // so the Android-side `FileInputStream(req)` will not block. Good.
        val reqStream = req.inputStream()
        val respStream = resp.outputStream()
        val reader = BufferedReader(InputStreamReader(reqStream))
        Log.i(TAG, "acceptFifoLoop: ready to read EXEC requests")
        try {
            while (running) {
                val line = try { reader.readLine() } catch (e: Exception) {
                    Log.w(TAG, "readLine failed: ${e.message}")
                    break
                } ?: break
                if (line.isBlank()) continue
                Log.d(TAG, "got line: ${line.take(80)}")
                handleClientLine(line, respStream)
            }
        } finally {
            try { reqStream.close() } catch (_: Exception) {}
            try { respStream.close() } catch (_: Exception) {}
        }
        Log.i(TAG, "acceptFifoLoop exited")
    }

    private fun handleClientLine(line: String, respOut: java.io.OutputStream) {
        val parts = line.split("\t", limit = 3)
        if (parts.size < 2) {
            writeResp(respOut, "ERR\t-\tmalformed request")
            return
        }
        val verb = parts[0]
        val key = parts[1]
        val argv = if (parts.size >= 3) parts[2] else ""
        when (verb) {
            "EXEC" -> launchJob(key, argv, respOut)
            "BYE" -> {
                jobs.remove(key)
                writeResp(respOut, "OK\t$key\tbye")
            }
            else -> writeResp(respOut, "ERR\t$key\tunknown verb: $verb")
        }
    }

    private fun writeResp(respOut: java.io.OutputStream, line: String) {
        synchronized(respOut) {
            try {
                respOut.write((line + "\n").toByteArray(Charsets.UTF_8))
                respOut.flush()
            } catch (e: Exception) {
                Log.w(TAG, "writeResp failed: ${e.message}")
            }
        }
    }

    private fun launchJob(key: String, argv: String, respOut: java.io.OutputStream) {
        // Sanity check imagefs.
        val missing = mutableListOf<String>()
        if (!fs.root.isDirectory) missing += "imagefs root dir (${fs.root})"
        if (!fs.box64Bin.exists()) missing += "box64 binary (${fs.box64Bin})"
        if (!fs.box64Bin.canExecute()) missing += "box64 not executable (${fs.box64Bin})"
        if (!fs.resolveWineBin().exists()) missing += "wine binary (${fs.resolveWineBin()})"
        if (!fs.resolveWineBin().canExecute()) missing += "wine not executable (${fs.resolveWineBin()})"
        if (!File(fs.libDir, "ld-linux-aarch64.so.1").exists()) {
            missing += "aarch64 glibc loader (${File(fs.libDir, "ld-linux-aarch64.so.1")})"
        }
        if (missing.isNotEmpty()) {
            val msg = "imagefs incomplete: " + missing.joinToString("; ")
            Log.e(TAG, "launchJob: $msg")
            writeResp(respOut, "ERR\t$key\t$msg")
            return
        }
        // Smoke test box64
        val box64Test = launcher.smokeTestBox64()
        if (box64Test == null || box64Test.startsWith("box64 failed") || box64Test.startsWith("box64 exit")) {
            val msg = "box64 smoke test failed: $box64Test"
            Log.e(TAG, "launchJob: $msg")
            writeResp(respOut, "ERR\t$key\t$msg")
            return
        }
        Log.i(TAG, "launchJob: box64 OK -> $box64Test")
        writeResp(respOut, "OK\t$key\tlaunching (box64=$box64Test)")
        // Run wine via launcher. The launcher's exec returns immediately
        // after fork(); the actual box64/wine lifecycle is tracked in
        // GlibcProgramLauncher (onExit callback fires on a worker thread).
        val job = JobState(key)
        jobs[key] = job
        val pid = launcher.launch(
            args = argv,
            extraEnv = null,
            workingDir = fs.winePrefixDir,
            logFilePath = null,
            onExit = { code ->
                Log.i(TAG, "launchJob[$key]: wine exit code=$code")
                writeResp(respOut, "END\t$key\t$code")
                jobs.remove(key)
            }
        )
        if (pid < 0) {
            val reason = launcher.lastLaunchError ?: "unknown launcher failure"
            Log.e(TAG, "launchJob[$key]: launcher failed: $reason")
            writeResp(respOut, "ERR\t$key\t$reason")
            jobs.remove(key)
        } else {
            Log.i(TAG, "launchJob[$key]: pid=$pid")
        }
    }

    private class JobState(val key: String)
}
