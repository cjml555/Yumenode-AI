// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.CoroutineScope

/**
 * Contrato mínimo que [BenchmarkController] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible para correr el benchmark del modelo cargado.
 */
interface BenchmarkHost {

    /** Activity para recursos (getString). */
    val activity: Activity

    /** Scope de corrutinas de la Activity. */
    val scope: CoroutineScope

    /** Motorio LiteRT cargado (null = no cargado). */
    var engine: Engine?

    /** Modelo GGUF cargado (null = no cargado). */
    var llamaModel: net.ladenthin.llama.LlamaModel?

    /** Last benchmark result (guarda tps, latencia, modelo, backend y dispositivo). */
    var lastBench: BenchStats?

    /** Obtener string por id. */
    fun getString(id: Int, vararg args: Any?): String
}
