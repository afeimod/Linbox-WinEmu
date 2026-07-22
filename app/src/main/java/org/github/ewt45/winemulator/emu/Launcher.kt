package org.github.ewt45.winemulator.emu

import java.lang.ProcessBuilder

/**
 * 启动器接口。PRoot 和 NativeGlibc 都实现此接口，
 * TerminalViewModel 根据用户偏好选择使用哪个启动器。
 */
interface Launcher {
    /** 上次执行时的完整命令，仅用于显示 */
    var lastTimeCmd: String

    /**
     * 构建启动命令。返回 ProcessBuilder，由调用方执行 .start()
     */
    suspend fun attach(): ProcessBuilder
}
