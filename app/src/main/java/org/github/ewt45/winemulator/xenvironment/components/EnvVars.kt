package org.github.ewt45.winemulator.xenvironment.components

import java.util.TreeMap

/**
 * EnvVars - 环境变量管理类
 * 对应Winlator的EnvVars类，用于构建和管理环境变量
 */
class EnvVars : TreeMap<String, String>(compareBy { it }) {
    
    constructor(envString: String) : this() {
        parseFromString(envString)
    }
    
    constructor(map: Map<String, String>) : this() {
        putAll(map)
    }
    
    /**
     * 从字符串解析环境变量（格式：KEY=VALUE,KEY=VALUE,...）
     */
    private fun parseFromString(envString: String) {
        if (envString.isEmpty()) return
        
        val pairs = envString.split(" ")
        for (pair in pairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty()) {
                this[parts[0]] = parts[1]
            }
        }
    }
    
    /**
     * 检查是否包含某个环境变量
     */
    fun has(name: String): Boolean = containsKey(name)
    
    /**
     * 转换为字符串数组（用于Process.exec）
     */
    fun toStringArray(): Array<String> {
        return entries.map { "${it.key}=${it.value}" }.toTypedArray()
    }
    
    /**
     * 转换为转义的字符串（用于命令行参数）
     */
    fun toEscapedString(): String {
        return entries.joinToString(" ") { entry ->
            "${entry.key}=${escapeValue(entry.value)}"
        }
    }
    
    /**
     * 转义环境变量值
     */
    private fun escapeValue(value: String): String {
        val escaped = StringBuilder()
        var escapedSpace = false
        
        for (char in value) {
            when (char) {
                ' ' -> {
                    escaped.append("\\ ")
                    escapedSpace = true
                }
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\t' -> escaped.append("\\t")
                else -> escaped.append(char)
            }
        }
        
        // 如果值包含空格或特殊字符，用引号包裹
        return if (escapedSpace || escaped.contains("\"") || escaped.contains("$")) {
            "\"$escaped\""
        } else {
            escaped.toString()
        }
    }
    
    override fun toString(): String {
        return entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}