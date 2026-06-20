package org.github.ewt45.winemulator.glibc

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The "fifo / ssh bridge" that lets the proot shell invoke box64+wine running
 * in the Android process.
 *
 * Wire protocol (line-based over either a UNIX socket or two named pipes):
 *
 *   request  (proot -> android) :  "EXEC\t<key>\t<argv...>"
 *                                  "ENV \t<key>\t<K=V>"
 *                                  "BYE \t<key>"
 *   response (android -> proot):  "OK  \t<key>\t<pid>"
 *                                  "OUT \t<key>\t<line>"
 *                                  "END \t<key>\t<exitCode>"
 *                                  "ERR \t<key>\t<message>"
 *
 * The proot side gets a small sh script (`/usr/local/bin/glibc-run`) that:
 *   1. opens the socket/fifo
 *   2. sends EXEC + args
 *   3. reads OUT lines until END, prints them to stdout
 *   4. exits with the wine exit code
 *
 * That way users can type `glibc-run /path/to/game.exe` in their proot
 * shell and it Just Works, but the actual wine process is the Android one
 * (no ptrace, full box64 dynarec speed).
 */
class GlibcWineBridge(
    private val fs: ImageFs,
    private val launcher: GlibcProgramLauncher,
    /** Path inside the proot rootfs where the bridge endpoint will be visible. */
    private val prootEndpointDir: File,
    /** Inside-proot user name we expose to bridge clients. */
    private val prootUser: String = "root",
    /** Endpoint type. AUTO = prefer unix-socket, fall back to fifo. */
    private val mode: Mode = Mode.AUTO
) {
    enum class Mode { AUTO, UNIX_SOCKET, FIFO }

    private val TAG = "GlibcBridge"
    @Volatile private var server: android.net.LocalServerSocket? = null
    @Volatile private var running: Boolean = false
    private val jobs = ConcurrentHashMap<String, JobState>()

    /** Starts the bridge. Idempotent. */
    fun start() {
        if (running) return
        running = true
        prootEndpointDir.mkdirs()
        when (mode) {
            Mode.UNIX_SOCKET, Mode.AUTO -> tryStartUnixSocket()
            Mode.FIFO -> startFifoLoop()
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        for ((_, job) in jobs) {
            launcher.stop()
            job.cancel()
        }
        jobs.clear()
        // best-effort cleanup of the unix socket file
        File(prootEndpointDir, "linbox-bridge.sock").takeIf { it.exists() }?.delete()
    }

    fun isRunning(): Boolean = running

    /**
     * Path string the proot sh script should use to talk to the bridge.
     * Resolved at start() time so we can return either a socket path or a
     * fifo path.
     */
    @Volatile var prootEndpoint: String = ""

    private fun tryStartUnixSocket() {
        val sockFile = File(prootEndpointDir, "linbox-bridge.sock")
        sockFile.delete()
        Executors.newSingleThreadExecutor().execute {
            // Real implementation: LocalServerSocket on the abstract namespace.
            // (java.net.ServerSocket is for TCP only — AF_UNIX isn't in the
            //  public JDK on Android. LocalServerSocket uses the Android-specific
            //  abstract namespace that proot can see via socat - UNIX-CONNECT:abstract:NAME.)
            try {
                val localSrv = android.net.LocalServerSocket("linbox-glibc-bridge")
                server = localSrv
                prootEndpoint = "abstract:linbox-glibc-bridge"
                Log.i(TAG, "bridge listening on $prootEndpoint")
                acceptLoop { socket ->
                    handleClient(socket.inputStream, socket.outputStream)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LocalServerSocket failed, falling back to fifo", e)
                if (mode == Mode.AUTO) startFifoLoop() else stop()
            }
        }
    }

    private fun startFifoLoop() {
        val reqFifo = File(prootEndpointDir, "linbox-bridge.in")
        val respFifo = File(prootEndpointDir, "linbox-bridge.out")
        listOf(reqFifo, respFifo).forEach { it.delete() }
        try {
            // mkfifo via Runtime.exec
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "mkdir -p '${prootEndpointDir.absolutePath}' && " +
                "rm -f '${reqFifo.absolutePath}' '${respFifo.absolutePath}' && " +
                "mkfifo '${reqFifo.absolutePath}' && " +
                "mkfifo '${respFifo.absolutePath}' && " +
                "chmod 666 '${reqFifo.absolutePath}' '${respFifo.absolutePath}'"
            )).waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "mkfifo failed", e)
            stop()
            return
        }
        prootEndpoint = "${reqFifo.absolutePath}|${respFifo.absolutePath}"
        Executors.newSingleThreadExecutor().execute {
            try {
                // Open response first to avoid blocking on the read side
                val respOut = respFifo.outputStream()
                val reqIn = reqFifo.inputStream()
                handleClient(reqIn, respOut)
            } catch (e: Exception) {
                Log.e(TAG, "fifo loop crashed", e)
            }
        }
    }

    private fun acceptLoop(handle: (android.net.LocalSocket) -> Unit) {
        val srv = server ?: return
        while (running) {
            val client = try { srv.accept() } catch (e: Exception) { break }
            Executors.newSingleThreadExecutor().execute { runCatching { handle(client) } }
        }
    }

    /**
     * Wire-protocol handler. One thread per client. Closes when client
     * disconnects.
     */
    private fun handleClient(input: java.io.InputStream, output: java.io.OutputStream) {
        val reader = BufferedReader(InputStreamReader(input))
        val writer = OutputStreamWriter(output)
        try {
            while (running) {
                val line = reader.readLine() ?: break
                val parts = line.split("\t", limit = 3)
                if (parts.size < 2) {
                    writer.write("ERR\t-\tmalformed request\n"); writer.flush()
                    continue
                }
                val verb = parts[0]
                val key = parts[1]
                when (verb) {
                    "EXEC" -> {
                        val argv = if (parts.size >= 3) parts[2] else ""
                        launchJob(key, argv, writer)
                    }
                    "BYE" -> {
                        jobs[key]?.cancel()
                        writer.write("OK\t$key\tbye\n"); writer.flush()
                    }
                    else -> {
                        writer.write("ERR\t$key\tunknown verb: $verb\n"); writer.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "client disconnected: ${e.message}")
        }
    }

    private fun launchJob(key: String, argv: String, writer: OutputStreamWriter) {
        val job = JobState(key, writer)
        jobs[key] = job
        // Sanity-check the imagefs layout BEFORE writing the "launching" ack,
        // so the proot shell gets a useful error if anything is missing.
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
            writer.write("ERR\t$key\t$msg\n"); writer.flush()
            jobs.remove(key)
            return
        }
        // Smoke test: actually run box64 --version right now. If box64 itself
        // doesn't run, wine definitely won't. Surface the actual error.
        val box64Test = launcher.smokeTestBox64()
        if (box64Test == null || box64Test.startsWith("box64 failed") || box64Test.startsWith("box64 exit")) {
            val msg = "box64 smoke test failed: $box64Test"
            Log.e(TAG, "launchJob: $msg")
            writer.write("ERR\t$key\t$msg\n"); writer.flush()
            jobs.remove(key)
            return
        }
        Log.i(TAG, "launchJob: box64 OK -> $box64Test")
        writer.write("OK\t$key\tlaunching (box64=$box64Test)\n"); writer.flush()
        // Run wine via launcher, capture exit code
        val pid = launcher.launch(
            args = argv,
            extraEnv = null,
            workingDir = fs.winePrefixDir,
            logFilePath = null,
            onExit = { code ->
                synchronized(writer) {
                    try { writer.write("END\t$key\t$code\n"); writer.flush() } catch (_: Exception) {}
                }
                jobs.remove(key)
            }
        )
        if (pid < 0) {
            val reason = launcher.lastLaunchError ?: "unknown launcher failure"
            Log.e(TAG, "launchJob: launcher failed: $reason")
            writer.write("ERR\t$key\t$reason\n"); writer.flush()
            jobs.remove(key)
        }
    }

    private class JobState(val key: String, val writer: OutputStreamWriter) {
        fun cancel() { /* launcher.stop is handled at the bridge level */ }
    }
}
