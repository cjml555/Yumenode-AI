# SPEC — GPU experimental opt-in (Vulkan) con caída a CPU

**Estado:** ✅ APROBADO por el owner 2026-11-05 — activable con aviso fuerte en Adreno 619, AAR Vulkan en el APK por defecto, sin prueba en dispositivo en esta iteración · **Rama:** `feat/gpu-experimental` · **Base:** `main` @ `8eb2fac` (v1.4.0)

## 1. Objetivo

Que el usuario pueda activar aceleración GPU Vulkan para **chat (GGUF)** y **dibujo (sd.cpp)**, sabiendo que:

- está **apagado por defecto**,
- solo se ofrece en dispositivos con driver Vulkan,
- si el driver crashea, la app **vuelve sola a CPU** en el siguiente arranque,
- en el Edge 50 Fusion (Adreno 619) el camino sirve para **verificar el crash**, no para prometer velocidad.

## 2. No-objetivos (esta iteración)

- OpenCL (rama `feat/gpu-opencl`): crashea en `libllvm-qcom.so`; no se toca.
- GPU para LiteRT-LM (`.litertlm`): sigue por su propio camino.
- Multi-GPU, selector de dispositivo, perfiles de capas: no.

## 3. Estado actual verificado

| Componente | Hecho |
|---|---|
| Chat GGUF | AAR oficial `net.ladenthin:llama-android:5.1.0` **solo CPU** (`app/build.gradle.kts:107`). El AAR Vulkan local se retiró en `2530fd4` por fallar en Adreno. |
| Flag | `GpuSupport.GGUF_GPU_EXPERIMENTAL = false` (const). `ModelLoadDecision.gpuLayers()` devuelve 0 sin el flag: pedir "GPU" hoy no hace nada, solo avisa. |
| Dibujo | `SD_VULKAN ON` en CMake; `SdCppEngine.vulkanDevice()` enumera `Vulkan0`; `DrawPage` cae a CPU si no hay device o falla el init. |
| Crash conocido | Chat: SIGSEGV con BDA no resuelto (parche `0001-...bda.patch` + dispatcher). Dibujo: SIGSEGV en `ggml_backend_buft_alloc_buffer` al **generar**, no al cargar. **Un SIGSEGV no se puede catchar desde Kotlin.** |
| Reproducibilidad | `tools/build-llama-vulkan.sh` recompila `libjllama.so` con Vulkan (llama.cpp b10682, parches `0001`/`0002`). |
| UI | Settings ya muestra ficha del dispositivo (`DeviceProbe`: SoC, Vulkan, GLES, ABI). |

## 4. Diseño

### 4.1 Switch opt-in persistido (default OFF)

- Nuevo toggle en Settings → *Dispositivo*: «GPU experimental (Vulkan)», con aviso de riesgo (cierre de la app si el driver falla).
- Persiste en `SharedPreferences` (`KEY_GPU_EXPERIMENTAL`), default `false`.
- `GpuSupport` deja de leer una const y lee el setting; `ModelLoadDecision` recibe `gpuAllowed` como parámetro (ya lo hace: es el 2º argumento).

### 4.2 Puerta por dispositivo (antes de ofrecer GPU) — decisión owner: activable siempre

- Si `DeviceProbe` no ve Vulkan (`vulkanLabel` vacío / sin `Vulkan0`), el toggle se muestra **deshabilitado** con nota «sin Vulkan en este dispositivo».
- En Adreno 619 / SM6450 el toggle **queda activable** (decisión del owner): se muestra aviso fuerte de riesgo —el driver de Qualcomm puede cerrar la app— y se confía en el mecanismo §4.3 para volver a CPU. Sin bloqueo por SoC.

### 4.3 Caída automática tras crash (el mecanismo clave)

Los SIGSEGV no se catchan, así que el sistema es **persistente**:

