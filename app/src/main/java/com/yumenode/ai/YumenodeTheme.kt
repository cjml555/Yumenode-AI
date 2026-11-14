// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import android.content.res.Configuration

/**
 * App color palette: light / dark sets.
 *
 * All colors in the UI come from here (MainActivity's C_* and DrawPage's pal),
 * so when adding a widget don't hardcode colors, or dark mode will leave white blocks.
 *
 * Theme mode is stored in the ponko pref: 0=follow system 1=light 2=dark (Activity is recreated on change).
 */
data class YumenodeTheme(
    val bg: Int,             // page / scroll area background
    val bgAlt: Int,          // page background (slightly darker)
    val surface: Int,        // cards, top bar, input bar etc. "white" surfaces
    val fieldBg: Int,        // input fields, preview areas etc. recessed surfaces
    val softBtn: Int,        // secondary buttons / thumbnail background
    val softBtn2: Int,       // secondary buttons (outlined variant)
    val border: Int,         // stroke / border
    val chipBg: Int,         // background of unselected entries
    val divider: Int,        // thin divider inside cards (semi-transparent)
    val dividerLine: Int,    // solid 1px divider (drawer etc.)
    val text: Int,           // main text
    val subText: Int,        // secondary text
    val hint: Int,           // input placeholder
    val primary: Int,        // primary color (button bg / accent text / top bar)
    val primarySoft: Int,    // light tint of primary (selected state)
    val onPrimary: Int,      // text on primary
    val thoughtBg: Int,      // thought bubble background
    val thoughtText: Int,    // thought text
    val ok: Int,
    val warn: Int,
    val err: Int,
    val idle: Int,
    val errText: Int,        // error body text (slightly darker than err)
    val stop: Int,           // "stop" button while generating
    val mediaBg: Int,        // image thumbnail background
    val scrim: Int,          // drawer scrim
    val switchTrack: Int,    // slider switch: track bg
    val switchThumb: Int,    // slider switch: thumb
    val statusBar: Int,      // status bar (same as top bar)
    val navBar: Int,         // navigation bar
    val isDark: Boolean,
) {
    /** Aplica un acento de la [AccentPalette], recalculando los derivados. */
    fun withAccent(index: Int): YumenodeTheme {
        val i = index.coerceIn(0, AccentPalette.size - 1)
        val c = if (isDark) AccentPalette.dark[i] else AccentPalette.light[i]
        val tint = if (isDark) blend(c, bg, 0.18f) else blend(c, 0xFFFFFFFF.toInt(), 0.12f)
        val on = if (luminance(c) > 0.62f) 0xFF001018.toInt() else 0xFFFFFFFF.toInt()
        return copy(
            primary = c,
            primarySoft = tint,
            onPrimary = on,
            switchThumb = c,
            statusBar = if (isDark) statusBar else c,
        )
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }

    companion object {
        fun light() = YumenodeTheme(
            bg = 0xFFF2F4F8.toInt(),
            bgAlt = 0xFFF5F6F8.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            fieldBg = 0xFFF2F3F5.toInt(),
            softBtn = 0xFFF3F5F9.toInt(),
            softBtn2 = 0xFFF6F7FA.toInt(),
            border = 0xFFDBE0EA.toInt(),
            chipBg = 0xFFF7F8FB.toInt(),
            divider = 0x1F000000,
            dividerLine = 0xFFEEF0F5.toInt(),
            text = 0xFF1C202A.toInt(),
            subText = 0xFF7A8394.toInt(),
            hint = 0xFFAAAAAA.toInt(),
            primary = 0xFF2F6BFF.toInt(),
            primarySoft = 0xFFEDF1FF.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            thoughtBg = 0xFFF5F7FB.toInt(),
            thoughtText = 0xFF6E7686.toInt(),
            ok = 0xFF22B26E.toInt(),
            warn = 0xFFF59E0B.toInt(),
            err = 0xFFE55252.toInt(),
            idle = 0xFF9AA4B5.toInt(),
            errText = 0xFFCC3333.toInt(),
            stop = 0xFFD9534F.toInt(),
            mediaBg = 0xFFEDEDED.toInt(),
            scrim = 0x66000000,
            switchTrack = 0xFFF1F3F7.toInt(),
            switchThumb = 0xFFFFFFFF.toInt(),
            statusBar = 0xFF2F6BFF.toInt(),
            navBar = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        fun dark() = YumenodeTheme(
            // ── 1. Fondos: Azul Noche Absoluto (#020814/#0A0D1A) + Azul Profundo de Red (#1A237E) ──
            bg = 0xFF020814.toInt(),              // página / scroll (fondo más oscuro)
            bgAlt = 0xFF0A0D1A.toInt(),           // fondo secundario
            surface = 0xFF1A237E.toInt(),         // tarjetas, top bar, input bar
            fieldBg = 0xFF0D1733.toInt(),         // inputs / previews cóncavos
            softBtn = 0xFF121E42.toInt(),         // botón secundario (sólido)
            softBtn2 = 0xFF14224A.toInt(),        // botón secundario (outline)
            border = 0xFF2E4A8C.toInt(),          // bordes / strokes (tinte del Azul Profundo)
            chipBg = 0xFF111F44.toInt(),          // chip no seleccionado
            divider = 0x20FFFFFF.toInt(),         // divisor semitransparente
            dividerLine = 0xFF1A2752.toInt(),     // línea 1px
            thoughtBg = 0xFF121E42.toInt(),       // burbuja de razonamiento
            mediaBg = 0xFF0A0D1A.toInt(),         // base de miniaturas
            scrim = 0x99020814.toInt(),           // overlay del drawer
            // ── 2. Texto ──
            text = 0xFFE6E9EF.toInt(),            // principal
            subText = 0xFF8A96B4.toInt(),          // secundario
            hint = 0xFF6A7DA9.toInt(),            // placeholder
            thoughtText = 0xFF9BA7C4.toInt(),     // texto de razonamiento
            errText = 0xFFFF8A8A.toInt(),         // texto de error
            // ── 3. Acento: Cyan eléctrico (#00F0FF), Magenta neón (#FF007F)
            //    (Violeta #9D4EDD está reservado para glow gráfico — futura capa visual) ──
            primary = 0xFF00F0FF.toInt(),         // cyan — acento / top bar / botones
            primarySoft = 0xFF002A33.toInt(),     // tinte muy oscuro de cyan (seleccionado)
            onPrimary = 0xFF001018.toInt(),       // texto sobre cyan (casi negro)
            switchThumb = 0xFF00F0FF.toInt(),     // thumb del switch = cyan
            switchTrack = 0xFF0D1733.toInt(),     // track del switch
            stop = 0xFFFF007F.toInt(),            // magenta — energía / punto caliente
            statusBar = 0xFF020814.toInt(),       // barra de estado (inmersiva)
            navBar = 0xFF020814.toInt(),          // barra de navegación
            // semáforos funcionales (reconocibles)
            ok = 0xFF35C57F.toInt(),
            warn = 0xFFF0A22E.toInt(),
            err = 0xFFFF6B6B.toInt(),
            idle = 0xFF7E8798.toInt(),
            isDark = true,
        )

        /** OLED: negro puro (#000000) para paneles OLED; ahorra batería y evita el gris elevado. */
        fun oled() = dark().copy(
            bg = 0xFF000000.toInt(),
            bgAlt = 0xFF000000.toInt(),
            surface = 0xFF000000.toInt(),
            fieldBg = 0xFF080808.toInt(),
            softBtn = 0xFF0E0E0E.toInt(),
            softBtn2 = 0xFF101010.toInt(),
            border = 0xFF2A2A2A.toInt(),
            chipBg = 0xFF0A0A0A.toInt(),
            divider = 0x14FFFFFF,
            dividerLine = 0xFF1A1A1A.toInt(),
            thoughtBg = 0xFF0A0A0A.toInt(),
            mediaBg = 0xFF000000.toInt(),
            scrim = 0xCC000000.toInt(),
            switchTrack = 0xFF0A0A0A.toInt(),
            statusBar = 0xFF000000.toInt(),
            navBar = 0xFF000000.toInt(),
        )

        /** Theme mode: 0=follow system 1=light 2=dark 3=OLED */
        fun resolve(ctx: Context, mode: Int): YumenodeTheme = when (mode) {
            1 -> light()
            2 -> dark()
            3 -> oled()
            else -> if (systemDark(ctx)) dark() else light()
        }

        fun systemDark(ctx: Context): Boolean =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}

/** Acentos seleccionables (el índice se guarda en prefs como "accentIndex"). */
object AccentPalette {
    val keys = arrayOf("cyan", "magenta", "lime", "violet", "amber")
    val dark = intArrayOf(
        0xFF00F0FF.toInt(), 0xFFFF2E9A.toInt(), 0xFF7CFF6B.toInt(),
        0xFFA78BFA.toInt(), 0xFFFFC24B.toInt(),
    )
    val light = intArrayOf(
        0xFF007C8C.toInt(), 0xFFC4146A.toInt(), 0xFF2E8B3C.toInt(),
        0xFF6D4AE0.toInt(), 0xFFA86A00.toInt(),
    )
    val size get() = keys.size
}
