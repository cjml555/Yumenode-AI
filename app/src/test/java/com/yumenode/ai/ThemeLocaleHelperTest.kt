// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [barLuminance] (ThemeLocaleHelper, extraído de MainActivity).
 * Lógica pura sin Android: corre en JVM sin Robolectric.
 * La constante del umbral en barras de sistema es 0.55f.
 */
class ThemeLocaleHelperTest {

    @Test
    fun blackIsZero() {
        assertEquals(0f, barLuminance(0xFF000000.toInt()), 1e-6f)
    }

    @Test
    fun whiteIsOne() {
        assertEquals(1f, barLuminance(0xFFFFFFFF.toInt()), 1e-6f)
    }

    @Test
    fun primaryRedMatchesWeights() {
        // Solo rojo: 0.299 en el cálculo
        assertEquals(0.299f, barLuminance(0xFFFF0000.toInt()), 1e-6f)
    }

    @Test
    fun primaryGreenMatchesWeights() {
        assertEquals(0.587f, barLuminance(0xFF00FF00.toInt()), 1e-6f)
    }

    @Test
    fun primaryBlueMatchesWeights() {
        assertEquals(0.114f, barLuminance(0xFF0000FF.toInt()), 1e-6f)
    }

    @Test
    fun alphaIsIgnored() {
        assertEquals(barLuminance(0xFF808080.toInt()), barLuminance(0x00808080), 1e-6f)
    }

    @Test
    fun midGreyIsNearHalf() {
        assertEquals(0.5f, barLuminance(0xFF808080.toInt()), 0.01f)
    }

    @Test
    fun thresholdSideIsConsistent() {
        // El umbral 0.55f decide iconos claros: blanco pasa, negro no
        assertTrue(barLuminance(0xFFFFFFFF.toInt()) > 0.55f)
        assertTrue(barLuminance(0xFF000000.toInt()) <= 0.55f)
        // Un gris medio claro (~0.78) también pasa
        assertTrue(barLuminance(0xFFC0C0C0.toInt()) > 0.55f)
    }
}
