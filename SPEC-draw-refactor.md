# SPEC — Refactor MainActivity: víctima Slots de dibujo/LoRA/tagger (sin cambios de comportamiento)

**Estado:** aprobado por continuación del owner 2026-11-05 (misma onda que API, adjuntos y chat) · **Rama:** `refactor/draw-slots` · **Base:** `main` tras PR #4

## 1. Objetivo

Cuarta pieza del refactor de `MainActivity` (2.932 líneas): extraer **slots de dibujo + LoRA + tagger + modelos guardados** con contrato host. Sin cambios visibles.

## 2. No-objetivos

- Tema/locale y núcleo de la Activity: última onda.
- Cambios de comportamiento, textos, orden de listas o paths.

## 3. Estado actual verificado

| Componente | Hecho |
|---|---|
| Slots dibujo | `pickDrawSlot`, `refreshDrawSlots`, `toggleDrawSlots`, `pickDrawModelFile`, `showDrawTypeMenu`, `selectDrawSet` |
| LoRA/TAESD | `pickLoraFile`, `pickLoraSource`, `pickTaesdSource`, `startTaesdDownload`, `startLoraDownload`, `confirmDeleteLora`, `confirmDeleteDrawSet`, `refreshLoraUi` |
| Tagger | `pickTaggerOnnx`, `pickTaggerCsv`, `refreshTaggerUi` (delega a `taggerUi` ya), `refreshTaggerToggle`, `onTaggerToggleClick` |
| Modelos chat | `pickModelFile`, `refreshSavedModels`, `selectSavedModel`, `confirmDeleteModel`, `copyModelToPrivate` (si quedó en la Activity) |

## 4. Diseño

### 4.1 Nuevos archivos

| Archivo | Qué |
|---|---|
| `DrawModelsHost.kt` | Interfaz host: status/toast, intents de picks, `drawPage`/`taggerUi`, `ModelSetStore`/`LoraStore`, textos. |
| `DrawModelsController.kt` | Los métodos de arriba movidos literalmente. `MainActivity` delega. |

### 4.2 Reglas

- Movimiento **literal**, mismos paths (incl. todo lo GPU: no se toca `SPEC.md`).
- Queda en la Activity lo que no es de esta pieza (tema, navegación, tracker de API).
- Un commit por paso; build+tests verdes tras cada paso.

## 5. Tickets

| # | Ticket | Criterio |
|---|---|---|
| T0 | Baseline verde | `assembleDebug` + `test` (299) verdes |
| T1 | `DrawModelsHost` + `DrawModelsController`, delegación | Solo movimiento |
| T2 | Tests de lógica pura extraíble (si la hay) | Total ≥ 299, 0 fallos |
| T3 | QA del diff + verificación final | MainActivity baja; todo verde |

---
**Gate:** cubierto por la continuación aprobada por el owner en API, adjuntos y chat.
