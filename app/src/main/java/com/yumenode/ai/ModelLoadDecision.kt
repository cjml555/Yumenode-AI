// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Decisiones puras de la carga de un modelo de lenguaje — delegadas al perfil de inferencia.
 */
internal object ModelLoadDecision {
    fun isGguf(path: String) = InferencePerfProfile.isGguf(path)
    fun wantsGpu(backendName: String) = InferencePerfProfile.wantsGpu(backendName)
    fun gpuLayers(backendName: String, gpuAllowed: Boolean) = InferencePerfProfile.gpuLayers(backendName, gpuAllowed)
    fun actuallyOnGpu(backendName: String, vulkanBuiltIn: Boolean, gpuAllowed: Boolean) =
        InferencePerfProfile.actuallyOnGpu(backendName, vulkanBuiltIn, gpuAllowed)
    fun shouldWarnGpuFallback(backendName: String, vulkanBuiltIn: Boolean, gpuAllowed: Boolean) =
        InferencePerfProfile.shouldWarnGpuFallback(backendName, vulkanBuiltIn, gpuAllowed)
    fun modelName(path: String) = InferencePerfProfile.modelName(path)
}
