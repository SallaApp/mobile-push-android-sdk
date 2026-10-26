package com.salla.push.internal.payload

/**
 * The gate in front of org.json, which parses recursively and accepts far more than JSON: single-quoted strings,
 * unquoted literals (which may contain quotes), and `/* */`, `//` and `#` comments. Any of those could hide brackets
 * from a quote-aware depth count, so this is a full RFC 8259 syntax check instead, iterative (never recursive), with a
 * nesting limit. Every document SallaPush parses is strict JSON: server answers, FCM data and its own files.
 */
internal object DepthScan {

    private const val VALUE = 0
    private const val FIRST_IN_ARRAY = 1
    private const val FIRST_IN_OBJECT = 2
    private const val KEY = 3
    private const val COLON = 4
    private const val AFTER_VALUE = 5

    /** True when [json] is one strict JSON value whose objects and arrays nest at most [maxDepth] levels. */
    fun isSafe(json: String, maxDepth: Int): Boolean {
        if (maxDepth < 0) return false
        val containers = BooleanArray(maxDepth.coerceAtLeast(1))
        var depth = 0
        var expect = VALUE
        var index = skipWhitespace(json, 0)
        while (true) {
            if (expect == AFTER_VALUE && depth == 0) return skipWhitespace(json, index) == json.length
            if (index >= json.length) return false
            val c = json[index]
            when (expect) {
                VALUE, FIRST_IN_ARRAY -> when {
                    expect == FIRST_IN_ARRAY && c == ']' -> {
                        depth -= 1
                        index += 1
                        expect = AFTER_VALUE
                    }
                    c == '{' || c == '[' -> {
                        if (depth >= maxDepth) return false
                        containers[depth] = c == '{'
                        depth += 1
                        index += 1
                        expect = if (c == '{') FIRST_IN_OBJECT else FIRST_IN_ARRAY
                    }
                    else -> {
                        index = skipScalar(json, index)
                        if (index < 0) return false
                        expect = AFTER_VALUE
                    }
                }
                FIRST_IN_OBJECT, KEY -> when {
                    expect == FIRST_IN_OBJECT && c == '}' -> {
                        depth -= 1
                        index += 1
                        expect = AFTER_VALUE
                    }
                    c == '"' -> {
                        index = skipString(json, index)
                        if (index < 0) return false
                        expect = COLON
                    }
                    else -> return false
                }
                COLON -> {
                    if (c != ':') return false
                    index += 1
                    expect = VALUE
                }
                else -> {
                    val inObject = containers[depth - 1]
                    when {
                        c == ',' -> {
                            index += 1
                            expect = if (inObject) KEY else VALUE
                        }
                        (c == '}' && inObject) || (c == ']' && !inObject) -> {
                            depth -= 1
                            index += 1
                        }
                        else -> return false
                    }
                }
            }
            index = skipWhitespace(json, index)
        }
    }

    private fun skipWhitespace(json: String, start: Int): Int {
        var index = start
        while (index < json.length) {
            val c = json[index]
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
            index += 1
        }
        return index
    }

    /** A string, number, true, false or null starting at [start]: the index after it, or -1. */
    private fun skipScalar(json: String, start: Int): Int {
        val c = json[start]
        return when {
            c == '"' -> skipString(json, start)
            c == '-' || c in '0'..'9' -> skipNumber(json, start)
            json.startsWith("true", start) -> start + 4
            json.startsWith("false", start) -> start + 5
            json.startsWith("null", start) -> start + 4
            else -> -1
        }
    }

    /** A double-quoted string with JSON escapes only and no raw control characters: the index after it, or -1. */
    private fun skipString(json: String, start: Int): Int {
        var index = start + 1
        while (index < json.length) {
            val c = json[index]
            when {
                c == '"' -> return index + 1
                c < ' ' -> return -1
                c == '\\' -> {
                    if (index + 1 >= json.length) return -1
                    when (json[index + 1]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> index += 2
                        'u' -> {
                            if (index + 6 > json.length) return -1
                            for (offset in 2..5) if (!isHexDigit(json[index + offset])) return -1
                            index += 6
                        }
                        else -> return -1
                    }
                }
                else -> index += 1
            }
        }
        return -1
    }

    /** -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?: the index after it, or -1. */
    private fun skipNumber(json: String, start: Int): Int {
        var index = start
        if (json[index] == '-') index += 1
        if (index >= json.length) return -1
        when {
            json[index] == '0' -> index += 1
            json[index] in '1'..'9' -> while (index < json.length && json[index] in '0'..'9') index += 1
            else -> return -1
        }
        if (index < json.length && json[index] == '.') {
            index += 1
            val digits = index
            while (index < json.length && json[index] in '0'..'9') index += 1
            if (index == digits) return -1
        }
        if (index < json.length && (json[index] == 'e' || json[index] == 'E')) {
            index += 1
            if (index < json.length && (json[index] == '+' || json[index] == '-')) index += 1
            val digits = index
            while (index < json.length && json[index] in '0'..'9') index += 1
            if (index == digits) return -1
        }
        return index
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
