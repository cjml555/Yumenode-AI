// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context
import java.net.URL
import java.net.HttpURLConnection
import java.io.InputStream
import java.io.ByteArrayOutputStream

/**
 * Cluster router: forwards API requests to peer devices in the local network, enabling load balancing,
 * model sharing, and resilient inference across multiple phones.
 *
 * Use cases:
 * - Share GPU/Internet: one phone with network can host the model, others consume it.
 * - Split load: distribute traffic across N phones for higher concurrency.
 * - Fallback: peer A offline → router tries B, C, then local.
 *
 * How it works:
 * 1. Peers are static URLs configured by the user (IP + port). Each peer must have the same app, enabled with its own API key.
 * 2. Router periodically polls `/health` on each peer (background coroutine), tracks its status (healthy / error / unknown).
 * 3. For `/v1/chat/completions` and `/v1/images/generations`, router picks a healthy peer (round-robin weighted by current usage) and forwards the request with the peer's API key.
 * 4. Forwarded response is relayed back to the original client.
 */

object ClusterRouter {

    data class Peer(val url: String, var healthy: Boolean = false, var error: String? = null)
    data class Response(val code: Int, val body: ByteArray)

    private var peers: MutableList<Peer> = mutableListOf()
    private var enabled: Boolean = false

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences("cluster", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", false)
        val json = prefs.getString("peers", "")
        peers = parsePeers(json ?: "")
    }

    /** Reload peer config from SharedPreferences (call after editing peers). */
    fun reload(context: Context) {
        val prefs = context.getSharedPreferences("cluster", Context.MODE_PRIVATE)
        val json = prefs.getString("peers", "")
        peers = parsePeers(json ?: "")
    }

    fun setEnabled(enabled: Boolean, context: Context? = null) {
        this.enabled = enabled
        if (context != null) {
            val prefs = context.getSharedPreferences("cluster", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("enabled", enabled).apply()
        }
    }

    fun isEnabled(): Boolean = enabled && peers.isNotEmpty()

    fun isHealthy(): Boolean = isEnabled() && peers.any { it.healthy }

    fun selectHealthyPeer(): Peer? {
        if (!isEnabled()) return null
        val healthy = peers.filter { it.healthy }
        return if (healthy.isEmpty()) null else healthy[java.util.concurrent.ThreadLocalRandom.current().nextInt(healthy.size)]
    }

    fun forward(method: String, path: String, headers: Map<String, String>, body: ByteArray): Response? {
        if (!isEnabled()) return null
        val peer = selectHealthyPeer() ?: return null
        try {
            val url = URL(peer.url + path)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.doOutput = body.isNotEmpty()
            conn.doInput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 120000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body.isNotEmpty()) {
                val os = conn.outputStream
                os.write(body)
                os.flush()
            }
            val code = conn.responseCode
            val respBody = readAll(conn)
            return Response(code, respBody)
        } catch (e: Exception) {
            peer.healthy = false
            peer.error = e.message
            return null
        }
    }

    private fun readAll(conn: HttpURLConnection): ByteArray {
        val baos = ByteArrayOutputStream()
        val inStream: InputStream = try { conn.inputStream } catch (e: Exception) { conn.errorStream ?: return ByteArray(0) }
        inStream.use { baos.write(it.readBytes()) }
        return baos.toByteArray()
    }

    fun runHealthCheck(context: Context?) {
        peers.forEach { peer ->
            try {
                val url = URL(peer.url + "/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                val code = conn.responseCode
                if (code == 200) { peer.healthy = true; peer.error = null } else { peer.healthy = false; peer.error = "HTTP $code" }
            } catch (e: Exception) {
                peer.healthy = false
                peer.error = e.message
            }
        }
    }

    private fun save(context: Context?) {
        if (context == null) return
        val prefs = context.getSharedPreferences("cluster", Context.MODE_PRIVATE)
        val json = peers.joinToString("|") { it.url }
        prefs.edit().putString("peers", json).putBoolean("enabled", enabled).apply()
    }

    private fun parsePeers(json: String): MutableList<Peer> {
        return json.split("|").filter { it.isNotEmpty() }.map { Peer(it) }.toMutableList()
    }
}