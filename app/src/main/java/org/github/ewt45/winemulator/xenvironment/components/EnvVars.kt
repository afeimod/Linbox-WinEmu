package org.github.ewt45.winemulator.xenvironment.components

import java.util.TreeMap

/**
 * EnvVars - 环境变量管理类
 * 对应Winlator的EnvVars类，用于构建和管理环境变量
 * 使用委托模式而不是继承 TreeMap
 */
class EnvVars private constructor(
    private val map: TreeMap<String, String>
) : Map<String, String> by map {
    
    // 无参构造函数
    companion object {
        private fun createTreeMap(): TreeMap<String, String> = TreeMap(compareBy { it })
    }
    
    constructor() : this(createTreeMap())
    
    // 从字符串解析环境变量
    constructor(envString: String) : this(createTreeMap()) {
        parseFromString(envString)
    }
    
    // 从Map创建
    constructor(map: Map<String, String>) : this(createTreeMap()) {
        this.map.putAll(map)
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
                map[parts[0]] = parts[1]
            }
        }
    }
    
    /**
     * 检查是否包含某个环境变量
     */
    fun has(name: String): Boolean = map.containsKey(name)
    
    /**
     * 转换为字符串数组（用于Process.exec）
     */
    fun toStringArray(): Array<String> {
        return map.entries.map { "${it.key}=${it.value}" }.toTypedArray()
    }
    
    /**
     * 转换为转义的字符串（用于命令行参数）
     */
    fun toEscapedString(): String {
        return map.entries.joinToString(" ") { entry ->
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
        return map.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
    
    fun toMap(): Map<String, String> = map
    
    // 实现 put 操作
    operator fun set(key: String, value: String) {
        map[key] = value
    }
    
    // 实现 putAll 操作
    fun putAll(map: Map<String, String>) {
        this.map.putAll(map)
    }
    
    // 清空操作
    fun clear() {
        map.clear()
    }
    
    // 大小
    
    // 大小
    fun size(): Int = map.size
}