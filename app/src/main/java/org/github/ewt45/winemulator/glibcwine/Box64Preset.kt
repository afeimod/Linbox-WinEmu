package org.github.ewt45.winemulator.glibcwine

/**
 * Box64 预设常量, 移植自 winlator-glibc 的 Box64Preset.java。
 *
 * 预设决定 box64 动态翻译器的性能/兼容性权衡:
 * - STABILITY: 最高稳定性, 强内存模型, 不使用快速浮点
 * - COMPATIBILITY: 平衡模式 (默认), 中等内存模型
 * - INTERMEDIATE: 中级性能, 允许快速 NaN, 大块翻译
 * - PERFORMANCE: 最高性能, 最宽松的内存模型
 * - CUSTOM: 用户自定义预设
 */
object Box64Preset {
    const val STABILITY = "STABILITY"
    const val COMPATIBILITY = "COMPATIBILITY"
    const val INTERMEDIATE = "INTERMEDIATE"
    const val PERFORMANCE = "PERFORMANCE"
    const val CUSTOM = "CUSTOM"
}

data class Box64PresetData(val id: String, val name: String) {
    fun isCustom(): Boolean = id.startsWith(Box64Preset.CUSTOM)
    override fun toString(): String = name
}
