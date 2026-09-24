package net.helcel.owu.crypto

/**
 * Deterministic JSON, the RFC 8785 subset the ledger needs: keys sorted by
 * UTF-16 code unit, strings, whole numbers, booleans, lists. Everything signed
 * or hashed comes through here, never the transport encoder, so the wire
 * format can change without breaking a signature.
 *
 * Nulls are dropped, so a field added later does not alter older bytes. Floats
 * are rejected: one double has no single textual form across platforms, which
 * is why coordinates are micro-degrees.
 */
object Canonical {

    fun bytes(value: Any?): ByteArray = encode(value).toByteArray(Charsets.UTF_8)

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value)
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                val keys = value.keys.map {
                    it as? String ?: throw IllegalArgumentException("non-string key: $it")
                }.sorted()
                sb.append('{')
                var first = true
                for (k in keys) {
                    val v = value[k] ?: continue
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    write(sb, v)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("cannot canonicalise ${value::class.simpleName}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
