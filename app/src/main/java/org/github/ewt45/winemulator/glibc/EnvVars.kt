package org.github.ewt45.winemulator.glibc

import java.util.LinkedHashMap

/**
 * Environment variable container.
 *
 * Mirrors the winlator-glibc EnvVars.java semantics:
 *  - put(k, v) replaces existing value
 *  - putAll(other) copies entries from another EnvVars / Map
 *  - toStringArray() returns the envp array used by ProcessBuilder / execve
 *
 * LinkedHashMap is used so the output is deterministic (helps for log diffing).
 */
class EnvVars : LinkedHashMap<String, String>() {

    override fun put(k: String, v: String): String? {
        if (k.contains("=")) {
            throw IllegalArgumentException("env key must not contain '=': $k")
        }
        return super.put(k, v)
    }

    fun putAll(other: Map<String, String>) {
        for ((k, v) in other) put(k, v)
    }

    /** Merge in raw "K=V" strings (e.g. parsed from a config file). */
    fun putAllFromArray(pairs: Array<String>) {
        for (p in pairs) {
            val idx = p.indexOf('=')
            if (idx > 0) {
                put(p.substring(0, idx), p.substring(idx + 1))
            }
        }
    }

    /** Returns a flat array of "K=V" entries suitable for Runtime.exec(envp, ...). */
    fun toStringArray(): Array<String> {
        val list = ArrayList<String>(size)
        for ((k, v) in this) list.add("$k=$v")
        return list.toTypedArray()
    }
}