1. Antes de cargar/generar con GPU: escribir `KEY_GPU_TRIAL = true` + timestamp.
2. Al completar la **primera** carga generacion con éxito: borrar `KEY_GPU_TRIAL`.
3. Al arrancar la app: si `KEY_GPU_TRIAL` sigue puesto → el intento anterior crasheó → **apagar el toggle solo**, mostrar aviso «GPU desactivada tras un cierre inesperado» y seguir en CPU.

Coste: ~1 pref + ~30 líneas. Es lo único que hace el opt-in seguro en un driver que mata el proceso.

### 4.4 Chat GGUF

- Recompilar el AAR Vulkan con `tools/build-llama-vulkan.sh` (NDK 29 ya instalado; actualizar `NDK=` por defecto del script, hoy apunta a 28.2) y ponerlo en `local-maven/`.
- `app/build.gradle.kts`: dependencia al AAR local **por defecto** (decisión del owner): el APK ya trae Vulkan siempre; el toggle decide si se usa. Se acepta que el APK crece.
- Capas GPU: reutilizar `GPU_LAYERS_ALL` existente.

### 4.5 Dibujo

- `DrawPage` ya cae a CPU si falla el init; añadir el mismo `KEY_GPU_TRIAL` antes de generar con `Vulkan0` y limpiarlo al terminar la primera imagen OK.
- Etiqueta de estado ya muestra el backend real (`CPU` / `Vulkan0`): no se afirma GPU si no corrió en GPU (mismo criterio que `actuallyOnGpu`).

### 4.6 Tests (lógica pura, sin device)

- `ModelLoadDecisionTest`: casos con `gpuAllowed` desde setting (ya existen los de const; añadir los del setting).
- Nuevo `GpuTrialFlagTest`: el flag se pone antes, se borra tras éxito, y al arrancar con flag puesto se desactiva el toggle.
- **Definición de hecho:** `assembleDebug` + `test` en verde, 273 tests + los nuevos.

## 5. Tickets

| # | Ticket | Toca | Criterio de aceptación |
|---|---|---|---|
| T1 | Setting + toggle en Settings, default OFF | `SettingsPage`, `Constants`, `strings` | Toggle visible, default OFF tras instalación limpia |
| T2 | `GpuSupport` lee setting en vez de const; puerta Vulkan sin device | `GpuSupport`, `DeviceProbe` | Sin Vulkan → toggle deshabilitado con nota |
| T3 | Marca de trial + auto-apagado al arrancar | `MainActivity` (carga) + `DrawPage` (generar) | Crash simulado con flag puesto → al reabrir, GPU OFF + aviso |
| T4 | AAR Vulkan en el APK por defecto | `build-llama-vulkan.sh`, `app/build.gradle.kts`, `local-maven/` | `assembleDebug` compila con el AAR Vulkan |
| T5 | Tests T1–T3 | `app/src/test` | 0 fallos |
| T6 | Docs (QA en dispositivo diferida por el owner) | `README.md` limitaciones, `CHANGELOG.md` | Cambio documentado; sin probar en device esta iteración |

## 6. Riesgos

- **Adreno 619:** se espera crash en el primer uso; el mecanismo §4.3 lo devuelve a CPU, pero hay que probarlo en dispositivo real, no por el apellido.
- **APK crece** con el AAR Vulkan (~decenas de MB): aceptado por el owner (§4.4).
- **BDA/dispatcher:** el parche `0001` ya cubre el SIGSEGV en 0x0; el resto (shaders del driver) es preexistente y no se promete arreglar.

## 8. Decisiones tomadas (gate 2026-11-05)

1. Toggle **activable** en Adreno 619 con aviso fuerte (sin bloqueo por SoC) — ver §4.2.
2. AAR Vulkan **en el APK por defecto** (sin flag de build) — ver §4.4.
3. **Sin prueba en dispositivo** esta iteración; QA diferida (T6 = solo docs).

---
**Gate:** el owner aprueba o pide cambios a este SPEC antes de crear la rama y repartir tickets.
