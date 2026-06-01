package org.github.ewt45.winemulator.terminal

import org.github.ewt45.winemulator.MainEmuActivity

class ViewClientImpl(
    val activity: MainEmuActivity,
    val sessionClient: SessionClientAImpl,
): ViewClientBase() {
}