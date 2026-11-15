// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Perfil de inferencia puro (decisiones sin estado, sin UI, sin prefs).
 *
 * Contiene el núcleo lógico de qué backend usar, cuántas capas enviar, si
 * realmente queda corriendo en GPU y si hay que avisar al usuario de un
 * fallback (GPU -> CPU). Mover aquí todas las decisiones puras elimina la
 * dispersión y facilita el seguimiento de la lógica.
 */
internal object InferencePerfProfile {

    /** Nombres de backend que el spinner de la página Modelos ofrece. */
    const val BACKEND_GPU = "GPU"
    const val BACKEND_CPU = "CPU"

    /** llama.cpp usa -1 para "todas las capas a la GPU". */
    const val GPU_LAYERS_ALL = -1

    /** ¿El archivo es un GGUF (llama.cpp) o un LiteRT-LM (.litertlm)? */
    fun isGguf(path: String): Boolean = path.endsWith(".gguf", ignoreCase = true)

    /** ¿El usuario pidió el backend GPU? */
    fun wantsGpu(backendName: String): Boolean = backendName == BACKEND_GPU

    /**
     * Capas a enviarle a llama.cpp.
     * -1 = todas las capas; 0 = solo CPU.
     */
    fun gpuLayers(backendName: String, gpuAllowed: Boolean): Int =
        if (wantsGpu(backendName) && gpuAllowed) GPU_LAYERS_ALL else 0

    /**
     * ¿El modelo realmente quedó corriendo en GPU?
     * Solo verdadero si: (a) el usuario pidió GPU, (b) el binario incluye Vulkan,
     * (c) la via experimental esta habilitada.
     */
    fun actuallyOnGpu(
        backendName: String,
        vulkanBuiltIn: Boolean,
        gpuAllowed: Boolean,
    ): Boolean = wantsGpu(backendName) && vulkanBuiltIn && gpuAllowed

    /**
     * ¿Hay que avisar al usuario de que pidió GPU pero obtuvo CPU?
     * Casos con aviso:
     *   - el build no trae Vulkan;
     *   - la via experimental esta deshabilitada;
     *   - el usuario pidió GPU.
     */
    fun shouldWarnGpuFallback(
        backendName: String,
        vulkanBuiltIn: Boolean,
        gpuAllowed: Boolean,
    ): Boolean = wantsGpu(backendName) && (!vulkanBuiltIn || !gpuAllowed)

    /**
     * Nombre del modelo para registros/logs: el archivo sin la ruta.
     */
    fun modelName(path: String): String = path.substringAfterLast('/')
}