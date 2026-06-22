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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageFs = ImageFs.find(this)
        val launcher = GlibcProgramLauncher(imageFs)
        // Auto-start the Termux:X11 server so wine has something to
        // connect to. linbox's MainEmuActivity normally does this in
        // onCreate but GlibcLauncherActivity is a separate Activity —
        // the user can launch it directly from the home screen without
        // ever visiting the desktop. Note: the actual startService
        // call is deferred until setContentView has run (in
        // setupUi()), since the X11 probe writes diagnostic text to
        // outputView and that view is lateinit.
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

        // Wrap the entire root in a vertical ScrollView so that when
        // Termux:X11's builtin-display overlay or the system IME push
        // the form down, the user can still scroll to reach the exe
        // / args EditTexts and the Run button.
        val outerScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(outerScroll)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Defer the X server probe to a background coroutine so that
        // any appendOutput() call (which references the lateinit
        // outputView) is safe. By the time the coroutine runs, the
        // Activity is fully constructed and views are bound.
        ioScope.launch {
            ensureX11Running()
        }
    }

    private suspend fun ensureX11Running() {
        // Detect whether Termux:X11 (linbox's X server) is already
        // running. We use the abstract-socket presence at
        // `/data/data/<pkg>/cache/tmp/.X11-unix/X<display>` as the
        // marker. linbox's X11Service sets XKB_CONFIG_ROOT to the
        // rootfs xkb path and runs `CmdEntryPoint.main(arrayOf(":13"))`.
        // If the socket isn't there we start the service.
        val tmp = org.github.ewt45.winemulator.Consts.tmpDir
        // Make sure .X11-unix exists; some Termux:X11 builds only
        // listen on abstract sockets and skip the filesystem entry.
        File(tmp, ".X11-unix").mkdirs()

        val sock = File(tmp, ".X11-unix/X13")
        if (sock.exists()) {
            appendOutput("X11 socket already present: ${sock.absolutePath}\n")
        } else {
            // Start X11Service unconditionally if the socket isn't
            // there. Termux:X11 may be listening on an abstract socket
            // instead (which can't be detected from the filesystem),
            // but starting it a second time is cheap if it's already
            // running — X11Service.onStartCommand is idempotent.
            appendOutput("X11 not detected; starting X11Service...\n")
            try {
                val intent = Intent(this, org.github.ewt45.winemulator.emu.X11Service::class.java).apply {
                    putExtra("timestamp", System.currentTimeMillis())
                }
                startService(intent)
            } catch (e: Exception) {
                appendOutput("failed to start X11Service: ${e.message}\n")
            }
        }

        // Wait briefly so CmdEntryPoint.main has time to bring up the
        // socket. 1.5 s is the empirical sweet spot — too short and
        // the first connect race-loses, too long and the user thinks
        // the app is hung.
        appendOutput("waiting 2s for X11 to come up...\n")
        kotlinx.coroutines.delay(2000)
        if (sock.exists()) {
            appendOutput("X11 ready: ${sock.absolutePath}\n")
        } else {
            appendOutput("X11 socket still not present at ${sock.absolutePath}; continuing anyway\n")
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
        appendOutput("Android-side cmd: ${cmd.joinToString(" ")}\n")
        // Rewrite to proot-side paths. Proot chroots at
        // <rootfsCurrDir>; Proot.attach() binds our imagefs to /imagefs
        // inside that chroot. The user-provided exe path is also
        // under <filesDir>/imagefs/..., which proot sees as /imagefs.
        val imageFsRoot = imageFs.rootDir.absolutePath
        fun toProotPath(androidAbs: String): String {
            if (androidAbs.startsWith(imageFsRoot)) {
                return "/imagefs" + androidAbs.substring(imageFsRoot.length)
            }
            return androidAbs
        }
        val prootCmd = cmd.drop(1).map { toProotPath(it) }
        val prootBox64 = toProotPath(cmd[0])
        val fullProotCmd = listOf(prootBox64) + prootCmd
        appendOutput("proot-side cmd: ${fullProotCmd.joinToString(" ")}\n")
        appendOutput("cmd[0] (box64) = $prootBox64\n")
        appendOutput("cmd[1] (wine) = ${prootCmd.firstOrNull() ?: "<none>"}\n")

        // Launch box64+wine via linbox's proot so the new process
        // shares the same Termux:X11 server that the rest of the
        // app uses (the linbox desktop, xfce, etc. all run through
        // proot + DISPLAY=:13, so any X client forked this way
        // inherits the same X server connection path).
        //
        // We bypass GlibcProgramLauncher.runDirect() because that
        // forks on the Android side where the Termux:X11 LorieView
        // lives in MainEmuActivity and is unreachable from a
        // plain Activity's view tree. Going through proot gives
        // us a process whose DISPLAY=:13 env var is honored by
        // box64/wine in the same way the user's other X apps
        // (xfce, etc.) get it.
        runViaProot(fullProotCmd)
    }

    private fun runViaProot(box64Cmd: List<String>) {
        val ctx = this
        val proot = org.github.ewt45.winemulator.emu.Proot()
        appendOutput("starting proot...\n")
        ioScope.launch {
            val proc = try {
                val pb = proot.attach()
                appendOutput("proot attach done; starting process\n")
                pb.start()
            } catch (e: Exception) {
                appendOutput("failed to start proot: ${e.message}\n")
                return@launch
            }
            process = proc
            runOnUiThread {
                runButton.isEnabled = false
                statusView.text = "running (via proot)"
            }
            // Inside the proot sh, execute the requested box64+wine
            // command. The /imagefs mount is provided by Proot.attach().
            try {
                val writer = java.io.OutputStreamWriter(proc.outputStream)
                writer.write(box64Cmd.joinToString(" ") + "\n")
                writer.flush()
            } catch (e: Exception) {
                appendOutput("failed to write to proot stdin: ${e.message}\n")
            }
            // Stream proot's stdout+stderr back to the output view.
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        runOnUiThread { appendOutput("$line\n") }
                    }
                }
            } catch (_: Exception) { }
            val rc = try { proc.waitFor() } catch (e: Exception) { -1 }
            runOnUiThread {
                statusView.text = "exited with code $rc"
                runButton.isEnabled = true
            }
        }
    }

    private fun appendOutput(s: String) {
        // Also emit to logcat so we can diagnose even if the TextView
        // is scrolled past or hidden. Tag = "GlibcLauncher".
        android.util.Log.i("GlibcLauncher", s.trimEnd())
        if (::outputView.isInitialized) {
            runOnUiThread { actuallyAppend(s) }
        }
    }

    private fun actuallyAppend(s: String) {
        // Always called on the main thread.
        outputView.append(s)
        val parent = outputView.parent as? ScrollView
        parent?.post { parent.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { process?.destroy() } catch (_: Exception) {}
    }
}
