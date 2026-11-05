// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Request codes del bloque de adjuntos/archivos (extraído a AttachController):
 * cámara, permiso de almacenamiento legacy y picks de chat en AttachmentHandler.
 * Deben ser estables: cambian el contrato con onActivityResult/onRequestPermissionsResult.
 *
 * Lo que queda fuera: el resto del controller necesita Activity, vistas,
 * intents y MediaStore — eso es instrumentado.
 */
class AttachRequestCodesTest {

    @Test
    fun cameraAndStorageCodes() {
        assertEquals(1006, REQ_TAKE_PHOTO)
        assertEquals(1009, REQ_PERM_STORAGE)
    }

    @Test
    fun chatPickCodes() {
        assertEquals(1007, AttachmentHandler.REQ_PICK_CHAT_IMAGE)
        assertEquals(1010, AttachmentHandler.REQ_PICK_FILE)
    }

    @Test
    fun attachCodesAreDistinct() {
        val codes = setOf(
            REQ_TAKE_PHOTO, REQ_PERM_STORAGE,
            AttachmentHandler.REQ_PICK_CHAT_IMAGE, AttachmentHandler.REQ_PICK_FILE,
        )
        assertEquals(4, codes.size)
        assertNotEquals(REQ_TAKE_PHOTO, REQ_PERM_STORAGE)
    }
}
