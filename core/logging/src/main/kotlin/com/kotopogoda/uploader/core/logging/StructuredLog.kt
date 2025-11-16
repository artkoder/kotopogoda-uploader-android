package com.kotopogoda.uploader.core.logging

fun structuredLog(vararg pairs: Pair<String, Any?>): String {
    if (pairs.isEmpty()) {
        return ""
    }
    return pairs.joinToString(separator = " ") { (key, value) ->
        "${key.trim()}=${formatStructuredValue(value)}"
    }
}

private fun formatStructuredValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Number -> value.toString()
    is Enum<*> -> value.name.lowercase()
    else -> "\"${escapeStructuredValue(value.toString())}\""
}

private fun escapeStructuredValue(raw: String): String {
    return buildString(raw.length) {
        raw.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
