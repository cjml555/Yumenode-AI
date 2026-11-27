// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Aplicación: solo para dar a WorkManager una configuración bajo demanda.
 *
 * La auto-inicialización de WorkManager (androidx.startup) abre su base de
 * datos en el hilo principal al arrancar; en este equipo eso dejaba la UI sin
 * responder más de 10 s (ANR al cambiar de pestaña). Se desactiva en el
 * manifiesto y se inicializa aquí, la primera vez que se usa.
 */
class YumenodeApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
