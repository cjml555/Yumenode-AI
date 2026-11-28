#!/usr/bin/env python3
"""Segunda pasada de i18n: saca a recursos el texto de UI que quedaba en el código."""
import re, pathlib, sys

S = {
 # clave: (en, es, zh)
 "dl_toast_downloading": ("Downloading %1$s…", "Descargando %1$s…", "正在下载 %1$s…"),
 "dl_toast_done": ("Download complete: %1$s", "Descarga completada: %1$s", "下载完成：%1$s"),
 "dl_toast_cancelled": ("Download cancelled", "Descarga cancelada", "下载已取消"),
 "dl_toast_error": ("Error: %1$s", "Error: %1$s", "错误：%1$s"),
 "premium_unlocked": ("Premium unlocked", "Premium desbloqueado", "已解锁 Premium"),
 "about_fork_credit": (
   "Original: Ponko © visitor257 (MIT). Derivative fork, renamed and partially refactored as Yumenode AI.",
   "Original: Ponko © visitor257 (MIT). Derivado como fork, rebautizado y parcialmente refactorizado como Yumenode AI.",
   "原作：Ponko © visitor257（MIT）。本项目为其派生分支，已更名并部分重构为 Yumenode AI。"),
 "premium_features": ("Premium features: %1$s", "Funciones premium: %1$s", "高级功能：%1$s"),
 "feat_cloud_sync": ("cloud sync", "sincronización en la nube", "云同步"),
 "feat_batch_generation": ("batch generation", "generación por lotes", "批量生成"),
 "feat_advanced_benchmark": ("advanced benchmark", "benchmark avanzado", "高级基准测试"),
 "feat_session_export": ("session export", "exportar sesiones", "导出会话"),
 "feat_usage_analytics": ("usage analytics", "analítica de uso", "使用情况分析"),
 "feat_custom_prompts": ("custom prompts", "prompts personalizados", "自定义提示词"),
 "feat_multi_device_api": ("multi-device API", "API multidispositivo", "多设备 API"),
 "feat_priority_gpu": ("priority GPU", "GPU prioritaria", "GPU 优先"),
 "export_no_sessions": ("No sessions file found", "No se encontró el fichero de sesiones", "未找到会话文件"),
 "export_sessions_ok": ("Sessions exported to %1$s", "Sesiones exportadas en %1$s", "会话已导出到 %1$s"),
 "export_no_bench": ("No benchmark data. Run benchmark first.",
                     "No hay datos de benchmark. Ejecuta uno primero.",
                     "还没有基准测试数据，请先运行一次。"),
 "export_bench_ok": ("Benchmark CSV exported to %1$s", "CSV del benchmark exportado en %1$s", "基准测试 CSV 已导出到 %1$s"),
 "cloud_sync_url_hint": ("Server URL", "URL del servidor", "服务器地址"),
 "cloud_sync_token_hint": ("Token (optional)", "Token (opcional)", "令牌（可选）"),
 "cloud_sync_url_missing": ("Enter a server URL", "Escribe la URL del servidor", "请输入服务器地址"),
 "cloud_sync_ok": ("Cloud sync OK (HTTP %1$d)", "Sincronización correcta (HTTP %1$d)", "云同步成功（HTTP %1$d）"),
 "cloud_sync_failed_http": ("Cloud sync failed (HTTP %1$d)", "Falló la sincronización (HTTP %1$d)", "云同步失败（HTTP %1$d）"),
 "cloud_sync_failed": ("Cloud sync failed: %1$s", "Falló la sincronización: %1$s", "云同步失败：%1$s"),
 "analytics_no_data": ("No session data yet", "Todavía no hay datos de sesiones", "还没有会话数据"),
 "analytics_ok": ("Analytics exported: %1$d sessions, %2$d turns, %3$d images",
                  "Analítica exportada: %1$d sesiones, %2$d turnos, %3$d imágenes",
                  "分析已导出：%1$d 个会话、%2$d 轮对话、%3$d 张图片"),
 "analytics_failed": ("Analytics failed: %1$s", "Falló la analítica: %1$s", "分析失败：%1$s"),
 "api_dialog_chat": ("Chat API: %1$s", "API de chat: %1$s", "对话 API：%1$s"),
 "api_status_running": ("Status: running", "Estado: en marcha", "状态：运行中"),
 "api_status_enabled": ("Status: enabled but not started", "Estado: activada pero sin arrancar", "状态：已启用但未启动"),
 "api_status_disabled": ("Status: disabled", "Estado: desactivada", "状态：已停用"),
 "api_key_label": ("Key: %1$s", "Clave: %1$s", "密钥：%1$s"),
 "api_key_set": ("set", "configurada", "已设置"),
 "api_key_none": ("none (open)", "ninguna (abierta)", "无（开放）"),
 "api_hint_wifi": ("Same Wi-Fi required. Start the API in Models/API → API first.",
                   "Requiere la misma Wi-Fi. Arranca la API en Modelos/API → API.",
                   "需要同一 Wi-Fi。请先在「模型/API → API」中启动。"),
 "copy_url": ("Copy URL", "Copiar URL", "复制地址"),
 "copied_value": ("Copied: %1$s", "Copiado: %1$s", "已复制：%1$s"),
 "saved_peers": ("Saved %1$d peer(s)", "Guardados %1$d pares", "已保存 %1$d 个对等设备"),
 "deleted_value": ("Deleted: %1$s", "Borrado: %1$s", "已删除：%1$s"),
 "saved_value": ("Saved: %1$s", "Guardado: %1$s", "已保存：%1$s"),
}

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
src_dir = root / "app/src/main/java/com/yumenode/ai"

