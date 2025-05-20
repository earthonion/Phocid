package org.sunsetware.phocid.utils

import kotlin.math.roundToInt

fun Int.wrap(other: Int, repeat: Boolean): Int? {
    return if (other > 0) {
        if (repeat) mod(other) else this.takeIf { it in 0..<other }
    } else null
}

fun Float.roundToIntOrZero(): Int {
    return if (isNaN()) 0 else roundToInt()
}
