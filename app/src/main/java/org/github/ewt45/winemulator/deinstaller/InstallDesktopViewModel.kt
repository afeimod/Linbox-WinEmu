package org.github.ewt45.winemulator.deinstaller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class InstallDesktopViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { IDLE, RUNNING, SUCCESS, FAILED }

    data class State(
        val rootfsPath: String = "",
        val choice: DesktopChoice = DesktopChoice.XFCE4,
        val phase: Phase = Phase.IDLE,
        val log: String = "",
        val errorMsg: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(rootfs: File, savedChoice: DesktopChoice?) {
        _state.update {
            State(
                rootfsPath = rootfs.absolutePath,
                choice = savedChoice ?: DesktopChoice.XFCE4,
                phase = Phase.IDLE,
            )
        }
        start()
    }

    fun setChoice(c: DesktopChoice) {
        if (_state.value.phase == Phase.RUNNING) return
        _state.update { it.copy(choice = c) }
    }

    fun start() {
        val s = _state.value
        if (s.phase == Phase.RUNNING) return
        if (s.choice == DesktopChoice.SKIP) {
            _state.update { it.copy(phase = Phase.SUCCESS, log = "(已跳过)\n") }
            return
        }
        _state.update { it.copy(phase = Phase.RUNNING, log = "", errorMsg = null) }
        val rootfs = File(s.rootfsPath)
        viewModelScope.launch {
            DesktopInstaller.install(
                context = getApplication(),
                rootfs = rootfs,
                choice = s.choice,
                onLine = { line ->
                    _state.update { it.copy(log = (it.log + line + "\n").takeLast(20_000)) }
                },
                onDone = { ok, err ->
                    _state.update {
                        it.copy(
                            phase = if (ok) Phase.SUCCESS else Phase.FAILED,
                            errorMsg = err,
                        )
                    }
                },
            )
        }
    }

    fun retry() = start()
    fun dismissAsHandled() {
        _state.update { it.copy(phase = Phase.IDLE, log = "", errorMsg = null) }
    }
}
