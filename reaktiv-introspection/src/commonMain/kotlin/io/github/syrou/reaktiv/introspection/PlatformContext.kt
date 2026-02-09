package io.github.syrou.reaktiv.introspection

/**
 * Platform-specific context wrapper.
 *
 * On Android, this is a typealias for `android.content.Context`.
 * On all other platforms, this is an empty class (no-op).
 */
expect class PlatformContext
