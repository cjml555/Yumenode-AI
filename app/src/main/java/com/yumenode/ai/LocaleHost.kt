// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.res.Configuration
import android.view.View
import kotlinx.coroutines.CoroutineScope

/**
 * Contrato mínimo que [LocaleController] necesita de su host (MainActivity).
 *
 * Solo lo imprescindible para la lógica de idioma y tema.
 */
interface LocaleHost {

    /** Activity para recursos y configuración. */
    val activity: android.app.Activity

    /** Administrador de tema compartido. */
    val themeManager: ThemeManager

    /** Preferencias de chat (SharedPreferences "ponko"). */
    fun chatPrefs(): android.content.SharedPreferences

    /** Scope de corrutinas. */
    val scope: CoroutineScope

    /** Aplicar tema en el lugar (reconstruye UI, drawerOpen, etc.). */
    fun applyThemeInPlace()

    /** Mostrar toast. */
    fun toast(msg: String)

    /** Obtener string por id. */
    fun getString(id: Int, vararg args: Any?): String
}