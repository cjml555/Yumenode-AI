# SPEC — Refactor MainActivity: víctima Attach/Archivos (sin cambios de comportamiento)

**Estado:** aprobado por continuación del owner 2026-11-05 (misma onda que la piloto API) · **Rama:** `refactor/attach-archivos` · **Base:** `main` tras PR #2 (merge de la piloto)

## 1. Objetivo

Segunda pieza del refactor de `MainActivity` (3.380 líneas tras la piloto): extraer **adjuntos/archivos** con contrato host, mismo patrón (`ApiHost`/`ApiController`). Sin cambios visibles.

## 2. No-objetivos

- Sesiones de chat, slots de dibujo, tema/locale: otra onda.
- Cambios de comportamiento, textos, permisos o intents.

## 3. Estado actual verificado

| Componente | Hecho |
|---|---|
| Drawer de adjuntar | `onAttachClick`, `showAttachDrawer` (con `mkItem`/`close`), `probeVision` (~líneas 1487–1573 en main original) |
| Cámara | `withLegacyStorage`, `takePhoto`/`doTakePhoto`, `handleTakePhotoResult`, `bitmapToJpeg` |
| Picks de archivos | `resultUris`, `handlePickedChatImages`, `handlePickedFiles`, `onActivityResult` (resto: modelos/LoRA/tagger en otra onda), `copyModelToPrivate` |
| Conceptos existentes | `AttachmentHandler.kt`, `VisionManager` (ya hay host externo) |

## 4. Diseño

### 4.1 Nuevos archivos

| Archivo | Qué |
|---|---|
| `AttachHost.kt` | Interfaz host: status/toast, permiso cámara, `chatPrefs`, textos, accesos a `VisionManager`/chat. |
| `AttachController.kt` | Los ~10 métodos de adjuntos/archivos movidos literalmente. `MainActivity` delega. |

### 4.2 Reglas

- Movimiento **literal**, mismos request codes, intents, permisos y mensajes.
- `onActivityResult` decide la delegación: rutas de adjuntos al controller, resto queda en la Activity.
- Un commit por paso; build+tests verdes tras cada paso; ningún commit deja el build roto.

## 5. Tickets

| # | Ticket | Criterio |
|---|---|---|
| T0 | Baseline verde en la rama | `assembleDebug` + `test` verdes |
| T1 | `AttachHost` + `AttachController`, MainActivity delega | Solo movimiento; sin cambio de comportamiento |
| T2 | Tests de lógica pura extraíble (si la hay) | Total ≥ 287, 0 fallos |
| T3 | QA del diff + verificación final | MainActivity baja; todo verde |

---
**Gate:** cubierto por la continuación aprobada por el owner tras la piloto API.
