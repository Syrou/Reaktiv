package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.UrlEncoder


public class CommonUrlEncoder : UrlEncoder {
    private val pathSafeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()
    private val querySafeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!*'()".toSet()

    override fun encodePath(value: String): String {
        return encodeString(value, pathSafeChars)
    }

    override fun encodeQuery(value: String): String {
        return encodeString(value, querySafeChars)
    }

    override fun decode(encoded: String): String {
        return decodeSingle(encoded, plusIsSpace = true)
    }

    internal fun decodePathSegment(encoded: String): String {
        return decodeSingle(encoded, plusIsSpace = false)
    }

    private fun decodeSingle(encoded: String, plusIsSpace: Boolean): String {
        val bytes = mutableListOf<Byte>()
        var i = 0

        while (i < encoded.length) {
            when {
                encoded[i] == '%' && i + 2 < encoded.length &&
                    encoded[i + 1].digitToIntOrNull(16) != null && encoded[i + 2].digitToIntOrNull(16) != null -> {
                    bytes.add(encoded.substring(i + 1, i + 3).toInt(16).toByte())
                    i += 3
                }
                plusIsSpace && encoded[i] == '+' -> {
                    bytes.addAll(" ".encodeToByteArray().toList())
                    i++
                }
                else -> {
                    val width = if (encoded[i].isHighSurrogate() && i + 1 < encoded.length &&
                        encoded[i + 1].isLowSurrogate()) 2 else 1
                    bytes.addAll(encoded.substring(i, i + width).encodeToByteArray().toList())
                    i += width
                }
            }
        }

        return bytes.toByteArray().decodeToString()
    }

    private fun encodeString(value: String, safeChars: Set<Char>): String {
        val result = StringBuilder()
        var i = 0

        while (i < value.length) {
            val char = value[i]
            when (char) {
                in safeChars -> {
                    result.append(char)
                    i++
                }
                ' ' -> {
                    // Always use %20 for spaces (more reliable than +)
                    result.append("%20")
                    i++
                }
                else -> {
                    // Handle Unicode characters properly by encoding the substring
                    val charString = if (char.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                        // Handle surrogate pairs (emojis, etc.)
                        val surrogatePair = value.substring(i, i + 2)
                        i += 2
                        surrogatePair
                    } else {
                        i++
                        char.toString()
                    }

                    val charBytes = charString.encodeToByteArray()
                    for (byte in charBytes) {
                        result.append("%${byte.toUByte().toString(16).padStart(2, '0').uppercase()}")
                    }
                }
            }
        }

        return result.toString()
    }
}
