package com.yumenode.ai

/**
 * Feature-toggle manager basado en el flavor de build (free vs premium).
 *
 * - BuildConfig.FLAVOR lo genera AGP a partir de la dimensión "tier"
 *   (productFlavors free/premium), por lo que vale "free" o "premium".
 */
object FeatureToggleManager {
    /** true cuando el APK es la versión premium (flavor = "premium") */
    val isPremium: Boolean
        get() = BuildConfig.FLAVOR == "premium"

    /** --- Cloud / Sync --- */
    val enableCloudSync: Boolean
        get() = isPremium

    /** --- Batch Generation --- */
    val enableBatchGeneration: Boolean
        get() = isPremium

    /** --- Advanced Benchmark --- */
    val enableAdvancedBenchmark: Boolean
        get() = isPremium

    /** --- Session Export --- */
    val enableSessionExport: Boolean
        get() = isPremium

    /** --- Usage Analytics --- */
    val enableUsageAnalytics: Boolean
        get() = isPremium

    /** --- Custom Prompts --- */
    val enableCustomPrompts: Boolean
        get() = isPremium

    /** --- Multi-device API --- */
    val enableMultiDeviceAPI: Boolean
        get() = isPremium

    /** --- Priority GPU --- */
    val enablePriorityGPU: Boolean
        get() = isPremium
}

/** Enum para que el código sea auto-documentado y fácil de refactorizar. */
enum class Feature {
    enableCloudSync,
    enableBatchGeneration,
    enableAdvancedBenchmark,
    enableSessionExport,
    enableUsageAnalytics,
    enableCustomPrompts,
    enableMultiDeviceAPI,
    enablePriorityGPU
}