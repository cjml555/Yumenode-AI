package com.yumenode.ai

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Feature-toggle manager basado en el flavor de build (free vs premium).
 *
 * - BuildConfig.FLAVOR lo genera AGP a partir de la dimensi\u00f3n "tier"
 *   (productFlavors free/premium), por lo que vale "free" o "premium".
 */
object FeatureToggleManager {
    /** true cuando el APK es la versi\u00f3n premium (flavor = "premium") */
    val isPremium: Boolean
        get() = BuildConfig.FLAVOR == "premium"

    /** Huella SHA-256 del certificado del keystore premium (hex sin dos puntos, min\u00fasculas). */
    private const val PINNED_CERT_SHA256 = "9b8ac400e7ae106bf3d7d340be7854f7c76166cb47483251161ac438946d39f"

    /** Verifica si el certificado de firma actual coincide con el PIN. */
    fun signatureMatchesPin(context: Context): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signers = info.signingInfo?.apkContentsSigners
            val signatureBytes: ByteArray? = if (signers != null && signers.isNotEmpty()) {
                signers[0].toByteArray()
            } else {
                info.signatures?.getOrNull(0)?.toByteArray()
            }
            signatureBytes?.let { sig ->
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(sig)
                hash.joinToString("") { "%02x".format(it) } == PINNED_CERT_SHA256
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

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

    /** --- Cluster router --- */
    val enableClusterRouter: Boolean
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