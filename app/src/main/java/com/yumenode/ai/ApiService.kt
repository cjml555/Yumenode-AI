// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * API service: exposes local models on the phone as an **OpenAI-compatible** interface,
 * keeps responding after the app goes to background or the screen is locked.
 *
 * **Chat and drawing are two independent things**; this service hosts both:
 *
 * | | Chat API | Drawing API |
 * |---|---|---|
 * | Port | `apiPort` (default 8080) | `drawApiPort` (default 8081) |
 * | key  | `apiKey` | `drawApiKey` |
 * | Switch | `apiEnabled` | `drawApiEnabled` |
 * | Route | `/v1/chat/completions` | `/v1/images/generations` |
 *
 * Each has its own port, key, switch, and stats — enable just one or both.
 * Why not cram them onto one port: their service targets, resource usage, and security exposure
 * are totally different — one image takes tens of seconds and maxes CPU, normally should only
 * run in trusted networks; the chat interface is for daily connection; hard-binding them together
 * equals denying separate configuration.
 *
 * But **one foreground service + one notification**: Android's foreground service is the mechanism
 * to "keep the process from being killed"; starting two only gets two persistent notifications,
 * each handling permissions and WifiLock separately, with no benefit.
 * The notification lists all running addresses.
 *
 * Why type must be **specialUse**: `dataSync` / `mediaProcessing` on Android 15+ (targetSdk 35+)
 * have a "max 6 hours per 24 hours" timeout; the system forcibly stops them at that point —
 * a persistent service can't use those two categories.
 *
 * The real inference happens in [ChatCore] / [DrawCore] (process-level singletons),
 * so models loaded in the app are directly available here.
 */
class ApiService : Service() {

