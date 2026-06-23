package org.github.ewt45.winemulator.glibc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * glibc wine 启动器 Activity。
 *
 * 提供两个入口:
 *
 *   1) "在 proot 内启动" (主路径,推荐)
 *      按下 "在 proot 内启动" 按钮后,我们通过 [TerminalViewModel.runCommand]
 *      把 `glibc-run <args>` 发到 linbox 主 Activity 已经启动的 proot shell
 *      里。box64+wine 在 proot 里启动,直接共享 host /tmp → Termux:X11 的
 *      socket 自然连得上,窗口能正常显示。
 *
 *   2) "Android 侧直接启动" (调试)
 *      按下 "Android 侧启动" 按钮后,本进程直接 fork `box64 wine ...`。
 *      保留给 adb 调试 (`am start ...`) 用; 因为不在 linbox 主进程的
 *      X server namespace 里,Termux:X11 可能拒绝连接,通常不显示。
 *
 * 用法:
 *   - 从 launcher 打开这个 Activity,输入 .exe 路径,选一个模式按按钮
 *   - 或者从 adb:
 *       adb shell am start -n \
 *         a.io.github.ewt45.winemulator/org.github.ewt45.winemulator.glibc.GlibcLauncherActivity
 *
 * 主路径推荐: 在 linbox 设置 → PRoot 参数 → "启动后执行命令" 里填
 *     glibc-run /home/xuser/.wine/drive_c/<你的游戏>.exe
 * 容器一启动就自动跑游戏,不需要打开这个 Activity。
 */
class GlibcLauncherActivity : Activity() {
    private val TAG = "GlibcLauncher"
    private lateinit var exeInput: EditText
    private lateinit var argsInput: EditText
    private lateinit var outputView: TextView
    private lateinit var statusView: TextView
    private lateinit var prootButton: Button
    private lateinit var directButton: Button
    private var process: java.lang.Process? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageFs = ImageFs.find(this)
        val launcher = GlibcProgramLauncher(imageFs)
        if (!imageFs.isValid) {
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
        val box64Proot = launcher.prootBox64Path() ?: "(imagefs 里没找到)"
        val wineProot = launcher.prootWinePath() ?: "(imagefs 里没找到)"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "box64 (proot 内路径): $box64Proot"
            textSize = 10f
        })
        root.addView(TextView(this).apply {
            text = "wine  (proot 内路径): $wineProot"
            textSize = 10f
        })

        root.addView(TextView(this).apply {
            text = "Executable — 可以是 proot 内绝对路径 (/home/xuser/.wine/drive_c/foo.exe), 也可以是 wine 子命令 (winecfg, winefile):"
            setPadding(0, 16, 0, 4)
        })
        exeInput = EditText(this).apply {
            hint = "/home/xuser/.wine/drive_c/Program Files/..."
            setSingleLine(true)
            setText("/home/xuser/.wine/drive_c/Program.exe")
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

        prootButton = Button(this).apply {
            text = "在 proot 内启动 (推荐)"
            setOnClickListener { onRunInProot() }
        }
        root.addView(prootButton)

        directButton = Button(this).apply {
            text = "Android 侧直接启动 (调试用)"
            setOnClickListener { onRunDirect() }
        }
        root.addView(directButton)

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

    /**
     * 主路径: 通过 proot shell 启动 glibc-run (会 exec box64+wine)。
     * 需要拿到已经启动的 TerminalViewModel。如果 MainEmuActivity 没在
     * 跑 (proot 没启动), 会提示用户先打开主界面。
     */
    private fun onRunInProot() {
        val imageFs = ImageFs.find(this)
        val launcher = GlibcProgramLauncher(imageFs)
        val exe = exeInput.text.toString().trim()
        val args = argsInput.text.toString().trim()
        val fullArgs = if (args.isEmpty()) exe else "$exe $args"
        if (exe.isBlank()) {
            Toast.makeText(this, "请输入要执行的 exe 路径", Toast.LENGTH_SHORT).show()
            return
        }

        // 拿到 MainEmuActivity 里创建的 TerminalViewModel (同一个 proot shell)。
        // MainEmuActivity 启动 proot 后会调用
        //     TerminalViewModelRegistry.register(terminal)
        // 把 singleton 引用放到 application 上。这样我们这个 Activity
        // 能在不开启新 proot 的前提下复用同一个 shell。
        val terminal: TerminalViewModel? = TerminalViewModelRegistry.current()
        if (terminal == null) {
            Toast.makeText(
                this,
                "TerminalViewModel 拿不到。请先打开 linbox 主界面启动 proot。",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        appendOutput("→ proot 内启动: glibc-run $fullArgs &\n")
        prootButton.isEnabled = false
        directButton.isEnabled = false
        statusView.text = "已在 proot 启动 (后台)"
        launcher.runInProot(terminal, fullArgs, background = true)
        // 不能在这里 re-enable 按钮,因为 wine 一直在跑;
        // 用户关掉 Activity 即可,proot 内的 wine 还会继续。
    }

    /**
     * 调试路径: Android 进程直接 fork box64+wine。
     * 保留给 adb 调试; Termux:X11 可能拒绝连接,X11 不会显示。
     */
    private fun onRunDirect() {
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

        val env = launcher.buildEnv(Consts.tmpDir.absolutePath)
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
        prootButton.isEnabled = false
        directButton.isEnabled = false
        statusView.text = "直接启动中..."
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
                prootButton.isEnabled = true
                directButton.isEnabled = true
            }
        }.start()
    }

    private fun appendOutput(s: String) {
        outputView.append(s)
        val parent = outputView.parent as? ScrollView
        parent?.post { parent.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { process?.destroy() } catch (_: Exception) {}
    }
}
