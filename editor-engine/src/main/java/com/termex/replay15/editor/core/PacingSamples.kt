package com.termex.replay15.editor.core

import kotlin.math.ceil

/** Bounded primitive storage. Snapshot/sort only after a measurement window ends. */
internal class PacingSamples(private val capacity: Int = 65_536) {
    init { require(capacity > 0) }
    private val values = LongArray(capacity)
    private var count = 0
    private var written = 0L
    @Synchronized fun add(ns: Long) {
        if (ns < 0) return
        values[(written % capacity).toInt()] = ns
        written++
        if (count < capacity) count++
    }
    @Synchronized fun clear() { count = 0; written = 0 }
    @Synchronized fun summary(): String {
        if (count == 0) return "samples=0"
        val sorted = values.copyOf(count).also { it.sort() }
        fun p(q: Double) = sorted[(ceil(q * count).toInt() - 1).coerceIn(0, count - 1)] / 1e6
        return "samples=$count overwritten=${written - count} avgMs=${sorted.average() / 1e6} " +
            "p50Ms=${p(.5)} p90Ms=${p(.9)} p95Ms=${p(.95)} p99Ms=${p(.99)} maxMs=${sorted.last() / 1e6} " +
            "gt4=${sorted.count { it > 4_000_000 }} gt8=${sorted.count { it > 8_000_000 }} " +
            "gt16_67=${sorted.count { it > 16_666_667 }} gt33_33=${sorted.count { it > 33_333_333 }} " +
            "gt50=${sorted.count { it > 50_000_000 }} gt100=${sorted.count { it > 100_000_000 }}"
    }
}
