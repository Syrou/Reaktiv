package io.github.syrou.reaktiv.navigation.definition


public interface UrlEncoder {
    
    public fun encodePath(value: String): String
    
    
    public fun encodeQuery(value: String): String
    
    
    public fun decode(encoded: String): String
}
