package org.github.ewt45.winemulator.prootdistro

/**
 * 参考 `proot_distro/names.py`——验证容器别名格式。
 *
 * 规则:仅允许 [a-z0-9_.-],长度 1..64。
 * 此外要求首字符是 [a-z0-9] (避免 . / _ 开头)。
 */
object ProotDistroNames {

    private val VALID_NAME_RE = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")

    fun isValidName(name: String): Boolean {
        return name.isNotEmpty() && VALID_NAME_RE.matches(name)
    }

    fun requireValidName(name: String) {
        if (!isValidName(name)) {
            throw IllegalArgumentException(
                "invalid container name '$name'. Names must match " +
                "^[a-z0-9][a-z0-9_.\\\\-]{0,63}$"
            )
        }
    }
}