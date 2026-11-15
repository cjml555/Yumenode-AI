// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.ActivityManager
import android.content.Context
import net.ladenthin.llama.args.CacheType

/**
 * Tipo de cuantización del cache KV de llama.cpp (solo ruta GGUF).
 *
 * El cache K/V guarda lo que el modelo "recuerda" del contexto. En F16
 * ocupa el doble de RAM que en Q8_0, y en Q4_0 la cuarta parte, sin que se
 * note en la calidad de la respuesta (Q8_0 es la recomendacion habitual de
 * llama.cpp; el server de llama.cpp usa q8_0 por defecto para K y V).
 *
 * Importa mucho en un movil: con 32k de contexto, un modelo de 2B se lleva
 * varios GB solo de cache. Bajar de F16 a Q8_0 libera la mitad.
 *
 * El tipo se fija al CARGAR el modelo (es un parametro del contexto), asi
 * que cambiarlo solo afecta a la siguiente carga.
 */
internal object KvCacheConfig {

    private const val PREFS = "ponko"
    private const val KEY_CACHE_TYPE = "kvCacheType"

    /** Por debajo de esta RAM total, el cache KV default a Q4_0 para salvar VRAM. */
    private const val LOW_RAM_BYTES = 6L * 1024 * 1024 * 1024

    /** Default por RAM total del dispositivo (sin override del usuario). */
    internal fun defaultFor(totalMemBytes: Long): CacheType =
        if (totalMemBytes in 1 until LOW_RAM_BYTES) CacheType.Q4_0 else CacheType.Q8_0

    /** Opciones ofrecidas, de mas a menos calidad (y de menos a mas RAM). */
    val OPTIONS: List<CacheType> = listOf(
        CacheType.Q8_0,
        CacheType.Q4_0,
        CacheType.F16,
    )

    /** Etiqueta legible de cada tipo. */
    fun label(type: CacheType): String = when (type) {
        CacheType.Q8_0 -> "Q8_0"
        CacheType.Q4_0 -> "Q4_0"
        CacheType.F16 -> "F16"
        else -> type.name
    }

    /** Descripcion corta del ahorro de RAM, para el texto de ayuda. */
    fun hint(type: CacheType): String = when (type) {
        CacheType.Q8_0 -> "Q8_0 · half the RAM of F16, no visible quality loss"
        CacheType.Q4_0 -> "Q4_0 · a quarter of F16, slight quality loss"
        CacheType.F16 -> "F16 · not quantized, most RAM"
        else -> ""
    }

    /** Tipo configurado (por defecto según RAM del dispositivo). */
    fun type(context: Context): CacheType {
        prefs(context).getString(KEY_CACHE_TYPE, null)?.let { name ->
            return OPTIONS.firstOrNull { it.name == name } ?: CacheType.Q8_0
        }
        return defaultFor(totalRam(context))
    }

    /** Guarda el tipo elegido. */
    fun setType(context: Context, type: CacheType) {
        prefs(context).edit().putString(KEY_CACHE_TYPE, type.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun totalRam(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }
}
