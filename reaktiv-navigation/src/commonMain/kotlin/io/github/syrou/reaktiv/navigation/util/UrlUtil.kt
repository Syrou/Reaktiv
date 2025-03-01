package io.github.syrou.reaktiv.navigation.util


object UrlUtil {
    fun encodeURIComponent(s: String): String {
        val unreservedChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
        return buildString {
            s.forEach { c ->
                when {
                    c in unreservedChars -> append(c)
                    c == ' ' -> append("%20")
                    else -> {
                        val bytes = c.toString().encodeToByteArray()
                        bytes.forEach { byte ->
                            append('%')
                            append(byte.toUByte().toString(16).padStart(2, '0').uppercase())
                        }
                    }
                }
            }
        }
    }

    fun decodeURIComponent(s: String): String {
        return buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    try {
                        val hexCode = s.substring(i + 1, i + 3)
                        val decoded = hexCode.toInt(16).toChar()
                        append(decoded)
                        i += 3
                    } catch (e: NumberFormatException) {
                        append(c)
                        i++
                    }
                } else if (c == '+') {
                    append(' ')
                    i++
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}