# ---- 1. Cadenas ----
def esc(t: str) -> str:
    return t.replace("&", "&amp;").replace("'", "\\'").replace('"', '\\"')

for idx, loc in enumerate(["", "-es", "-zh"]):
    f = root / f"app/src/main/res/values{loc}/strings.xml"
    s = f.read_text(encoding="utf-8")
    have = set(re.findall(r'<string name="([^"]+)"', s))
    lines = [f'    <string name="{k}">{esc(v[idx])}</string>' for k, v in S.items() if k not in have]
    if lines:
        s = s.replace("</resources>", "\n".join(lines) + "\n</resources>")
        f.write_text(s, encoding="utf-8")
    print(f"values{loc}: +{len(lines)}")

# ---- 2. Código ----
EDITS = {
 "DownloadPage.kt": [
   ('toast("Descargando ${info.name}...")',
    'toast(ctx.getString(R.string.dl_toast_downloading, info.name))'),
   ('toast("Descarga completada: ${info.name}")',
    'toast(ctx.getString(R.string.dl_toast_done, info.name))'),
   ('st == "cancelada" -> toast("Descarga cancelada")',
    'st == "cancelada" -> toast(ctx.getString(R.string.dl_toast_cancelled))'),
   ('else -> toast("Error: ${st.removePrefix("error:")}")',
    'else -> toast(ctx.getString(R.string.dl_toast_error, st.removePrefix("error:")))'),
 ],
 "MainActivity.kt": [
   ('toast("Premium unlocked")', 'toast(getString(R.string.premium_unlocked))'),
 ],
 "SettingsPage.kt": [
   ('host.ui.hintText("Original: Ponko © visitor257 (MIT). Derivado como fork, rebautizado y parcialmente refactorizado como Yumenode AI.")',
    'host.ui.hintText(getString(R.string.about_fork_credit))'),
   ('''            val enabled = listOf(
                "cloud sync" to FeatureToggleManager.enableCloudSync,
                "batch generation" to FeatureToggleManager.enableBatchGeneration,
                "advanced benchmark" to FeatureToggleManager.enableAdvancedBenchmark,
                "session export" to FeatureToggleManager.enableSessionExport,
                "usage analytics" to FeatureToggleManager.enableUsageAnalytics,
                "custom prompts" to FeatureToggleManager.enableCustomPrompts,
                "multi-device API" to FeatureToggleManager.enableMultiDeviceAPI,
                "priority GPU" to FeatureToggleManager.enablePriorityGPU
            ).filter { it.second }.joinToString { it.first }
            aboutBody.addView(host.ui.hintText("Premium features: $enabled"), matchWrap())''',
    '''            val enabled = listOf(
                getString(R.string.feat_cloud_sync) to FeatureToggleManager.enableCloudSync,
                getString(R.string.feat_batch_generation) to FeatureToggleManager.enableBatchGeneration,
                getString(R.string.feat_advanced_benchmark) to FeatureToggleManager.enableAdvancedBenchmark,
                getString(R.string.feat_session_export) to FeatureToggleManager.enableSessionExport,
                getString(R.string.feat_usage_analytics) to FeatureToggleManager.enableUsageAnalytics,
                getString(R.string.feat_custom_prompts) to FeatureToggleManager.enableCustomPrompts,
                getString(R.string.feat_multi_device_api) to FeatureToggleManager.enableMultiDeviceAPI,
                getString(R.string.feat_priority_gpu) to FeatureToggleManager.enablePriorityGPU
            ).filter { it.second }.joinToString { it.first }
            aboutBody.addView(
                host.ui.hintText(getString(R.string.premium_features, enabled)),
                matchWrap()
            )'''),
   ('host.toast("No sessions file found")\n            return\n        }\n        val data = sessions.readText()',
    'host.toast(getString(R.string.export_no_sessions))\n            return\n        }\n        val data = sessions.readText()'),
   ('host.toast("Sessions exported to ${file.absolutePath}")',
    'host.toast(getString(R.string.export_sessions_ok, file.absolutePath))'),
   ('host.toast("No benchmark data. Run benchmark first.")',
    'host.toast(getString(R.string.export_no_bench))'),
   ('host.toast("Benchmark CSV exported to ${file.absolutePath}")',
    'host.toast(getString(R.string.export_bench_ok, file.absolutePath))'),
   ('hint = "Server URL"', 'hint = getString(R.string.cloud_sync_url_hint)'),
   ('hint = "Token (optional)"', 'hint = getString(R.string.cloud_sync_token_hint)'),
   ('host.toast("Enter a server URL")', 'host.toast(getString(R.string.cloud_sync_url_missing))'),
   ('host.toast(if (code in 200..299) "Cloud sync OK (HTTP $code)" else "Cloud sync failed (HTTP $code)")',
    'host.toast(\n                        if (code in 200..299) getString(R.string.cloud_sync_ok, code)\n                        else getString(R.string.cloud_sync_failed_http, code)\n                    )'),
   ('host.activity.runOnUiThread { host.toast("Cloud sync failed: ${e.message ?: e.javaClass.simpleName}") }',
    'host.activity.runOnUiThread {\n                    host.toast(getString(R.string.cloud_sync_failed, e.message ?: e.javaClass.simpleName))\n                }'),
   ('host.toast("No session data yet")', 'host.toast(getString(R.string.analytics_no_data))'),
   ('host.toast("Analytics exported: ${arr.length()} sessions, $totalTurns turns, $totalImages images")',
    'host.toast(getString(R.string.analytics_ok, arr.length(), totalTurns, totalImages))'),
   ('host.toast("Analytics failed: ${e.message ?: e.javaClass.simpleName}")',
    'host.toast(getString(R.string.analytics_failed, e.message ?: e.javaClass.simpleName))'),
   ('''            append("Chat API: $base\\n")
            append(if (ApiService.chatRunning) "Status: running" else if (chatEnabled) "Status: enabled but not started" else "Status: disabled")
            append("\\n")
            append("Key: ").append(if (apiKey != null) "set" else "none (open)")
            append("\\n\\n/v1/chat/completions · /v1/models · /health\\n")
            append("Same Wi-Fi required. Start the API in Models/API → API first.")''',
    '''            append(getString(R.string.api_dialog_chat, base)).append("\\n")
            append(
                if (ApiService.chatRunning) getString(R.string.api_status_running)
                else if (chatEnabled) getString(R.string.api_status_enabled)
                else getString(R.string.api_status_disabled)
            )
            append("\\n")
            append(getString(R.string.api_key_label, if (apiKey != null) getString(R.string.api_key_set) else getString(R.string.api_key_none)))
            append("\\n\\n/v1/chat/completions · /v1/models · /health\\n")
            append(getString(R.string.api_hint_wifi))'''),
   ('.setPositiveButton("Copy URL") { _, _ ->', '.setPositiveButton(R.string.copy_url) { _, _ ->'),
   ('host.toast("Copied: $base")', 'host.toast(getString(R.string.copied_value, base))'),
   ('host.toast("Saved ${urls.size} peer(s)")', 'host.toast(getString(R.string.saved_peers, urls.size))'),
   ('host.toast("Copied: $name")', 'host.toast(getString(R.string.copied_value, name))'),
   ('host.toast("Deleted: $name")', 'host.toast(getString(R.string.deleted_value, name))'),
   ('host.toast("Saved: $n")', 'host.toast(getString(R.string.saved_value, n))'),
 ],
}

for fname, pairs in EDITS.items():
    p = src_dir / fname
    s = p.read_text(encoding="utf-8")
    for old, new in pairs:
        if old not in s:
            print(f"  !! no encontrado en {fname}: {old[:60]}")
            continue
        s = s.replace(old, new, 1)
    p.write_text(s, encoding="utf-8")
    print(f"{fname}: {len(pairs)} puntos procesados")
