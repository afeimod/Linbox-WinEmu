package org.github.ewt45.winemulator.terminal

import android.util.Log
import com.termux.shared.settings.properties.TermuxPropertyConstants
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.Exception

open class SessionClientBase : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession?) {
    }

    override fun onTitleChanged(changedSession: TerminalSession?) {
    }

    override fun onSessionFinished(finishedSession: TerminalSession?) {
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
    }

    override fun onBell(session: TerminalSession?) {
    }

    override fun onColorsChanged(session: TerminalSession?) {
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
    }

    override fun getTerminalCursorStyle() = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String?, message: String?) {
        Log.e(tag, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag, "", e)
    }
}