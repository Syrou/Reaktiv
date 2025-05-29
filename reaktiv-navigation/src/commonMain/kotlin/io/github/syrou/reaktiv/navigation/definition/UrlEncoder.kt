package io.github.syrou.reaktiv.navigation.definition


interface UrlEncoder {
    
    fun encodePath(value: String): String
    
    
    fun encodeQuery(value: String): String
    
    
    fun decode(encoded: String): String
}