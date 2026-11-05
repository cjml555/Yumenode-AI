// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Contrato de prefs/puertos que usa ApiController.apiPort (extraído en el
 * piloto API): cada rol tiene su clave de puerto/token/switch y su puerto
 * por defecto, y los dos roles nunca se pisan.
 *
 * Lo que queda fuera: el coerceIn(1024, 65535) de apiPort sobre el valor
 * guardado necesita SharedPreferences real (sin Robolectric en el proyecto),
 * y ApiController necesita Activity para el resto — eso es instrumentado.
 */
class ApiServicePrefsTest {

    @Test
    fun chatPortKeyAndDefault() {
        assertEquals("apiPort", ApiService.keyPort(ApiServer.Role.CHAT))
        assertEquals(8080, ApiService.defaultPort(ApiServer.Role.CHAT))
    }

    @Test
    fun drawPortKeyAndDefault() {
        assertEquals("drawApiPort", ApiService.keyPort(ApiServer.Role.DRAW))
        assertEquals(8081, ApiService.defaultPort(ApiServer.Role.DRAW))
    }

    @Test
    fun tokenKeysArePerRole() {
        assertEquals("apiKey", ApiService.keyToken(ApiServer.Role.CHAT))
        assertEquals("drawApiKey", ApiService.keyToken(ApiServer.Role.DRAW))
    }

    @Test
    fun enabledKeysArePerRole() {
        assertEquals("apiEnabled", ApiService.keyEnabled(ApiServer.Role.CHAT))
        assertEquals("drawApiEnabled", ApiService.keyEnabled(ApiServer.Role.DRAW))
    }

    @Test
    fun rolesNeverSharePrefKeysOrPorts() {
        val chat = ApiServer.Role.CHAT
        val draw = ApiServer.Role.DRAW
        assertNotEquals(ApiService.keyPort(chat), ApiService.keyPort(draw))
        assertNotEquals(ApiService.keyToken(chat), ApiService.keyToken(draw))
        assertNotEquals(ApiService.keyEnabled(chat), ApiService.keyEnabled(draw))
        assertNotEquals(ApiService.defaultPort(chat), ApiService.defaultPort(draw))
    }

    @Test
    fun defaultPortsAreInsideApiPortRange() {
        for (role in ApiServer.Role.values()) {
            val port = ApiService.defaultPort(role)
            assert(port in 1024..65535) { "puerto por defecto fuera de rango para $role" }
        }
    }
}
