package org.github.ewt45.winemulator.glibc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.R
import java.io.File

/**
 * Foreground service that owns the GlibcProgramLauncher + GlibcWineBridge.
 *
 * Why a foreground service?
 *  - The user might minimize linbox and let wine keep running.
 *  - Android 8+ kills background processes aggressively, so we need
 *    startForeground() to keep box64/wine alive.
 *  - The bridge server socket needs to stay open as long as the proot
 *    container is running.
 *
 * Lifecycle:
 *  - onCreate: builds launcher + bridge.
 *  - onStartCommand: starts wine if the intent has EXTRA_GLIBC_ARGS, otherwise
 *    just makes sure the bridge is up.
 *  - onDestroy: stops launcher, stops bridge.
 *
 * UI talks to it via GlibcLauncherBinder.
 */
class GlibcLauncherService : Service() {

    private val TAG = "GlibcLauncherService"
    private val CHANNEL_ID = "linbox-glibc-launcher"
    private val NOTIF_ID = 7893

    private var launcher: GlibcProgramLauncher? = null
    private var bridge: GlibcWineBridge? = null

    inner class GlibcLauncherBinder : Binder() {
        fun getService(): GlibcLauncherService = this@GlibcLauncherService
    }

    private val binder = GlibcLauncherBinder()

    override fun onCreate() {
        super.onCreate()
        val fs = ImageFs.find(this)
        ImageFs.ensureLayout(fs)
        // Pref.get() is suspend, wrap in runBlocking for service startup.
        val modeStr = kotlinx.coroutines.runBlocking {
            Consts.Pref.glibc_bridge_mode.get()
        }
        val l = GlibcProgramLauncher(this, fs)
        val ok = l.ensureInstalled()
        if (!ok) Log.w(TAG, "glibcfs install incomplete — wine won't run until assets are populated")
        launcher = l

        val bridgeDir = File(File(cacheDir, "tmp"), "linbox-glibc")
        bridgeDir.mkdirs()
        val mode = when (modeStr) {
            "unix_socket" -> GlibcWineBridge.Mode.UNIX_SOCKET
            "fifo" -> GlibcWineBridge.Mode.FIFO
            else -> GlibcWineBridge.Mode.AUTO
        }
        val b = GlibcWineBridge(fs, l, bridgeDir, mode = mode)
        b.start()
        bridge = b
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        intent?.getStringExtra(EXTRA_GLIBC_ARGS)?.let { args ->
            val pid = launcher?.launch(
                args = args,
                workingDir = File(launcher!!.fs().winePrefixDir, "drive_c"),
                logFilePath = File(File(cacheDir, "tmp"), "linbox-glibc/wine.log").absolutePath
            ) ?: -1
            Log.i(TAG, "launched wine, pid=$pid, args=$args")
        }
        return START_STICKY
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "glibc/wine",
                        NotificationManager.IMPORTANCE_LOW)
                mgr.createNotificationChannel(ch)
            }
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("linbox glibc/wine")
                .setContentText("box64+wine running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge?.stop()
        launcher?.stop()
        bridge = null
        launcher = null
    }

    /** Public API for UI to start a wine command. Returns the pid or -1. */
    fun launch(args: String, workingDir: File? = null, logFile: String? = null): Int {
        val l = launcher ?: return -1
        val wd = workingDir ?: File(l.fs().winePrefixDir, "drive_c")
        val log = logFile ?: File(File(cacheDir, "tmp"), "linbox-glibc/wine.log").absolutePath
        return l.launch(args, workingDir = wd, logFilePath = log)
    }

    fun stopWine() {
        launcher?.stop()
    }

    fun bridgeStatus(): String = bridge?.prootEndpoint ?: "(not started)"

    companion object {
        const val EXTRA_GLIBC_ARGS = "glibc_args"
    }
}
