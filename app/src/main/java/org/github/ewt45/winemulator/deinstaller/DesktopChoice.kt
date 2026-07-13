package org.github.ewt45.winemulator.deinstaller

enum class DesktopChoice(val displayName: String, val value: String) {
    XFCE4("XFCE4 (轻量,推荐)", "xfce4"),
    KDE("KDE Plasma (完整,较重)", "kde"),
    SKIP("不装,我自己来", "skip");

    companion object {
        fun fromValue(v: String?): DesktopChoice =
            values().firstOrNull { it.value == v } ?: SKIP
    }
}
