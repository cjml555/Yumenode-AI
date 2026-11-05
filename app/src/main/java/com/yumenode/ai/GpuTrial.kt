// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.SharedPreferences

/**
 * Marca de prueba GPU ("trial"): un SIGSEGV del driver Vulkan no se puede
 * catchar desde Kotlin, asi que el mecanismo es persistente:
 *  1. antes de cargar/generar con GPU se escribe KEY_GPU_TRIAL = true,
 *  2. tras la primera carga/generacion OK se borra,
 *  3. al arrancar, si el flag sigue puesto, el intento anterior mato el
 *     proceso: se apaga el toggle solo y se avisa.
 */
internal object GpuTrial {

    fun begin(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_GPU_TRIAL, true)
            .putLong(KEY_GPU_TRIAL_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_GPU_TRIAL).remove(KEY_GPU_TRIAL_AT).apply()
    }

    fun isTrial(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_GPU_TRIAL, false)

    /**
     * @return true si habia un trial colgado (crash): apaga el toggle,
     * borra el flag y el llamador debe avisar al usuario.
     */
    fun recoverOnStart(prefs: SharedPreferences): Boolean {
        if (!isTrial(prefs)) return false
        prefs.edit()
            .putBoolean(KEY_GPU_EXPERIMENTAL, false)
            .remove(KEY_GPU_TRIAL)
            .remove(KEY_GPU_TRIAL_AT)
            .apply()
        return true
    }
}
