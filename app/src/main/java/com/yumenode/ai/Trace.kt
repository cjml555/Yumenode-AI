// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import android.os.SystemClock
import android.util.Log

/**
 * Marcas de tiempo del hilo principal para diagnosticar bloqueos con `logcat`.
 *
 * Antes había aquí un detector de ANR que volcaba la pila del hilo principal
 * desde un hilo de fondo. **Se retiró porque el propio volcado bloqueaba el hilo
 * principal** (`No such thread for suspend: …:main` en ART): el instrumento
 * causaba el síntoma que pretendía medir. Para medir bloqueos usa el contador
 * del sistema (`adb logcat -d | grep -c "ANR in com.yumenode"`).
 */
object Trace {
    private const val TAG = "YumeTrace"

    fun tlog(msg: String) = Log.d(TAG, "${SystemClock.uptimeMillis()} $msg")
}
