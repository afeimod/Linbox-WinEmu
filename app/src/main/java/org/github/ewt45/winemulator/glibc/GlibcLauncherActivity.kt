package org.github.ewt45.winemulator.glibc

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Tiny launcher activity that lets the user run box64+wine on a
 * .exe (or any command) directly from the Android side — no proot.
 *
 * This is the v3.1 escape hatch after we discovered that running
 * box64 from inside linbox's proot container segfaults due to
 * proot's syscall translation layer conflicting with musl's
 * startup sequence.
 *
 * Usage:
 *   1. The user runs the launcher (icon on the home screen or via
 *      `am start -n .../.glibc.GlibcLauncherActivity`).
 *   2. They type the absolute Android-side path of the .exe (e.g.
 *      `/data/data/.../imagefs/home/xuser/drive_c/Program.exe`) and
 *      tap Run.
 *   3. We spawn `box64 wine <path> <args>` via ProcessBuilder with
 *      the imagefs as cwd, wire its stdout/stderr to a ScrollView,
 *      and exit when the process terminates.
 *
 * Requirements:
 *   - The box64 binary must be patched with:
 *       patchelf --set-interpreter
 *         /data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/lib/ld-linux-aarch64.so.1
 *         /data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/local/bin/box64
 *   - Same patch for wine and any other ELF in the imagefs.
 *   - The imagefs must be installed (this activity does NOT install
 *     it; the existing v3 installer handles that).
 */
