package eu.syrou.example.ui.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

fun formatDecimal(value: Double, digits: Int): String {
    val factor = 10.0.pow(digits)
    val scaled = (abs(value) * factor).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (digits == 0) return "$sign$scaled"
    val whole = scaled / factor.toLong()
    val fraction = (scaled % factor.toLong()).toString().padStart(digits, '0')
    return "$sign$whole.$fraction"
}

fun formatDecimal(value: Float, digits: Int): String = formatDecimal(value.toDouble(), digits)
