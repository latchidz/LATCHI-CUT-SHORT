package dz.latchi.cutshort.core

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.effect.Crop
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class Orientation { VERTICAL, HORIZONTAL }

/// ✂️ محرك القص — Media3 Transformer (كودك عتادي سريع) + كشف مشاهد بتحليل الإطارات
object Engine {

    data class Probe(val durationMs: Long, val width: Int, val height: Int)

    fun probe(path: String): Probe {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            return Probe(dur, w, h)
        } finally { r.release() }
    }

    /// 👁 حدود المشاهد: فرق الإطارات المفتاحية (خوارزمية بصرية — بلا AI)
    fun sceneBoundaries(path: String, durationMs: Long): List<Double> {
        val r = MediaMetadataRetriever()
        val bounds = mutableListOf<Double>()
        try {
            r.setDataSource(path)
            var prev: Bitmap? = null
            var tUs = 0L
            var lastBound = 0.0
            while (tUs < durationMs) {
                val f = try { r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Exception) { null }
                if (f != null) {
                    val small = Bitmap.createScaledBitmap(f, 32, 18, true)
                    val p = prev
                    if (p != null) {
                        val d = diff(p, small)
                        val sec = tUs / 1_000_000.0
                        if (d > 22 && sec - lastBound >= 20 && (durationMs / 1000.0) - sec >= 15) {
                            bounds.add(sec); lastBound = sec
                            if (bounds.size >= 9) break
                        }
                    }
                    prev = small; f.recycle()
                }
                tUs += 1_000_000L
            }
        } catch (_: Exception) { } finally { r.release() }
        return bounds
    }

    private fun diff(a: Bitmap, b: Bitmap): Double {
        val pa = IntArray(32 * 18); val pb = IntArray(32 * 18)
        a.getPixels(pa, 0, 32, 0, 0, 32, 18)
        b.getPixels(pb, 0, 32, 0, 0, 32, 18)
        var sum = 0.0
        for (i in pa.indices) {
            val ga = (pa[i] shr 16 and 0xff) * 3 + (pa[i] shr 8 and 0xff) * 6 + (pa[i] and 0xff)
            val gb = (pb[i] shr 16 and 0xff) * 3 + (pb[i] shr 8 and 0xff) * 6 + (pb[i] and 0xff)
            sum += abs(ga - gb)
        }
        return sum / (32 * 18 * 255.0 * 10.0) * 255.0
    }

    /// ✂️ قص + تحويل للمقاس المطلوب (عمودي 1080×1920 أو أفقي 1920×1080) بقص وسط احترافي
    suspend fun cut(ctx: Context, src: String, startMs: Long, endMs: Long, out: String, w: Int, h: Int, orientation: Orientation): Boolean {
        val done = CompletableDeferred<Boolean>()
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs).setEndPositionMs(endMs).build()
        val (cl, cr, cb, ct) = centerCropWindow(w, h, orientation)
        val targetHeight = if (orientation == Orientation.VERTICAL) 1920 else 1080
        val effects = Effects(emptyList(), listOf(Crop(cl, cr, cb, ct), Presentation.createForHeight(targetHeight)))
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()
        ).setEffects(effects).build()

        val transformer = Transformer.Builder(ctx)
            .setListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(true) }
                override fun onError(composition: Composition, exportResult: ExportResult, e: androidx.media3.transformer.ExportException) { done.complete(false) }
            })
            .build()
        transformer.start(item, out)
        val ph = ProgressHolder()
        while (!done.isCompleted) { transformer.getProgress(ph); delay(250) }
        return done.await()
    }

    private fun centerCropWindow(w: Int, h: Int, o: Orientation): FloatArray {
        if (w <= 0 || h <= 0) return floatArrayOf(-1f, 1f, -1f, 1f)
        val src = w.toFloat() / h
        val tgt = if (o == Orientation.VERTICAL) 9f / 16f else 16f / 9f
        return if (src > tgt) {
            val x = tgt / src
            floatArrayOf(-x, x, -1f, 1f)
        } else {
            val y = src / tgt
            floatArrayOf(-1f, 1f, -y, y)
        }
    }
}
