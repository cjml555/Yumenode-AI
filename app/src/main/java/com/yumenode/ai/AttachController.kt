// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.google.ai.edge.litertlm.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Lógica del bloque de adjuntos/archivos (drawer de adjuntar, cámara,
 * picks de imágenes/archivos de chat).
 *
 * Extraída tal cual de MainActivity: mismos request codes, intents, permisos
 * y mensajes. Solo recibe [AttachHost]; los picks de modelos/LoRA/tagger se
 * quedan en la Activity.
 */
class AttachController(private val host: AttachHost) : ThemeAliases {
    override val palette: YumenodeTheme get() = host.palette

    private val activity get() = host.activity

    /** 拍照时预创建的 MediaStore URI */
    private var pendingCameraUri: Uri? = null

    /** 安卓 9 及以下等拿到存储权限后再执行的动作（仅这些机型用得到） */
    private var pendingStorageAction: (() -> Unit)? = null

    /** 探测 .litertlm 模型是否支持图像输入；任何异常都按「不支持」处理。 */
    fun probeVision(path: String): Boolean = try {
        ModelInfo.from(path).use { it.inputModalities().vision }
    } catch (_: Throwable) {
        false
    }

    fun onAttachClick() {
        // 生成过程中也允许先把附件备好（发送仍会被 busy 拦住）
        // 注意：这里只管「模型加载了没」——文本文件任何模型都能读，不该被「必须多模态」挡住
        if (!host.hasModel) { host.toast(activity.getString(R.string.s_273)); return }
        showAttachDrawer()
    }

    /** 「＋」抽屉：贴着输入栏向上展开，可选拍照 / 图库。 */
    private fun showAttachDrawer() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.rounded(C_CARD, 14)
            elevation = activity.dp(8).toFloat()
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
        }
        // 外面再套一层留 8dp 余量：给阴影和缩放动画留空间，避免被弹窗边界裁掉
        val outer = FrameLayout(activity).apply {
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            addView(box, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        fun mkItem(icon: String, label: String, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = "$icon  $label"
                textSize = 14.5f
                setTextColor(C_TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
                isClickable = true
                setOnClickListener { onClick() }
            }

        val popup = PopupWindow(outer, activity.dp(150) + activity.dp(16), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            // 透明背景：真正的外观画在 box 上，这样手动缩放动画才不会被窗口背景挡住
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        // 收起：先播完动画再 dismiss
        fun close(action: () -> Unit) {
            box.animate().alpha(0f).scaleY(0.75f)
                .setDuration(120)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    popup.dismiss()
                    action()
                }
                .start()
        }

        box.addView(mkItem("📷", activity.getString(R.string.s_270)) { close { takePhoto() } }, activity.matchWrap())
        box.addView(mkItem("🖼", activity.getString(R.string.s_271)) { close { host.attachmentHandler.pickChatImage() } }, activity.matchWrap())
        box.addView(mkItem("📄", activity.getString(R.string.s_295)) { close { host.attachmentHandler.pickChatFile() } }, activity.matchWrap())

        // 先手动量一次，在 show 之前就把动画初值设好（否则会闪一帧全尺寸）
        box.measure(
            View.MeasureSpec.makeMeasureSpec(activity.dp(150), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        box.pivotY = box.measuredHeight.toFloat()
        box.alpha = 0f
        box.scaleY = 0.72f

        // 向上展开：弹窗底边贴在输入栏上沿
        val loc = IntArray(2)
        host.inputBar.getLocationOnScreen(loc)
        val screenH = activity.resources.displayMetrics.heightPixels
        popup.showAtLocation(
            host.inputBar, Gravity.BOTTOM or Gravity.END, activity.dp(10) - activity.dp(8), screenH - loc[1] + activity.dp(6)
        )

        // 入场动画：从底部往上长出来 + 淡入（不依赖 PopupWindow 的系统窗口动画）
        box.animate().alpha(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 安卓 9 及以下写相册/拍照需要运行时 WRITE_EXTERNAL_STORAGE；安卓 10+ 走 MediaStore，免权限，直接执行。 */
    fun withLegacyStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingStorageAction = action
        activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM_STORAGE)
    }

    /** Parte de adjuntos de onRequestPermissionsResult (REQ_PERM_STORAGE). true = consumido. */
    fun onStoragePermissionResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQ_PERM_STORAGE) return false
        val act = pendingStorageAction
        pendingStorageAction = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) act?.invoke()
        else host.toast(activity.getString(R.string.s_294))
        return true
    }

    private fun takePhoto() = withLegacyStorage { doTakePhoto() }

    private fun doTakePhoto() {
        val uri = try {
            activity.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ponko_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
            )
        } catch (_: Throwable) {
            null
        }
        if (uri == null) { host.toast(activity.getString(R.string.s_277)); return }
        pendingCameraUri = uri
        try {
            activity.startActivityForResult(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                },
                REQ_TAKE_PHOTO
            )
        } catch (_: Throwable) {
            host.toast(activity.getString(R.string.s_274))
        }
    }

    /** Bitmap → JPEG 字节（作为 Content.ImageBytes 送给 LiteRT-LM）。 */
    fun bitmapToJpeg(bmp: Bitmap, quality: Int = 88): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun handleTakePhotoResult(resultCode: Int) {
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (resultCode != Activity.RESULT_OK || uri == null) return
        host.scope.launch {
            val bmp = withContext(Dispatchers.IO) { host.attachmentHandler.decodeChatImage(uri) }
            if (bmp == null) {
                host.toast(activity.getString(R.string.s_275))
                return@launch
            }
            val jpeg = withContext(Dispatchers.IO) { bitmapToJpeg(bmp) }
            host.attachmentHandler.addPendingImage(bmp, jpeg)
        }
    }

    /** URIs de un resultado, venga como seleccion multiple o simple. */
    private fun resultUris(data: Intent?): List<Uri> {
        val clip = data?.clipData
        if (clip != null) {
            val out = ArrayList<Uri>(clip.itemCount)
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
            return out
        }
        return listOfNotNull(data?.data)
    }

    fun handlePickedChatImages(data: Intent?) {
        val uris = resultUris(data)
        if (uris.isEmpty()) return
        host.scope.launch {
            val added = host.attachmentHandler.importPickedImages(uris) { bitmapToJpeg(it) }
            if (added == 0 && host.attachmentHandler.getPendingImages().isEmpty()) {
                host.toast(activity.getString(R.string.s_275))
            }
        }
    }

    fun handlePickedFiles(data: Intent?) {
        val uris = resultUris(data)
        if (uris.isEmpty()) return
        host.scope.launch {
            val err = host.attachmentHandler.importPickedFiles(uris)
            if (err != null) host.toast(err)
        }
    }
}
