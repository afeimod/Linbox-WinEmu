package org.github.ewt45.winemulator.glibc

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Thin wrapper around Runtime.exec() for spawning native glibc / box64 processes.
 *
 * Why a separate helper? The Kotlin stdlib Runtime.exec returns a Process whose
 * pid is not exposed (no public getPid()). We need the pid so we can kill the
 * box64/wine tree from the UI thread, and so the GlibcWineBridge fifo daemon
 * can report liveness. ProcessHelper extracts the pid via reflection.
 *
 * The callback runs on a worker thread, NOT the Android main thread. UI
 * consumers must post back via Handler / Dispatchers.Main.
 */
object ProcessHelper {
    private const val TAG = "ProcessHelper"

    /**
     * Split a shell-style command string into argv tokens.
     * Honours single/double quotes and backslash escapes, mirroring
     * POSIX shell tokenization for the common cases.
     */
    fun splitCommand(command: String): Array<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c == '\\' && i + 1 < command.length -> {
                    cur.append(command[i + 1])
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    i++
                    continue
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    i++
                    continue
                }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.clear()
                    }
                    i++
                    continue
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.toTypedArray()
    }

    fun exec(command: String, envp: Array<String>?, workingDir: File?, terminationCallback: ((Int) -> Unit)? = null,
             logFilePath: String? = null): Int {
        val cmd = splitCommand(command)
        val cmdStr = cmd.joinToString(" ")
        Log.d(TAG, "exec cmd=$cmdStr cwd=$workingDir env=${envp?.size ?: 0}")
        var pid = -1
        try {
            val pb = ProcessBuilder(*cmd)
            if (workingDir != null) pb.directory(workingDir)
            if (envp != null) {
                // ProcessBuilder.environment() merges with inherited env, which we don't want.
                // Clear and re-populate to get an exact envp.
                val env = pb.environment()
                env.clear()
                for (kv in envp) {
                    val idx = kv.indexOf('=')
                    if (idx > 0) env[kv.substring(0, idx)] = kv.substring(idx + 1)
                }
            }
            val proc = pb.redirectErrorStream(true).start()
            pid = extractPid(proc) ?: -1

            if (logFilePath != null) {
                startLogThread(proc.inputStream, logFilePath)
            } else {
                startSinkThread(proc.inputStream, "ProcessHelper-stdout")
            }

            if (terminationCallback != null) {
                Executors.newSingleThreadExecutor().execute {
                    val code = try { proc.waitFor() } catch (e: InterruptedException) { -1 }
                    Log.d(TAG, "process $pid exited with code=$code")
                    terminationCallback(code)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmdStr", e)
        }
        return pid
    }

    private fun extractPid(proc: Process): Int? {
        return try {
            val f = proc.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            val v = f.getInt(proc)
            f.isAccessible = false
            v
        } catch (e: Exception) {
            Log.w(TAG, "could not extract pid via reflection", e)
            null
        }
    }

    private fun startLogThread(input: java.io.InputStream, logFilePath: String) {
        Executors.newSingleThreadExecutor().execute {
            BufferedReader(InputStreamReader(input)).use { reader ->
                FileWriter(logFilePath, false).use { writer ->
                    try {
                        var line = reader.readLine()
                        while (line != null) {
                            writer.write(line + "\n")
                            writer.flush()
                            line = reader.readLine()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "log thread interrupted", e)
                    }
                }
            }
        }
    }

    private fun startSinkThread(input: java.io.InputStream, tag: String) {
        Executors.newSingleThreadExecutor().execute {
            BufferedReader(InputStreamReader(input)).use { reader ->
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        Log.d(tag, line)
                        line = reader.readLine()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** Send SIGTERM-equivalent to the pid. Android's Process.killProcess is SIGKILL. */
    fun killPid(pid: Int) {
        if (pid <= 0) return
        try {
            android.os.Process.killProcess(pid)
        } catch (e: Exception) {
            Log.w(TAG, "killPid $pid failed", e)
        }
    }
}
