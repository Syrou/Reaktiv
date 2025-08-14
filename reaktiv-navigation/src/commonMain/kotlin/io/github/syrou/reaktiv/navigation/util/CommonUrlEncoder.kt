package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.UrlEncoder


class CommonUrlEncoder : UrlEncoder {
    private val pathSafeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toSet()
    private val querySafeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!*'()".toSet()
    
    override fun encodePath(value: String): String {
        return encodeString(value, pathSafeChars)
    }
    
    override fun encodeQuery(value: String): String {
        return encodeString(value, querySafeChars)
    }
    
    override fun decode(encoded: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        
        while (i < encoded.length) {
            when {
                encoded[i] == '%' && i + 2 < encoded.length -> {
                    try {
                        val hex = encoded.substring(i + 1, i + 3)
                        val byte = hex.toInt(16).toByte()
                        bytes.add(byte)
                        i += 3
                    } catch (e: NumberFormatException) {
                        // Invalid hex, treat as literal character
                        bytes.addAll(encoded[i].toString().encodeToByteArray().toList())
                        i++
                    }
                }
                encoded[i] == '+' -> {
                    bytes.addAll(" ".encodeToByteArray().toList())
                    i++
                }
                else -> {
                    bytes.addAll(encoded[i].toString().encodeToByteArray().toList())
                    i++
                }
            }
        }
        
        return bytes.toByteArray().decodeToString()
    }
    
    private fun encodeString(value: String, safeChars: Set<Char>): String {
        val result = StringBuilder()
        
        for (char in value) {
            when (char) {
                in safeChars -> result.append(char)
                ' ' -> result.append("%20") // Always use %20 for spaces (more reliable than +)
                else -> {
                    val bytes = char.toString().encodeToByteArray()
                    for (byte in bytes) {
                        result.append("%${byte.toUByte().toString(16).padStart(2, '0').uppercase()}")
                    }
                }
            }
        }
        
        return result.toString()
    }
}