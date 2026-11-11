// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/** Último resultado del benchmark (guarda tps, latencia, modelo, backend y dispositivo). */
data class BenchStats(
    val model: String,
    val backend: String,
    val device: String,
    val soc: String,
    val tpsAvg: Double,
    val tpsMin: Double,
    val tpsMax: Double,
    val latMin: Long,
    val latMax: Long,
    val tokens: Int,
)