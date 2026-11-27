// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * Punto de entrada de las descargas de modelos.
 *
 * Encola el trabajo en WorkManager con nombre único por modelo, de modo que:
 *  - la descarga sobrevive a que Android mate el proceso o a cerrar la app,
 *  - pedir dos veces el mismo modelo no arranca dos descargas,
 *  - se puede cancelar de forma limpia.
 *
 * El progreso en vivo (para la UI) se publica en [progress] mientras el proceso
 * está vivo; con la app cerrada manda la notificación del worker.
 */
object ModelDownloads {

    /** Última fracción (0..100) conocida por modelo, para pintar la barra al volver. */
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress

    /** Resultado final de una descarga: "ok", "cancelada" o "error:<motivo>". */
    private val _finished = MutableStateFlow<Map<String, String>>(emptyMap())
    val finished: StateFlow<Map<String, String>> = _finished

    internal fun finish(modelKey: String, status: String) {
        _finished.value = _finished.value + (modelKey to status)
        _progress.value = _progress.value - modelKey
        forget(context = null, modelKey)
    }

    /** Descargas pendientes que sobreviven al proceso. */
    private fun prefs(context: Context) =
        context.getSharedPreferences("yumenode_downloads", Context.MODE_PRIVATE)

    private fun remember(context: Context, modelKey: String) {
        val set = prefs(context).getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        set.add(modelKey)
        prefs(context).edit().putStringSet(KEY_PENDING, set).apply()
    }

    private fun forget(context: Context?, modelKey: String) {
        val ctx = context ?: appContext ?: return
        val set = prefs(ctx).getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        set.remove(modelKey)
        prefs(ctx).edit().putStringSet(KEY_PENDING, set).apply()
    }

    /**
     * Reencola lo que quedó a medias.
     *
     * WorkManager reprograma el trabajo por su cuenta, pero puede tardar
     * minutos; al abrir la app se relanza con REPLACE y el descargador sigue
     * desde el `.parts` (no se pierde nada).
     */
    fun resumePending(context: Context) {
        appContext = context.applicationContext
        val pending = prefs(context).getStringSet(KEY_PENDING, emptySet()).orEmpty().toList()
        if (pending.isEmpty()) return
        for (key in pending) {
            enqueue(context, key, key, replace = true)
        }
    }

    /** Contexto de aplicación, para poder limpiar la marca desde [finish]. */
    @Volatile
    private var appContext: Context? = null

    private fun uniqueName(modelKey: String) = "model-download:$modelKey"

    private fun tagFor(modelKey: String) = "$KEY_TAG_PREFIX$modelKey"

    /**
     * Instancia única de WorkManager.
     *
     * `getInstance()` inicializa el singleton (abre la base de datos) y es caro:
     * llamarlo desde el hilo principal por cada tarjeta del catálogo congelaba
     * la UI. Se guarda una vez por proceso.
     */
    @Volatile
    private var wm: WorkManager? = null

    private fun workManager(context: Context): WorkManager =
        wm ?: WorkManager.getInstance(context.applicationContext).also { wm = it }

    /**
     * Prepara WorkManager (abre su base de datos) fuera del hilo principal.
     * La primera llamada a `getInstance()` es cara; si ocurre al pulsar
     * «Descargar» congela la UI unos segundos.
     */
    fun warmUp(context: Context) {
        workManager(context)
    }

    fun enqueue(
        context: Context,
        modelKey: String,
        modelName: String = modelKey,
        replace: Boolean = false,
    ) {
        appContext = context.applicationContext
        remember(context, modelKey)
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL to modelKey,
                ModelDownloadWorker.KEY_NAME to modelName,
            ))
            .setConstraints(
                Constraints.Builder()
                    // Solo con red: si el usuario está sin datos, el trabajo
                    // espera en vez de fallar y perder tiempo.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // Backoff lineal y corto: el backoff exponencial por defecto deja
            // una descarga de varios GB parada minutos tras unos cuantos cortes.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            // Tag propio: permite saber qué modelo es incluso antes de que el
            // worker escriba su primer progreso.
            .addTag(tagFor(modelKey))
            .build()

        workManager(context).enqueueUniqueWork(
            uniqueName(modelKey),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context, modelKey: String) {
        workManager(context).cancelUniqueWork(uniqueName(modelKey))
        ModelDownloader.cancelActiveDownload()
        _progress.value = _progress.value - modelKey
    }

    /** Cancela todas las descargas en curso. */
    fun cancelAll(context: Context) {
        workManager(context).cancelAllWorkByTag(TAG)
        ModelDownloader.cancelActiveDownload()
        _progress.value = emptyMap()
    }

    /**
     * Estados actuales de las descargas, indexados por clave de modelo.
     * Bloquea: llamar desde IO.
     */
    fun states(context: Context): Map<String, WorkInfo> {
        val out = HashMap<String, WorkInfo>()
        for (info in workManager(context).getWorkInfosByTag(TAG).get()) {
            val key = info.tags.firstOrNull { it.startsWith(KEY_TAG_PREFIX) }
                ?.removePrefix(KEY_TAG_PREFIX) ?: continue
            out[key] = info
        }
        return out
    }

    /** Claves de los modelos con descarga en curso (consulta única, para la UI). */
    fun activeKeys(context: Context): Set<String> = runCatching {
        states(context).filterValues { !it.state.isFinished }.keys
    }.getOrDefault(emptySet())

    /** Estado del trabajo de ese modelo, como Flow (para pintar la barra en la UI). */
    fun observe(context: Context, modelKey: String): kotlinx.coroutines.flow.Flow<List<WorkInfo>> =
        workManager(context).getWorkInfosForUniqueWorkFlow(uniqueName(modelKey))

    /** true si ese modelo se está descargando ahora mismo. */
    fun isActive(context: Context, modelKey: String): Boolean = runCatching {
        workManager(context)
            .getWorkInfosForUniqueWork(uniqueName(modelKey))
            .get()
            .any { !it.state.isFinished }
    }.getOrDefault(false)

    internal fun publish(modelKey: String, pct: Int) {
        _progress.value = _progress.value + (modelKey to pct)
    }

    internal fun clear(modelKey: String) {
        _progress.value = _progress.value - modelKey
    }

    const val TAG = "model-download"
    private const val KEY_PENDING = "pending"
    private const val KEY_TAG_PREFIX = "model:"
}
