// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import com.yumenode.ai.draw.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La marca de trial GPU: se pone antes de cargar/generar con GPU, se borra
 * tras el exito, y al arrancar con el flag puesto se apaga el toggle solo.
 */
class GpuTrialFlagTest {

    @Test fun trial_is_not_set_by_default() {
        assertFalse(GpuTrial.isTrial(FakePrefs()))
    }

    @Test fun begin_sets_the_flag() {
        val prefs = FakePrefs()
        GpuTrial.begin(prefs)
        assertTrue(GpuTrial.isTrial(prefs))
    }

    @Test fun clear_removes_the_flag() {
        val prefs = FakePrefs()
        GpuTrial.begin(prefs)
        GpuTrial.clear(prefs)
        assertFalse(GpuTrial.isTrial(prefs))
    }

    @Test fun startup_without_trial_keeps_the_toggle() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(KEY_GPU_EXPERIMENTAL, true).apply()
        assertFalse(GpuTrial.recoverOnStart(prefs))
        assertTrue(GpuSupport.gpuAllowed(prefs))
    }

    @Test fun startup_with_trial_turns_the_toggle_off() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(KEY_GPU_EXPERIMENTAL, true).apply()
        GpuTrial.begin(prefs)
        assertTrue(GpuTrial.recoverOnStart(prefs))
        assertFalse(GpuSupport.gpuAllowed(prefs))
        assertFalse(GpuTrial.isTrial(prefs))
    }

    @Test fun startup_with_trial_and_toggle_already_off_still_clears_flag() {
        val prefs = FakePrefs()
        GpuTrial.begin(prefs)
        assertTrue(GpuTrial.recoverOnStart(prefs))
        assertFalse(GpuSupport.gpuAllowed(prefs))
        assertFalse(GpuTrial.isTrial(prefs))
    }

    @Test fun gpu_allowed_defaults_to_false() {
        assertFalse(GpuSupport.gpuAllowed(FakePrefs()))
    }

    @Test fun gpu_allowed_reads_the_setting() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(KEY_GPU_EXPERIMENTAL, true).apply()
        assertTrue(GpuSupport.gpuAllowed(prefs))
        assertEquals(GpuSupport.GPU_LAYERS_ALL, ModelLoadDecision.gpuLayers("GPU", GpuSupport.gpuAllowed(prefs)))
    }
}
