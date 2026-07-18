package org.github.ewt45.winemulator.glibcwine

import java.util.LinkedHashMap

/**
 * 环境变量集合, 移植自 winlator-glibc 的 EnvVars.java。
 *
 * 支持以空格分隔的 "KEY=VALUE" 字符串解析和生成,
 * 用于 wine 容器的环境变量管理。
 */
class GlibcEnvVars : Iterable<String> {
    private val data = LinkedHashMap<String, String>()

    constructor()

    constructor(values: String?) {
        putAll(values)
    }

    fun put(name: String, value: Any) {
        data[name] = value.toString()
    }

    fun putAll(values: String?) {
        if (values.isNullOrEmpty()) return
        val parts = values.split(" ")
        for (part in parts) {
            val index = part.indexOf('=')
            if (index != -1) {
                val name = part.substring(0, index)
                val value = part.substring(index + 1)
                data[name] = value
            }
        }
    }

    fun putAll(envVars: GlibcEnvVars) {
        data.putAll(envVars.data)
    }

    fun get(name: String): String = data.getOrDefault(name, "")

    fun remove(name: String) {
        data.remove(name)
    }

    fun has(name: String): Boolean = data.containsKey(name)

    fun clear() {
        data.clear()
    }

    fun isEmpty(): Boolean = data.isEmpty()

    override fun toString(): String = toStringArray().joinToString(" ")

    /**
     * 转义空格后的字符串表示, 用于 shell 命令行。
     */
    fun toEscapedString(): String {
        val sb = StringBuilder()
        for (key in data.keys) {
            if (sb.isNotEmpty()) sb.append(' ')
            val value = data[key] ?: ""
            sb.append(key).append('=').append(value.replace(" ", "\\ "))
        }
        return sb.toString()
    }

    /**
     * 转为 "KEY=VALUE" 数组, 用于 ProcessBuilder.environment() 或 env -i。
     */
    fun toStringArray(): Array<String> {
        return data.entries.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    override fun iterator(): Iterator<String> = data.keys.iterator()
}
