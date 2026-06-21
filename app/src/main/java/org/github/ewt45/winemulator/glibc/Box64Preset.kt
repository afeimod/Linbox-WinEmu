package org.github.ewt45.winemulator.glibc

/**
 * Box64 / Box86 environment-variable preset. Mirrors winlator-glibc's
 * `Box86_64Preset` enum — the strings here are *added* to the box64
 * environment inside `glibc-run.sh` (we don't write them from Java, since
 * the actual launch happens inside proot).
 *
 * For our v3 design the preset is informational only; we keep it here so
 * the pref UI (and future MainEmuActivity hooks) have a stable enum to
 * reference. The runtime behavior comes from the env vars set in
 * `assets/glibc/glibc-run.sh` based on the user's selection.
 */
enum class Box64Preset(val key: String, val description: String) {
    COMPATIBILITY("compatibility", "最大兼容,部分 3D 慢"),
    PERFORMANCE("performance", "性能优先,部分游戏闪退"),
    INTERMEDIATE("intermediate", "平衡"),
    SAFE("safe", "最稳定,速度最慢");

    companion object {
        fun fromKey(key: String?): Box64Preset =
            values().firstOrNull { it.key == key } ?: COMPATIBILITY

        /**
         * Convert a preset to a shell snippet that exports the
         * corresponding BOX64_* env vars. Empty string if no overrides.
         * Inserted into glibc-run.sh before the `exec box64` line.
         */
        fun toShellSnippet(preset: Box64Preset): String = when (preset) {
            COMPATIBILITY -> """
                export BOX64_DYNAREC=1
                export BOX64_DYNAREC_BIGBLOCK=2
                export BOX64_DYNAREC_FASTROUND=0
                export BOX64_DYNAREC_FASTNAN=0
                export BOX64_DYNAREC_SAFEFLAGS=0
                export BOX64_DYNAREC_CALLRET=0
            """.trimIndent()
            PERFORMANCE -> """
                export BOX64_DYNAREC=1
                export BOX64_DYNAREC_BIGBLOCK=3
                export BOX64_DYNAREC_FASTROUND=1
                export BOX64_DYNAREC_FASTNAN=1
                export BOX64_DYNAREC_SAFEFLAGS=0
                export BOX64_DYNAREC_CALLRET=1
            """.trimIndent()
            INTERMEDIATE -> """
                export BOX64_DYNAREC=1
                export BOX64_DYNAREC_BIGBLOCK=2
                export BOX64_DYNAREC_FASTROUND=1
                export BOX64_DYNAREC_FASTNAN=0
                export BOX64_DYNAREC_SAFEFLAGS=0
                export BOX64_DYNAREC_CALLRET=1
            """.trimIndent()
            SAFE -> """
                export BOX64_DYNAREC=0
            """.trimIndent()
        }
    }
}
