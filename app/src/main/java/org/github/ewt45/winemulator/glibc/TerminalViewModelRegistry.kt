package org.github.ewt45.winemulator.glibc

import org.github.ewt45.winemulator.viewmodel.TerminalViewModel

/**
 * Application-scope singleton holder for the [TerminalViewModel] instance
 * that [org.github.ewt45.winemulator.MainEmuActivity] creates when it
 * starts the proot shell.
 *
 * Why this exists:
 *   - `android.app.Application` is not a `ViewModelStoreOwner`, so we
 *     can't use `ViewModelProvider(application)[TerminalViewModel::class.java]`
 *     to share the same VM instance across Activities.
 *   - `androidx.lifecycle.ViewModelStore` is per-Activity / per-Fragment;
 *     a `GlibcLauncherActivity` started via `am start` gets a fresh
 *     empty store and a brand-new `TerminalViewModel` that is NOT bound
 *     to any running proot process.
 *   - So MainEmuActivity publishes its real `TerminalViewModel` here on
 *     startup, and GlibcLauncherActivity reads it back.
 *
 * Lifetime: this is a plain `object` (Kotlin singleton). It survives
 * configuration changes but dies with the process. The MainEmuActivity
 * clears it in `onDestroy` so we never end up with a stale reference
 * to a closed proot shell.
 */
object TerminalViewModelRegistry {
    @Volatile
    private var current: TerminalViewModel? = null

    fun register(vm: TerminalViewModel) {
        current = vm
    }

    fun unregister(vm: TerminalViewModel) {
        // Only clear if we're unregistering the same instance, so a
        // second Activity that registered itself later doesn't get
        // wiped by an old Activity tearing down.
        synchronized(this) {
            if (current === vm) current = null
        }
    }

    fun current(): TerminalViewModel? = current
}
