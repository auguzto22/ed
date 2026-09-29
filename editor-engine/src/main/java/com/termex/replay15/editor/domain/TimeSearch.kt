package com.termex.replay15.editor.domain

/** Index of the first item strictly after [timeUs] in an already validated sorted list. */
internal inline fun <T> List<T>.upperBoundTime(
    timeUs: Long,
    timestamp: (T) -> Long,
): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (timestamp(this[middle]) <= timeUs) low = middle + 1 else high = middle
    }
    return low
}
