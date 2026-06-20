package org.github.ewt45.winemulator.glibc

/**
 * A named box64 tuning preset.
 *
 * box64 has a large set of BOX64_* env vars. Most users don't want to think
 * about them, so we offer 4 presets that match the winlator-glibc defaults.
 */
enum class Box64Preset(val key: String, val displayName: String, val env: EnvVars) {
    COMPATIBILITY("compatibility", "Compatibility (safest)",
        EnvVars().apply {
            put("BOX64_DYNAREC", "1")
            put("BOX64_DYNAREC_BIGBLOCK", "2")
            put("BOX64_DYNAREC_STRONGMEM", "0")
            put("BOX64_DYNAREC_FASTROUND", "1")
            put("BOX64_DYNAREC_FASTNAN", "1")
            put("BOX64_DYNAREC_SAFEFLAGS", "0")
            put("BOX64_DYNAREC_CALLRET", "1")
            put("BOX64_DYNAREC_FUNCOST", "0")
            put("BOX64_DYNAREC_WAITBOX64", "0")
        }),
    PERFORMANCE("performance", "Performance",
        EnvVars().apply {
            put("BOX64_DYNAREC", "1")
            put("BOX64_DYNAREC_BIGBLOCK", "3")
            put("BOX64_DYNAREC_STRONGMEM", "1")
            put("BOX64_DYNAREC_FASTROUND", "1")
            put("BOX64_DYNAREC_FASTNAN", "1")
            put("BOX64_DYNAREC_SAFEFLAGS", "0")
            put("BOX64_DYNAREC_CALLRET", "1")
            put("BOX64_DYNAREC_FUNCOST", "0")
            put("BOX64_DYNAREC_WAITBOX64", "0")
            put("BOX64_DYNAREC_BLEEDING_EDGE", "1")
        }),
    INTERMEDIATE("intermediate", "Intermediate",
        EnvVars().apply {
            put("BOX64_DYNAREC", "1")
            put("BOX64_DYNAREC_BIGBLOCK", "2")
            put("BOX64_DYNAREC_STRONGMEM", "1")
            put("BOX64_DYNAREC_FASTROUND", "1")
            put("BOX64_DYNAREC_FASTNAN", "1")
            put("BOX64_DYNAREC_SAFEFLAGS", "0")
            put("BOX64_DYNAREC_CALLRET", "1")
            put("BOX64_DYNAREC_FUNCOST", "0")
        }),
    DISABLE_DYNAREC("disabledynarec", "Disable dynarec (debug)",
        EnvVars().apply {
            put("BOX64_DYNAREC", "0")
        });

    companion object {
        fun fromKey(k: String?): Box64Preset =
                values().firstOrNull { it.key == k } ?: COMPATIBILITY
    }
}
