package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Box64 预设管理器, 移植自 winlator-glibc 的 Box64PresetManager.java。
 *
 * 根据 preset id 返回对应的 BOX64_DYNAREC_* 环境变量集合。
 * 这些变量控制 box64 动态翻译器的行为, 影响性能和兼容性。
 */
object Box64PresetManager {
    private val TAG = "Box64PresetManager"

    fun getEnvVars(id: String): GlibcEnvVars {
        val envVars = GlibcEnvVars()

        when (id) {
            Box64Preset.STABILITY -> {
                envVars.put("BOX64_DYNAREC_SAFEFLAGS", "2")
                envVars.put("BOX64_DYNAREC_FASTNAN", "0")
                envVars.put("BOX64_DYNAREC_FASTROUND", "0")
                envVars.put("BOX64_DYNAREC_X87DOUBLE", "1")
                envVars.put("BOX64_DYNAREC_BIGBLOCK", "0")
                envVars.put("BOX64_DYNAREC_STRONGMEM", "2")
                envVars.put("BOX64_DYNAREC_FORWARD", "128")
                envVars.put("BOX64_DYNAREC_CALLRET", "0")
                envVars.put("BOX64_DYNAREC_WAIT", "0")
                envVars.put("BOX64_AVX", "0")
                envVars.put("BOX64_UNITYPLAYER", "1")
            }
            Box64Preset.COMPATIBILITY -> {
                envVars.put("BOX64_DYNAREC_SAFEFLAGS", "2")
                envVars.put("BOX64_DYNAREC_FASTNAN", "0")
                envVars.put("BOX64_DYNAREC_FASTROUND", "0")
                envVars.put("BOX64_DYNAREC_X87DOUBLE", "1")
                envVars.put("BOX64_DYNAREC_BIGBLOCK", "0")
                envVars.put("BOX64_DYNAREC_STRONGMEM", "1")
                envVars.put("BOX64_DYNAREC_FORWARD", "128")
                envVars.put("BOX64_DYNAREC_CALLRET", "0")
                envVars.put("BOX64_DYNAREC_WAIT", "1")
                envVars.put("BOX64_AVX", "0")
                envVars.put("BOX64_UNITYPLAYER", "1")
            }
            Box64Preset.INTERMEDIATE -> {
                envVars.put("BOX64_DYNAREC_SAFEFLAGS", "2")
                envVars.put("BOX64_DYNAREC_FASTNAN", "1")
                envVars.put("BOX64_DYNAREC_FASTROUND", "0")
                envVars.put("BOX64_DYNAREC_X87DOUBLE", "1")
                envVars.put("BOX64_DYNAREC_BIGBLOCK", "1")
                envVars.put("BOX64_DYNAREC_STRONGMEM", "0")
                envVars.put("BOX64_DYNAREC_FORWARD", "128")
                envVars.put("BOX64_DYNAREC_CALLRET", "0")
                envVars.put("BOX64_DYNAREC_WAIT", "1")
                envVars.put("BOX64_AVX", "0")
                envVars.put("BOX64_UNITYPLAYER", "0")
            }
            Box64Preset.PERFORMANCE -> {
                envVars.put("BOX64_DYNAREC_SAFEFLAGS", "1")
                envVars.put("BOX64_DYNAREC_FASTNAN", "1")
                envVars.put("BOX64_DYNAREC_FASTROUND", "1")
                envVars.put("BOX64_DYNAREC_X87DOUBLE", "0")
                envVars.put("BOX64_DYNAREC_BIGBLOCK", "3")
                envVars.put("BOX64_DYNAREC_STRONGMEM", "0")
                envVars.put("BOX64_DYNAREC_FORWARD", "512")
                envVars.put("BOX64_DYNAREC_CALLRET", "1")
                envVars.put("BOX64_DYNAREC_WAIT", "1")
                envVars.put("BOX64_AVX", "0")
                envVars.put("BOX64_UNITYPLAYER", "0")
            }
            else -> {
                if (id.startsWith(Box64Preset.CUSTOM)) {
                    // 自定义预设从 SharedPreferences 读取
                    // 格式: "CUSTOM-1|名称|KEY=VAL KEY=VAL,..."
                    // 暂时简化, 返回空
                    Log.w(TAG, "自定义预设 $id 暂未实现, 使用 COMPATIBILITY")
                    return getEnvVars(Box64Preset.COMPATIBILITY)
                }
            }
        }

        return envVars
    }

    fun getPresets(context: Context): List<Box64PresetData> {
        return listOf(
            Box64PresetData(Box64Preset.STABILITY, "稳定性"),
            Box64PresetData(Box64Preset.COMPATIBILITY, "兼容性"),
            Box64PresetData(Box64Preset.INTERMEDIATE, "中级"),
            Box64PresetData(Box64Preset.PERFORMANCE, "性能"),
        )
    }
}