class GlibcLauncherActivity : Activity() {
    private val TAG = "GlibcLauncher"
    private lateinit var exeInput: EditText
    private lateinit var argsInput: EditText
    private lateinit var outputView: TextView
    private lateinit var statusView: TextView
    private lateinit var runButton: Button
    private var process: java.lang.Process? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageFs = ImageFs.find(this)
        val launcher = GlibcProgramLauncher(imageFs)
        // Auto-start the Termux:X11 server so wine has something to
        // connect to. linbox's MainEmuActivity normally does this in
        // onCreate but GlibcLauncherActivity is a separate Activity —
        // the user can launch it directly from the home screen without
        // ever visiting the desktop.
        ensureX11Running()
        if (!imageFs.isValid) {
            // Dump diagnostics so the user knows which sentinel file
            // is missing — isValid just checks for box64/wine64/libc.so.6
            // but doesn't say which one.
            val root = imageFs.rootDir.absolutePath
            val checks = listOf(
                "$root/usr/local/bin/box64",
                "$root/usr/bin/box64",
                "$root/opt/wine/bin/wine",
                "$root/usr/bin/wine",
                "$root/usr/lib/libc.so.6",
                "$root/usr/lib/x86_64-linux-gnu/libc.so.6",
            )
            val lines = checks.map { path ->
                val f = File(path)
                if (f.exists()) "OK   $path (${f.length()} B)"
                else            "MISS $path"
            }
            val msg = "imagefs 没装好。详细:\n" + lines.joinToString("\n")
            android.app.AlertDialog.Builder(this)
                .setTitle("GlibcLauncher")
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
            return
        }
        val verifyErr = launcher.verifyReady()
        if (verifyErr != null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("GlibcLauncher")
                .setMessage("imagefs 验证失败: $verifyErr")
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
            return
        }
        val box64 = launcher.androidBox64Path() ?: run {
            Toast.makeText(this, "box64 没找到", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val wine = launcher.androidWinePath() ?: run {
            Toast.makeText(this, "wine 没找到", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "box64: $box64"
            textSize = 10f
        })
        root.addView(TextView(this).apply {
            text = "wine:  $wine"
            textSize = 10f
        })

        root.addView(TextView(this).apply {
            text = "Executable (.exe) — absolute Android-side path:"
            setPadding(0, 16, 0, 4)
        })
        exeInput = EditText(this).apply {
            hint = "/data/data/a.io.github.ewt45.winemulator/files/imagefs/home/xuser/drive_c/..."
            setSingleLine(true)
            setText("/data/data/a.io.github.ewt45.winemulator/files/imagefs/home/xuser/drive_c/Program.exe")
        }
        root.addView(exeInput)

        root.addView(TextView(this).apply {
            text = "Extra args (passed to wine):"
            setPadding(0, 16, 0, 4)
        })
        argsInput = EditText(this).apply {
            hint = "/silent /nologo"
        }
        root.addView(argsInput)

        runButton = Button(this).apply {
            text = "Run box64 wine"
            setOnClickListener { onRun() }
        }
        root.addView(runButton)

        statusView = TextView(this).apply { text = "ready" }
        root.addView(statusView)

        outputView = TextView(this).apply {
            text = ""
            textSize = 10f
            setPadding(0, 16, 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(outputView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
    }

    private fun ensureX11Running() {
        // Detect whether Termux:X11 (linbox's X server) is already
        // running. We use the abstract-socket presence at
        // `/data/data/<pkg>/cache/tmp/.X11-unix/X<display>` as the
        // marker. linbox's X11Service sets XKB_CONFIG_ROOT to the
        // rootfs xkb path and runs `CmdEntryPoint.main(arrayOf(":13"))`.
        // If the socket isn't there we start the service.
        val tmp = org.github.ewt45.winemulator.Consts.tmpDir
        val sock = File(tmp, ".X11-unix/X13")
        if (sock.exists()) {
            appendOutput("X11 already running: ${sock.absolutePath}\n")
            return
        }
        // Also fall back to "is xserver-termux-x11 process alive?"
        val xserverAlive = try {
            Runtime.getRuntime().exec("pidof xserver-termux-x11").waitFor() == 0
        } catch (_: Exception) { false }
        if (xserverAlive) {
            appendOutput("X11 server process alive (pidof)\n")
            return
        }
        appendOutput("X11 not running; starting X11Service...\n")
        try {
            val intent = Intent(this, org.github.ewt45.winemulator.emu.X11Service::class.java).apply {
                putExtra("timestamp", System.currentTimeMillis())
            }
            startService(intent)
        } catch (e: Exception) {
            appendOutput("failed to start X11Service: ${e.message}\n")
        }
    }

    private fun onRun() {
        val imageFs = ImageFs.find(this)
        val launcher = GlibcProgramLauncher(imageFs)
        val exe = exeInput.text.toString().trim()
        val args = argsInput.text.toString().trim()
        val fullArgs = if (args.isEmpty()) listOf(exe) else listOf(exe) + args.split(" ")

        val cmd = launcher.buildCommand(*fullArgs.toTypedArray()) ?: run {
            appendOutput("box64 or wine not found in imagefs\n")
            return
        }
        appendOutput("$ ${cmd.joinToString(" ")}\n")

        val env = launcher.buildEnv(org.github.ewt45.winemulator.Consts.tmpDir.absolutePath)
        appendOutput("env DISPLAY=${env["DISPLAY"]} XDG_RUNTIME_DIR=${env["XDG_RUNTIME_DIR"]} TMPDIR=${env["TMPDIR"]}\n")
        appendOutput("cmd[0] (box64) = ${cmd[0]}\n")
        appendOutput("cmd[1] (wine) = ${cmd[1]}\n")
        try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(imageFs.rootDir)
            pb.redirectErrorStream(true)
            process = pb.start()
        } catch (e: Exception) {
            appendOutput("failed to start: ${e.message}\n")
            return
        }
        runButton.isEnabled = false
        statusView.text = "running"
        Thread {
            process?.inputStream?.let { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.forEach { line ->
                        runOnUiThread { appendOutput("$line\n") }
                    }
                }
            }
            val rc = try { process?.waitFor() ?: -1 } catch (e: Exception) { -1 }
            runOnUiThread {
                statusView.text = "exited with code $rc"
                runButton.isEnabled = true
            }
        }.start()
    }

    private fun appendOutput(s: String) {
        outputView.append(s)
        // Best-effort scroll
        val parent = outputView.parent as? ScrollView
        parent?.post { parent.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { process?.destroy() } catch (_: Exception) {}
    }
}
