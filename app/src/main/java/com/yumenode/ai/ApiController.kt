// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.graphics.Color
import android.view.View

/**
 * Lógica del bloque de configuración de API (estado / 地址 / key / 开关 / 统计)。
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

    /** 刷新某个 API 区块：状态 / 地址 / key / 开关 / 统计 */
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
        // 「对话模型：xxx」——告诉用户这个接口到底在服务哪个模型
        if (host.modelsInitialized) {
            val name = ChatCore.modelName
            host.chatApiModelTv.text =
                if (name != null) activity.getString(R.string.s_436, name) else activity.getString(R.string.s_437)
            host.chatApiModelTv.setTextColor(if (name != null) C_TEXT else C_SUBTEXT)
        }
        refreshApiBlock(ApiServer.Role.CHAT, host.chatApiBlock)
    }

    /** 绘图侧除了通用内容，还多一行「绘图模型：xxx」——那是句柄层面的状态，与服务无关 */
    fun refreshDrawApiUi() {
        if (host.modelsInitialized) {
            val name = DrawCore.modelName
            host.drawApiModelTv.text =
                if (name != null) activity.getString(R.string.s_426, name) else activity.getString(R.string.s_427)
            host.drawApiModelTv.setTextColor(if (name != null) C_TEXT else C_SUBTEXT)
        }
        refreshApiBlock(ApiServer.Role.DRAW, host.drawApiBlock)
    }

    /** 两个区块一起刷新：两者共用一个前台服务，一边变化常会牵动另一边 */
    fun refreshApiSurfaces() {
        refreshApiUi()
        refreshDrawApiUi()
    }

    /** 复制某个服务的地址（点一下地址那行即可） */
    fun copyApiAddress(role: ApiServer.Role) {
        val addr = ApiService.addressOf(role) ?: return
        runCatching {
            val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ponko-api", addr))
            host.toast(activity.getString(R.string.s_416))
        }
    }

    /** 生成一个新的 API key（形如 «redacted:sk-…»） */
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

    /** 端口 / key 改完之后：该服务在跑就重启它，让新配置立刻生效（不用用户自己记得重启） */
    fun afterApiConfigChanged(role: ApiServer.Role) {
        refreshApiSurfaces()
        if (ApiService.isRunning(role)) {
            ApiService.sync(activity)
            android.os.Handler(activity.mainLooper).postDelayed({ refreshApiSurfaces() }, 500)
        }
    }

    /**
     * 开关某一类 API。
     *
     * **不检查有没有加载模型** —— 服务只是把 HTTP 端口架起来，加载模型是各接口自己的事：
     * 没加载时对应接口返回 503 并说明原因。早期版本要求「先加载对话模型」，
     * 那是把可选前置条件错当成了拦路条件，逼着想开绘图接口的人去加一个无关的模型。
     */
    fun toggleApi(role: ApiServer.Role) {
        val wasOn = ApiService.isRunning(role)
        host.chatPrefs().edit().putBoolean(ApiService.keyEnabled(role), !wasOn).apply()
        if (!wasOn && android.os.Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Android 13+ 常驻通知需要运行时权限；不给也能跑，只是看不到通知
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        ApiService.sync(activity)
        android.os.Handler(activity.mainLooper).postDelayed({ refreshApiSurfaces() }, 700)
    }
}
