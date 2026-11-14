// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai.draw

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.yumenode.ai.R
import java.io.File
import java.nio.FloatBuffer

/**
 * Image tagging (Tagger) engine.
 *
 * Runs user-imported Danbooru-style `.onnx` tagger models (e.g. WD14 with `selected_tags.csv`) via **ONNX Runtime**,
 * turning an image back into Danbooru-style tags (`1girl, solo, ...`) -- exactly the prompts SD1.5 / SDXL anime models expect.
 *
 * Independent of sd.cpp: models are not bundled or downloaded, only user-imported local files are read.
 * Backend follows the "Models" page run mode: CPU = XNNPACK/CPU EP; GPU = NNAPI.
 */
object TaggerEngine {

    /** One row of the tag table: name + category (0=general, 4=character, 9=rating; 9 is skipped in results) */
    data class Tag(val name: String, val category: Int)

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tags: List<Tag> = emptyList()
    private var inputName: String = ""
    private var inSize: Int = 448
    private var nhwc: Boolean = true
    private var modelFile: File? = null
    private var csvFile: File? = null
    private var usingGpu: Boolean = false

    fun isLoaded(): Boolean = session != null
    fun loadedWithGpu(): Boolean = usingGpu
    fun tagCount(): Int = tags.size
    fun fileNames(): Pair<String?, String?> = modelFile?.name to csvFile?.name
    fun backendLabel(): String = if (usingGpu) "NNAPI" else "CPU"

    /** Parse selected_tags.csv: `tag_id,name,category,count`, row order = model output index */
    fun parseCsv(file: File): List<Tag> {
        val out = ArrayList<Tag>()
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                val parts = line.split(',')
                if (parts.size < 3) return@forEachLine
                val name = parts[1].trim()
                val cat = parts[2].trim().toIntOrNull() ?: 0
                if (name.isEmpty() || name.equals("name", true)) return@forEachLine
                out.add(Tag(name, cat))
            }
        }
        return out
    }

    /**
     * Load model + tag table. Returns null on success, otherwise an error message.
     * Can take seconds, so always call on an IO thread.
     */
    fun load(ctx: Context, model: File, csv: File, useGpu: Boolean, threads: Int): String? {
        return try {
            unload()
            val tagList = parseCsv(csv)
            if (tagList.isEmpty()) return ctx.getString(R.string.s_384)

            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            // Caller computes the thread count from real usable cores (InferConfig); here we only clamp the lower bound
            // and never cap -- otherwise a user-set 12 threads would be silently cut back to 8 (fix 2026-10-13).
            runCatching { opts.setIntraOpNumThreads(threads.coerceAtLeast(1)) }
            var gpuOk = false
            if (useGpu) {
                gpuOk = runCatching { opts.addNnapi(); true }.getOrDefault(false)
            }

            val s = e.createSession(model.absolutePath, opts)
            val inputEntry = s.inputInfo.entries.firstOrNull()
            if (inputEntry == null) {
                runCatching { s.close() }
                return ctx.getString(R.string.s_385)
            }
            inputName = inputEntry.key
            val shape = (inputEntry.value.info as? TensorInfo)?.shape
            if (shape != null && shape.size == 4) {
                nhwc = shape[3] == 3L
                inSize = when {
                    nhwc -> shape[1].toInt()
                    shape[1] == 3L -> shape[2].toInt()
                    else -> shape[1].toInt()
                }
            }
            if (inSize <= 0) inSize = 448

            env = e
            session = s
            tags = tagList
            modelFile = model
            csvFile = csv
            usingGpu = gpuOk
            null
        } catch (t: Throwable) {
            unload()
            t.message ?: t.javaClass.simpleName
        }
    }

    fun unload() {
        runCatching { session?.close() }
        session = null
        modelFile = null
        csvFile = null
        tags = emptyList()
        usingGpu = false
    }

    /**
     * Tag an image. Returns (tag, score) pairs sorted by score descending; below threshold is dropped, at most topK (<=0 = unlimited).
     *
     * rgbOrder = false uses BGR (WD official preprocessing, default), true uses RGB (a few re-exported models may
     * already bake the BGR conversion in; those need RGB).
     */
    fun run(bitmap: Bitmap, threshold: Float, topK: Int, rgbOrder: Boolean = false): List<Pair<String, Float>> {
        val s = session ?: return emptyList()
        val e = env ?: return emptyList()
        val n = inSize
        val buf = preprocess(bitmap, n, rgbOrder)
        val shape = if (nhwc) {
            longArrayOf(1, n.toLong(), n.toLong(), 3)
        } else {
            longArrayOf(1, 3, n.toLong(), n.toLong())
        }
        val tensor = OnnxTensor.createTensor(e, buf, shape)
        val result = s.run(mapOf(inputName to tensor))
        try {
            val out = result.get(0) as OnnxTensor
            val fb = out.floatBuffer
            val raw = FloatArray(fb.remaining())
            fb.get(raw)

            // Output may be raw logits (negative / >1) or already probabilities -- detect automatically
            val needSigmoid = raw.any { it < 0f || it > 1f }
            val limit = minOf(raw.size, tags.size)
            val list = ArrayList<Pair<String, Float>>()
            for (i in 0 until limit) {
                val t = tags[i]
                if (t.category == 9) continue // skip rating tags
                val v = if (needSigmoid) sigmoid(raw[i]) else raw[i]
                if (v >= threshold) list.add(t.name to v)
            }
            list.sortByDescending { it.second }
            return if (topK in 1 until list.size) list.subList(0, topK).toList() else list
        } finally {
            runCatching { result.close() }
            runCatching { tensor.close() }
        }
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + kotlin.math.exp(-x))

    /**
     * Preprocess: white-background center letterbox -> n×n -> **BGR, 0~255**.
     *
     * Key point: WD models (WD14 etc.) expect **raw 0~255 pixel values** and must never be divided by 255 -- doing so makes them
     * read color images as low-contrast oddities and reliably output wrong tags like monochrome / greyscale / no_humans.
     * Channels are BGR (matching training). nhwc=true interleaves channels, otherwise planes (NCHW).
     */
    private fun preprocess(src: Bitmap, n: Int, rgbOrder: Boolean): FloatBuffer {
        val scale = n.toFloat() / maxOf(src.width, src.height)
        val dw = maxOf(1, (src.width * scale).toInt())
        val dh = maxOf(1, (src.height * scale).toInt())
        val scaled = if (dw == src.width && dh == src.height) src
                     else Bitmap.createScaledBitmap(src, dw, dh, true)
        val square = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, (n - dw) / 2f, (n - dh) / 2f, null)

        val px = IntArray(n * n)
        square.getPixels(px, 0, n, 0, 0, n, n)
        val buf = FloatBuffer.allocate(n * n * 3)
        // BGR (default): B,G,R; RGB: R,G,B. shifts map to the three channels in that order
        val shifts = if (rgbOrder) intArrayOf(16, 8, 0) else intArrayOf(0, 8, 16)
        if (nhwc) {
            for (p in px) {
                for (sh in shifts) buf.put(((p shr sh) and 0xFF).toFloat())
            }
        } else {
            for (sh in shifts) {                              // three planes
                for (p in px) buf.put(((p shr sh) and 0xFF).toFloat())
            }
        }
        buf.rewind()
        if (scaled !== src) runCatching { scaled.recycle() }
        runCatching { square.recycle() }
        return buf
    }
}
