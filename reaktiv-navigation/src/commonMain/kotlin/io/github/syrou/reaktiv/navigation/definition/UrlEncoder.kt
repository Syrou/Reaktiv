package io.github.syrou.reaktiv.navigation.definition

/**
 * Cross-platform URL encoder and decoder for navigation parameters
 * Follows RFC 3986 standards for URL encoding
 */
interface UrlEncoder {
    /**
     * Encode a string for use in URL path segments
     * More restrictive than query parameter encoding
     */
    fun encodePath(value: String): String
    
    /**
     * Encode a string for use in URL query parameters
     * Allows more characters than path encoding
     */
    fun encodeQuery(value: String): String
    
    /**
     * Decode a URL-encoded string
     */
    fun decode(encoded: String): String
}