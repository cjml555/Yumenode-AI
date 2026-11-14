// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.graphics.Color
import android.view.View

/**
 * Lógica del bloque de configuración de API (estado / address / key / toggle / stats).
 *
 * Extraída tal cual de MainActivity (piloto del refactor): mismo comportamiento,
 * mismos textos, mismas claves de prefs («ponko») y mismos puertos/roles de
 * [ApiServer]. Solo recibe [ApiHost]; la construcción del bloque sigue en
 * [ApiBlockBuilder] y [ModelsHost] sigue existiendo (delega vía la Activity).
 */
class ApiController(private val host: ApiHost) : ThemeAliases {
    override val palette: YumenodeTheme get() = host.palette

    private val activity get() = host.activity

    fun apiPort(role: ApiServer.Role): Int =
        host.chatPrefs().getInt(ApiService.keyPort(role), ApiService.defaultPort(role))
            .coerceIn(1024, 65535)

    /** Refresh a specific API block: state / address / key / toggle / stats */
    fun refreshApiBlock(role: ApiServer.Role, b: ApiBlockView?) {
        if (b == null) return
        val running = ApiService.isRunning(role)
        b.statusTv.text =
            if (running) activity.getString(R.string.s_406, ApiService.addressOf(role) ?: "")
            else activity.getString(R.string.s_405)
        b.statusTv.setTextColor(if (running) C_OK else C_SUBTEXT)
        b.addrTv.visibility = if (running) View.VISIBLE else View.GONE
        b.addrTv.text = if (running) activity.getString(R.string.s_417) else ""
        val key = host.chatPrefs().getString(ApiService.keyToken(role), null)
        b.keyTv.text = activity.getString(R.string.s_409,
            if (key.isNullOrBlank()) activity.getString(R.string.s_410) else key)
        b.toggleBtn.text = activity.getString(if (running) R.string.s_408 else R.string.s_407)
        b.toggleBtn.background = activity.rounded(
            if (running) C_SOFT_BTN2 else C_PRIMARY, 12,
            strokeDp = if (running) 1 else 0, strokeColor = C_BORDER)
        b.toggleBtn.setTextColor(if (running) C_TEXT else Color.WHITE)
        val (n, last) = ApiService.statsSnapshot(role)
        b.statsTv.text = if (!running) "" else
            activity.getString(R.string.s_413, n) + (last?.let { "\n" + activity.getString(R.string.s_414, it) } ?: "")
    }

    fun refreshApiUi() {
        // "Chat model: xxx" — tells the user which model this interface serves
        if (host.modelsInitialized) {
            val name = ChatCore.modelName
            host.chatApiModelTv.text =
                if (name != null) activity.getString(R.string.s_436, name) else activity.getString(R.string.s_437)
            host.chatApiModelTv.setTextColor(if (name != null) C_TEXT else C_SUBTEXT)
        }
        refreshApiBlock(ApiServer.Role.CHAT, host.chatApiBlock)
    }

    // Drawing side, in addition to generic content, also a "drawing model: xxx" row — handle-level status, unrelated to the service
    fun refreshDrawApiUi() {
        if (host.modelsInitialized) {
            val name = DrawCore.modelName
            host.drawApiModelTv.text =
                if (name != null) activity.getString(R.string.s_426, name) else activity.getString(R.string.s_427)
            host.drawApiModelTv.setTextColor(if (name != null) C_TEXT else C_SUBTEXT)
        }
        refreshApiBlock(ApiServer.Role.DRAW, host.drawApiBlock)
    }

    // Refresh both blocks together: they share one foreground service, and a change in one can drag the other
    fun refreshApiSurfaces() {
        refreshApiUi()
        refreshDrawApiUi()
    }

    /** Copy the address of a service (just click the address row) */
    fun copyApiAddress(role: ApiServer.Role) {
        val addr = ApiService.addressOf(role) ?: return
        runCatching {
            val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ponko-api", addr))
            host.toast(activity.getString(R.string.s_416))
        }
    }

    /** Generate a new API key (looks like «redacted:sk-…»)
     */
    fun newApiKey(role: ApiServer.Role) {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val key = "sk-ponko-" + (1..32).map { chars.random() }.joinToString("")
        host.chatPrefs().edit().putString(ApiService.keyToken(role), key).apply()
        afterApiConfigChanged(role)
        host.toast(activity.getString(R.string.s_418))
    }

    fun clearApiKey(role: ApiServer.Role) {
        host.chatPrefs().edit().remove(ApiService.keyToken(role)).apply()
        afterApiConfigChanged(role)
        host.toast(activity.getString(R.string.s_418))
    }

    /** After changing port / key, the service restarts itself so the new config takes effect immediately (no need for user to remember to restart) */
    fun afterApiConfigChanged(role: ApiServer.Role) {
        refreshApiSurfaces()
        if (ApiService.isRunning(role)) {
            ApiService.sync(activity)
            android.os.Handler(activity.mainLooper).postDelayed({ refreshApiSurfaces() }, 500)
        }
    }

    /**
     * Toggle an API class.
     *
     * **Does not check whether a model is loaded** -- the service only brings up the HTTP port; loading models is each endpoint's own business:
     * when none is loaded the endpoint returns 503 with a reason. Older versions required "load the chat model first",
     * which mistook an optional precondition for a hard blocker and forced anyone wanting the draw endpoint to load an unrelated model.
     */
    fun toggleApi(role: ApiServer.Role) {
        val wasOn = ApiService.isRunning(role)
        host.chatPrefs().edit().putBoolean(ApiService.keyEnabled(role), !wasOn).apply()
        if (!wasOn && android.os.Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Android 13+ persistent notification needs a runtime permission; works without it too, just no notification
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        ApiService.sync(activity)
        android.os.Handler(activity.mainLooper).postDelayed({ refreshApiSurfaces() }, 700)
    }
}
