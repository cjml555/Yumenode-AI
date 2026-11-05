# SPEC — Refactor MainActivity: pieza Sesiones de chat (sin cambios de comportamiento)

**Estado:** aprobado por continuación del owner 2026-11-05 (misma onda que API y adjuntos) · **Rama:** `refactor/chat-sesiones` · **Base:** `main` tras PR #3

## 1. Objetivo

Tercera pieza del refactor de `MainActivity` (3.201 líneas): extraer **sesiones y render del chat** con contrato host, mismo patrón (`ApiHost`, `AttachHost`). Sin cambios visibles.

## 2. No-objetivos

- Slots de dibujo, tema/locale, adjuntos, API: otra onda (API/attach ya fusionados).
- Cambios de comportamiento, textos, orden de mensajes o layout.

## 3. Estado actual verificado

| Componente | Hecho |
|---|---|
| Sesiones | `newSession`, `switchTo`, `confirmDeleteSession`, `rebuildConversation`, `restoreSession` |
| Turnos/render | `regenerate`, `renderTurn`, `addAiArea`, `addUserBubble`, `addSystemHint` |
| Helpers | `extractText`, `mergeStreamDelta` (delegan a `ChatCore`), busy/estado (`setStoppingUi`, `applyBusy` si son de chat) |
| Orden | Se mueve **después** de adjuntos porque `addAiArea`/`addUserBubble` reciben imágenes/archivos adjuntos |

## 4. Diseño

### 4.1 Nuevos archivos

| Archivo | Qué |
|---|---|
| `ChatSessionHost.kt` | Interfaz host: vistas de chat (AI areas, contenedores), status/toast, `ChatCore`/ChatManager, textos. |
| `ChatSessionController.kt` | Los métodos de sesiones/render movidos literalmente. `MainActivity` delega. |

### 4.2 Reglas

- Movimiento **literal**, mismos orden de mensajes, IDs de vistas y textos.
- `ChatCore` ya instrumentado: no duplicar lógica de texto/stream.
- Un commit por paso; build+tests verdes tras cada paso.

## 5. Tickets

| # | Ticket | Criterio |
|---|---|---|
| T0 | Baseline verde | `assembleDebug` + `test` (290) verdes |
| T1 | `ChatSessionHost` + `ChatSessionController`, delegación en MainActivity | Solo movimiento |
| T2 | Tests de lógica pura extraíble (si la hay) | Total ≥ 290, 0 fallos |
| T3 | QA del diff + verificación final | MainActivity baja; todo verde |

---
**Gate:** cubierto por la continuación aprobada por el owner tras API y adjuntos.
