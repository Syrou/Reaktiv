package io.github.syrou.reaktiv.devtools.ui.components

import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "${bytes / 1_048_576L}.${(bytes % 1_048_576L) * 10 / 1_048_576L}MB"
    bytes >= 1024L -> "${bytes / 1024L}.${(bytes % 1024L) * 10 / 1024L}KB"
    else -> "${bytes}B"
}

internal fun formatBytes(bytes: Int): String = formatBytes(bytes.toLong())

internal fun formatDuration(ms: Long): String =
    ms.milliseconds.toComponents { hours, minutes, seconds, nanoseconds ->
        when {
            hours > 0 -> "${hours}h${minutes.toString().padStart(2, '0')}m"
            minutes > 0 -> "${minutes}m${seconds.toString().padStart(2, '0')}s"
            seconds > 0 -> "$seconds.${nanoseconds / 100_000_000}s"
            else -> "${ms}ms"
        }
    }

internal fun formatOffset(ms: Long): String =
    if (ms <= 0L) "0ms" else "+${formatDuration(ms)}"

private val clockTimeFormat = LocalTime.Format {
    hour()
    char(':')
    minute()
    char(':')
    second()
    char('.')
    secondFraction(3)
}

internal fun formatClockTime(timestamp: Long): String =
    Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .time
        .format(clockTimeFormat)

internal fun openInBrowser(url: String) {
    js("window.open(url, '_blank')")
}
