# SPEC — Refactor MainActivity: piloto API (sin cambios de comportamiento)

**Estado:** borrador para gate del owner · **Rama:** `refactor/mainactivity-api` (a crear tras aprobar) · **Base:** `main` @ `65819c1`

## 1. Objetivo

Partir `MainActivity.kt` (3.464 líneas) en pieza extraíbles con contrato host, validando el patrón con **una piloto (bloque API)** antes de tocar el resto. Sin cambios visibles: mismos textos, orden, toggles y comportamiento.

## 2. No-objetivos (esta iteración)

- UI fuera del bloque API (chat, dibujo, drawers): otra iteración.
- Cambios de comportamiento, textos o layout.
- Nuevas dependencias ni features.

## 3. Estado actual verificado

| Componente | Hecho |
|---|---|
| Bloque API build | Ya extraído: `ApiBlockBuilder.kt` (`ApiBlockView`). Queda en MainActivity la **lógica**: `refreshApiBlock`/`refreshApiUi`/`refreshDrawApiUi`/`refreshApiSurfaces`, `copyApiAddress`, `newApiKey`, `clearApiKey`, `afterApiConfigChanged`, `toggleApi`, `apiPort` (~120 líneas, líneas 1053–1163). |
| Patrón de hosts | El repo ya extrae con contratos: `InputBarHost`, `ModelsHost`, `SettingsHost`. `ModelsHost` ya declara `onRefreshApiUi`/`onRefreshDrawApiUi`/`onBuildApiBlock`. |
| Red de seguridad | 281 tests JVM en verde + `assembleDebug`. Compilación con ccache: ~1 min tras rebuild limpia. |

## 4. Diseño

### 4.1 Nuevos archivos

| Archivo | Qué |
|---|---|
| `ApiHost.kt` | Interfaz con lo que el controller necesita de la Activity: `setStatus`, `toast`, `chatPrefs`, textos (por método, no `getString` en controller puro) y accesos a `ApiServer`. |
| `ApiController.kt` | Los 9 métodos de lógica API movidos tal cual; reciben `ApiHost`. |

### 4.2 Lo que NO se mueve

- `buildApiBlock(...)` (ya está en `ApiBlockBuilder.kt`): solo cambia el delegante.
- `ModelsHost` (sigue existiendo): sus métodos API delegan ahora al controller vía la Activity.

### 4.3 Reglas

- Movimiento **literal** de código (mismos nombres, textos, claves de prefs: `"ponko"`, puertos, roles `ApiServer.Role`).
- Todo cambio de API externa de `MainActivity` que se mueva = renombrar callers en el mismo commit; ningún commit deja el build roto.
- Un commit por paso; tras cada paso `assembleDebug` + `test` verdes.

## 5. Tickets

| # | Ticket | Criterio |
|---|---|---|
| T0 | Baseline en verde en la rama | `assembleDebug` + `test` (281) verdes antes de tocar nada |
| T1 | `ApiHost` + `ApiController` extraídos, `MainActivity` delega | Diff solo mueve código; sin cambios de comportamiento |
| T2 | Tests de la lógica pura extraíble (puertos/claves/prefs de API) | 0 fallos; total ≥ 281 |
| T3 | QA diff: verificación línea a línea del diff + `assembleDebug`/`test` final | `MainActivity` < 3.464 líneas; todo verde |

## 6. Iteraciones siguientes si la piloto funciona

1. **Attach/archivos** (`AttachController`): drawer, foto, picks, `copyModelToPrivate`.
2. **Sesiones de chat** (`ChatController`): sesiones, burbujas, render de turnos.
3. **Slots de dibujo/LoRA/tagger** (`DrawModelsController`): picks, listas, descargas.
4. **Tema/locale** (`ThemeHelper` + `LocaleHelper`): líneas ~138–790.

---
**Gate:** el owner aprueba o pide cambios antes de crear la rama y repartir los tickets.
