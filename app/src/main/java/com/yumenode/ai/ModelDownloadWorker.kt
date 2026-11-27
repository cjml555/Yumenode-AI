// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Descarga de un modelo en segundo plano con WorkManager.
 *
 * La diferencia con lanzar la descarga desde la UI es que aquí Android puede
 * relanzar el trabajo si mata el proceso (app cerrada, poca memoria, reinicio):
 * `ModelDownloader.downloadBlocking` reanuda desde el `.parts` y no se repite
 * el fichero entero.
 *
 * Va como *foreground work* mientras dura, para que el sistema no la pare a los
 * pocos minutos y para que el usuario vea el progreso con la app cerrada.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val modelKey = inputData.getString(KEY_MODEL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Falta KEY_MODEL"))
        val modelName = inputData.getString(KEY_NAME) ?: modelKey

        return try {
            // Android 12+ prohíbe arrancar un servicio en primer plano cuando el
            // trabajo se reanuda en segundo plano: si falla NO es fatal, se sigue
            // descargando y el sistema podrá pararla (al reabrir la app se
            // reanuda desde el `.parts` en vez de empezar de cero).
            runCatching { setForeground(foregroundInfo(modelName, 0, "Preparando…")) }
                .onFailure { Log.w(TAG, "Foreground no permitido ahora: ${it.message}") }
            setProgress(workDataOf(KEY_MODEL to modelKey, KEY_PROGRESS to 0))

            // El callback llega muchas veces por segundo. Escribir cada vez en la
            // base de datos de WorkManager (setProgress) y notificar satura el
            // hilo principal: basta con avisar al cambiar de 1 % y cada 400 ms.
            var lastPct = -1
            var lastAt = 0L
            val result = ModelDownloader.downloadBlocking(modelKey, applicationContext) { p ->
                val pct = (p.progress * 100).toInt().coerceIn(0, 100)
                val now = System.currentTimeMillis()
                // La notificación solo cada 5 %: cada punto suspende al sistema
                // (binder) y con la descarga a tope se nota en la UI.
                val step = pct / 5
                val lastStep = lastPct / 5
                if (step == lastStep && now - lastAt < 1500L) return@downloadBlocking
                lastPct = pct
                lastAt = now
                val speed = Formatters.speed(p.speedBytesPerSec)
                val eta = p.etaSeconds?.takeIf { it > 0 }?.let { " · ETA ${Formatters.eta(it)}" } ?: ""
                // El callback de progreso no es suspend: se usa la variante async.
                setProgressAsync(workDataOf(KEY_MODEL to modelKey, KEY_PROGRESS to pct))
                ModelDownloads.publish(modelKey, pct)
                // Misma notificación que la del servicio en primer plano: se
                // actualiza en sitio y no se duplica.
                DownloadNotifier.progress(applicationContext, modelName, pct, "$pct% · $speed$eta")
            }

            when (result) {
                is ModelDownloader.DownloadResult.Success -> {
                    Log.i(TAG, "Descarga completa: ${result.modelPath}")
                    ModelDownloads.finish(modelKey, "ok")
                    Result.success(workDataOf(KEY_PATH to result.modelPath))
                }
                is ModelDownloader.DownloadResult.Cancelled -> {
                    ModelDownloads.finish(modelKey, "cancelada")
                    Result.failure(workDataOf(KEY_ERROR to "cancelada"))
                }
                // Sin red: se reintenta indefinidamente (con backoff corto). La
                // descarga reanuda desde el `.parts`, así que reintentar sale
                // casi gratis; es peor rendirse y perder el avance.
                is ModelDownloader.DownloadResult.NetworkError -> Result.retry()
                // Fallo determinista (404, checksum, catálogo): reintentar no
                // arregla nada.
                is ModelDownloader.DownloadResult.Failure -> {
                    ModelDownloads.finish(modelKey, "error:${result.error}")
                    Result.failure(workDataOf(KEY_ERROR to result.error))
                }
            }
        } catch (e: Throwable) {
            if (isStopped) Result.failure(workDataOf(KEY_ERROR to "detenida"))
            else {
                Log.w(TAG, "Fallo en el intento #$runAttemptCount", e)
                Result.retry()
            }
        }
    }

    private fun foregroundInfo(modelName: String, pct: Int, detail: String) = androidx.work.ForegroundInfo(
        DownloadNotifier.NOTIF_ID,
        DownloadNotifier.ongoing(applicationContext, modelName, pct, detail),
        if (android.os.Build.VERSION.SDK_INT >= 29) FOREGROUND_TYPE else 0,
    )

    companion object {
        const val TAG = "YumenodeDownload"
        const val KEY_MODEL = "model_key"
        const val KEY_NAME = "model_name"
        const val KEY_PROGRESS = "pct"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"

        /** Reintentos propios además del backoff de WorkManager. */
        const val MAX_ATTEMPTS = 3

        /** Android 14+ exige declarar el tipo de servicio en primer plano. */
        private const val FOREGROUND_TYPE =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