    companion object {
        const val ACTION_START = "com.yumenode.ai.action.API_START"
        const val ACTION_STOP = "com.yumenode.ai.action.API_STOP"

        private const val CHANNEL_ID = "ponko_api"
        private const val NOTIF_ID = 4101
        private const val PREFS = "ponko"

        // ---- Chat API ----
        const val KEY_ENABLED = "apiEnabled"
        const val KEY_PORT = "apiPort"
        const val KEY_TOKEN = "apiKey"
        const val DEF_PORT = 8080

        // ---- Draw API (independent; default port offset from chat to avoid collision) ----
        const val KEY_DRAW_ENABLED = "drawApiEnabled"
        const val KEY_DRAW_PORT = "drawApiPort"
        const val KEY_DRAW_TOKEN = "drawApiKey"
        const val DEF_DRAW_PORT = 8081

        @Volatile var chatRunning = false
            private set
        @Volatile var drawRunning = false
            private set

        /** Either one running */
        val running: Boolean get() = chatRunning || drawRunning

        @Volatile var chatAddress: String? = null
            private set
        @Volatile var drawAddress: String? = null
            private set

        @Volatile var chatServed = 0
            private set
        @Volatile var chatLast: String? = null
            private set
        @Volatile var drawServed = 0
            private set
        @Volatile var drawLast: String? = null
            private set

        /** Currently running server instances (same process; UI reads live stats directly) */
        @Volatile internal var chatServer: ApiServer? = null
        @Volatile internal var drawServer: ApiServer? = null

        // ---------- Preference keys / defaults by role ----------

        fun keyEnabled(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) KEY_ENABLED else KEY_DRAW_ENABLED

        fun keyPort(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) KEY_PORT else KEY_DRAW_PORT

        fun keyToken(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) KEY_TOKEN else KEY_DRAW_TOKEN

        fun defaultPort(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) DEF_PORT else DEF_DRAW_PORT

        fun isRunning(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) chatRunning else drawRunning

        fun addressOf(role: ApiServer.Role): String? =
            if (role == ApiServer.Role.CHAT) chatAddress else drawAddress

        /** Pulls live stats once: request count + most recent one */
        fun statsSnapshot(role: ApiServer.Role): Pair<Int, String?> {
            publish()
            return if (role == ApiServer.Role.CHAT) chatServed to chatLast else drawServed to drawLast
        }

        private fun publish() {
            chatServer?.let { chatServed = it.served; chatLast = it.lastRequest }
            drawServer?.let { drawServed = it.served; drawLast = it.lastRequest }
        }

        private fun serverOf(role: ApiServer.Role) =
            if (role == ApiServer.Role.CHAT) chatServer else drawServer

        private fun setServer(role: ApiServer.Role, s: ApiServer?) {
            if (role == ApiServer.Role.CHAT) chatServer = s else drawServer = s
        }

        private fun setRunning(role: ApiServer.Role, v: Boolean) {
            if (role == ApiServer.Role.CHAT) chatRunning = v else drawRunning = v
        }

        private fun setAddress(role: ApiServer.Role, v: String?) {
            if (role == ApiServer.Role.CHAT) chatAddress = v else drawAddress = v
        }

        /**
         * Called by the UI after changing switch / port / key: aligns start/stop of both servers
         * per current preference.
         *
         * If port or key changed, **auto-restarts that one** (instead of making the user remember
         * to restart), so "change port takes effect immediately" — one fewer pitfall.
         */
        fun sync(ctx: Context) {
            val i = Intent(ctx, ApiService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stopAll(ctx: Context) {
            ctx.startService(Intent(ctx, ApiService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Broadcast para encender/apagar el servidor desde fuera de la app.
         *
         * Sirve para automatizar pruebas: `adb shell am broadcast -a com.yumenode.ai.API_START`
         * levanta el servidor sin tener que tocar la pantalla (el boton "Iniciar servicio"
         * vive en una pestana que hay que abrir a mano).
         */
        const val BROADCAST_START = "com.yumenode.ai.API_START"
        const val BROADCAST_STOP = "com.yumenode.ai.API_STOP"

        /** Local IPv4 in LAN (returns 127.0.0.1 if unavailable) */
        fun localIp(): String = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null

    // --- Background health checks for cluster peers ---
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            ClusterRouter.runHealthCheck(this@ApiService)
            healthCheckHandler.postDelayed(this, 30000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground service must call startForeground ASAP (may have been started via startForegroundService),
        // so go foreground first, then align servers
        ensureChannel()
        goForeground()
        // Start periodic cluster health checks (no-op if cluster disabled)
        healthCheckHandler.postDelayed(healthCheckRunnable, 5000)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownAll()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                reconcile()
                if (!running) {
                    // Nothing needs running → shut down (also removes the notification)
                    shutdownAll()
                    stopSelf()
                    return START_NOT_STICKY
                }
                acquireWifiLock()
                refreshNotification()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownAll()
        healthCheckHandler.removeCallbacks(healthCheckRunnable)
        super.onDestroy()
    }

    /**
     * Recibe los broadcasts de arranque/parada. Vive en el propio Service para
     * que android:exported="true" no exponga nada mas de la app.
     */
    class BootReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                // startService (no foreground): desde un receiver en background,
                // startForegroundService esta restringido en Android 12+.
                // El Service se convierte en foreground en cuanto arranca.
                BROADCAST_START -> context.startService(
                    Intent(context, ApiService::class.java).setAction(ACTION_START))
                BROADCAST_STOP -> stopAll(context)
            }
        }
    }

    // ==================== Start/stop alignment ====================

    private fun reconcile() {
        ClusterRouter.initialize(this)
        reconcileOne(ApiServer.Role.CHAT)
        reconcileOne(ApiServer.Role.DRAW)
    }

    private fun reconcileOne(role: ApiServer.Role) {
        val enabled = prefs().getBoolean(keyEnabled(role), false)
        val port = prefs().getInt(keyPort(role), defaultPort(role)).coerceIn(1024, 65535)
        val key = prefs().getString(keyToken(role), null)?.takeIf { it.isNotBlank() }
        val existing = serverOf(role)

        // Already running per current config — leave it
        if (enabled && existing != null && existing.port == port && existing.apiKey == key) return

        // Config changed or shutting down — stop old instance first
        if (existing != null) {
            publish()
            existing.stop()
            setServer(role, null)
            setRunning(role, false)
            setAddress(role, null)
        }
        if (!enabled) return

        val srv = ApiServer(port, key, role) { msg ->
            if (role == ApiServer.Role.CHAT) chatLast = msg else drawLast = msg
        }
        if (srv.start()) {
            setServer(role, srv)
            setRunning(role, true)
            setAddress(role, "http://${localIp()}:$port")
        } else {
            // Port occupied etc.: keep "not running"; UI shows not running, reason in logs
            setServer(role, null)
            setRunning(role, false)
            setAddress(role, null)
        }
    }

    private fun shutdownAll() {
        for (role in listOf(ApiServer.Role.CHAT, ApiServer.Role.DRAW)) {
            serverOf(role)?.let {
                publish()
                it.stop()
            }
            setServer(role, null)
            setRunning(role, false)
            setAddress(role, null)
        }
        releaseWifiLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    // ==================== Notification ====================

    private fun nm(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.s_393),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.s_394)
            setShowBadge(false)
        }
        nm().createNotificationChannel(ch)
    }

    /** Notification body: lists running addresses; if none started, says "starting" or "start failed" */
    private fun notificationText(): String {
        val parts = ArrayList<String>()
        if (chatRunning) parts.add(getString(R.string.s_431, chatAddress ?: ""))
        if (drawRunning) parts.add(getString(R.string.s_432, drawAddress ?: ""))
        // If one "enabled but not started" (port occupied etc.) → add a line; otherwise the user only sees a missing line
        val failed = listOf(ApiServer.Role.CHAT, ApiServer.Role.DRAW).count {
            prefs().getBoolean(keyEnabled(it), false) && !isRunning(it)
        }
        if (parts.isEmpty()) {
            // None started: either still starting, or all failed — can't make the user wait around on "starting…"
            return if (failed > 0) getString(R.string.s_435) else getString(R.string.s_434)
        }
        val txt = parts.joinToString("  ·  ")
        return if (failed > 0) txt + "  ·  " + getString(R.string.s_435) else txt
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CHANNEL_ID else null)
            .setContentTitle(getString(R.string.s_391))
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.s_396), stop).build()
            )
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Refresh notification content (called on state change) */
    fun refreshNotification() {
        runCatching { nm().notify(NOTIF_ID, buildNotification()) }
    }

    // ==================== WifiLock ====================

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ponko:api").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
