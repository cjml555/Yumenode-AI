# SPEC — Refactor MainActivity: víctima Tema/Locale (sin cambios de comportamiento)

**Estado:** aprobado por continuación del owner 2026-11-05 (misma onda que API, adjuntos, chat y dibujo) · **Rama:** `refactor/tema-locale` · **Base:** `main` tras PR #5

## 1. Objetivo

Última pieza del refactor de `MainActivity` (2.465 líneas): extraer **tema + locale + barras de sistema** con patrón host. Sin cambios visibles. Tras esto, la Activity queda solo como shell de navegación.

## 2. No-objetivos

- Nuevas piezas/funcionalidades; queda fuera lo que resulte inseparable del shell.
- Cambios de comportamiento, textos o layout.

## 3. Estado actual verificado

| Componente | Hecho |
|---|---|
| Tema | `applyThemeInPlace`, `applySystemBarColors`, `barLuminance`, `ThemeManager`/`YumenodeTheme` (ya hay archivos propios) |
| Locale | `currentAppLocale33`, `applyAppLocale33` |
| Crash | `installCrashHandler`, `takeCrashLog` (`CrashHandler.kt` existe ya) |
| Navegación (queda) | `buildUi`, páginas, `switchTab`, drawer global |

## 4. Diseño

### 4.1 Nuevos archivos

| Archivo | Qué |
|---|---|
| `ThemeLocaleHost.kt` | Interfaz host mínima (vistes raíz, activity) — si no hace falta contrato, funciones top-level internas. |
| `ThemeLocaleHelper.kt` | Tema + locale + barras de sistema movidos literalmente. `ThemeManager`/`YumenodeTheme` no se duplican. |

### 4.2 Reglas

- Movimiento **literal**, mismos colores, textos y LocaleTags (`33+` mencionados en los nombres).
- Si alguna pieza no sale limpia, se deja en la Activity y se documenta en el SPEC/PR (no se fuerza).
- Un commit por paso; build+tests verdes tras cada paso.

## 5. Tickets

| # | Ticket | Criterio |
|---|---|---|
| T0 | Baseline verde | `assembleDebug` + `test` (299) verdes |
| T1 | `ThemeLocaleHost` (si aplica) + helper, delegación | Solo movimiento |
| T2 | Tests de lógica pura extraíble (si la hay) | Total ≥ 299, 0 fallos |
| T3 | QA del diff + verificación final | MainActivity baja; todo verde |

---
**Gate:** cubierto por la continuación aprobada por el owner en las cuatro piezas anteriores.
