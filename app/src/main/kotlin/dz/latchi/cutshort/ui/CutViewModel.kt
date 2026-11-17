package dz.latchi.cutshort.ui

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dz.latchi.cutshort.core.Agent
import dz.latchi.cutshort.core.AgentMsg
import dz.latchi.cutshort.core.Engine
import dz.latchi.cutshort.core.Orientation
import dz.latchi.cutshort.core.Segment
import dz.latchi.cutshort.core.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class Mode { AI, SCENE, TIME }

data class JobState(
    val title: String, val phase: String, val pct: Int = 0,
    val done: Boolean = false, val err: String? = null, val uri: Uri? = null
)

class CutViewModel(app: Application) : AndroidViewModel(app) {

    private val _jobs = MutableStateFlow<List<JobState>>(emptyList())
    val jobs: StateFlow<List<JobState>> = _jobs
    private val _chat = MutableStateFlow<List<AgentMsg>>(
        listOf(AgentMsg(false, "سلام 👋 أنا وكيل LATCHI CUT SHORT.\nالصق رابط يوتيوب هنا وقول لي «اعطيني الشورتات كل 30 ثانية عمودي» — أو استعمل شاشة القص مباشرة."))
    )
    val chat: StateFlow<List<AgentMsg>> = _chat
    val busy = MutableStateFlow(false)
    val url = MutableStateFlow("")
    val lastPlan = MutableStateFlow<List<Segment>?>(null)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    fun send(text: String) {
        if (text.isBlank()) return
        _chat.value = _chat.value + AgentMsg(true, text)
        viewModelScope.launch(Dispatchers.IO) {
            val reply = Agent.ask(_chat.value, text)
            if (reply.plan != null) {
                lastPlan.value = reply.plan
                if (url.value.isBlank()) Yt.normalize(text)?.let { url.value = it }
            }
            _chat.value = _chat.value + reply
        }
    }

    fun startWithPlan() {
        val p = lastPlan.value ?: return
        start(url.value, Mode.AI, 45, Orientation.VERTICAL, p)
    }

    fun start(rawUrl: String, mode: Mode, segSec: Int, orientation: Orientation, plan: List<Segment>? = null) {
        if (busy.value) return
        val norm = Yt.normalize(rawUrl) ?: run {
            _jobs.value = _jobs.value + JobState("—", "رابط يوتيوب غير صالح", 0, false, "الصق رابط يوتيوب صحيح")
            return
        }
        busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try { run(norm, mode, segSec, orientation, plan) } catch (e: Exception) {
                _jobs.value = _jobs.value + JobState("—", "فشل", 0, false, e.message ?: e.toString())
            } finally { busy.value = false }
        }
    }

    private fun setJob(i: Int, j: JobState) {
        _jobs.value = _jobs.value.toMutableList().also { if (i < it.size) it[i] = j }
    }

    private suspend fun run(url: String, mode: Mode, segSec: Int, orientation: Orientation, plan: List<Segment>?) {
        val ctx = getApplication<Application>()
        Yt.init(ctx)
        val idx = _jobs.value.size
        _jobs.value = _jobs.value + JobState("تحليل", "جاري استخراج معلومات الفيديو…")
        val info = Yt.extract(url)
        setJob(idx, JobState(info.title, "تحميل الفيديو الأصلي…", 1))

        val src = File(ctx.cacheDir, "src_${System.currentTimeMillis()}.mp4")
        http.newCall(okhttp3.Request.Builder().url(info.streamUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("فشل التحميل (${r.code})")
            val total = r.body?.contentLength() ?: -1L
            r.body?.byteStream()?.use { inp ->
                src.outputStream().use { out ->
                    val buf = ByteArray(1 << 16); var done = 0L
                    var n = inp.read(buf)
                    while (n != -1) {
                        out.write(buf, 0, n); done += n
                        if (total > 0) setJob(idx, JobState(info.title, "تحميل الفيديو الأصلي…", (done * 80 / total).toInt().coerceAtMost(80)))
                        n = inp.read(buf)
                    }
                }
            } ?: throw Exception("جسم التحميل فارغ")
        }

        val pr = Engine.probe(src.absolutePath)
        val dur = (pr.durationMs / 1000.0).takeIf { it > 1 } ?: info.durationSec.toDouble()

        var segments: List<Segment>? = plan
        var aiNote = ""
        if (segments == null && mode == Mode.AI) {
            setJob(idx, JobState(info.title, "🧠 الذكاء الاصطناعي يخطط المقاطع…", 82))
            segments = Agent.planFor(url, segSec)
            if (segments != null) aiNote = " (خطة AI)"
            else {
                segments = scenes(src.absolutePath, dur, pr.durationMs)
                aiNote = if (Agent.aiAvailable) " (AI بلا رصيد → محرك المشاهد)" else " (محرك المشاهد الذكي)"
            }
        }
        if (segments == null) segments = when (mode) {
            Mode.TIME -> split(dur, segSec)
            else -> scenes(src.absolutePath, dur, pr.durationMs)
        }
        if (segments.isEmpty()) throw Exception("ما لقيتش مقاطع مناسبة — جرّب وضعًا آخر")

        val outUris = mutableListOf<Uri>()
        segments.forEachIndexed { i, seg ->
            setJob(idx, JobState(info.title, "قص المقطع ${i + 1}/${segments.size}: ${seg.title}$aiNote", 82 + (i * 18 / segments.size)))
            val out = File(ctx.cacheDir, "cut_${System.currentTimeMillis()}_$i.mp4")
            val ok = Engine.cut(ctx, src.absolutePath, (seg.start * 1000).toLong(), (seg.end * 1000).toLong(), out.absolutePath, pr.width, pr.height, orientation)
            if (ok && out.length() > 0) {
                val uri = saveGallery(out, "${sanitize(info.title)}_${i + 1}.mp4")
                outUris.add(uri)
                setJob(idx, JobState(info.title, "تم ✓ ${outUris.size}/${segments.size} — ${seg.title}", 82 + ((i + 1) * 18 / segments.size), uri = uri))
            } else {
                setJob(idx, JobState(info.title, "تعذّر قص المقطع ${i + 1}", err = "فشل الترميز في المقطع ${i + 1}"))
            }
            out.delete()
        }
        src.delete()
        setJob(idx, JobState(info.title, "اكتمل ✓ ${outUris.size} شورت جاهز للمشاركة", 100, done = true, uri = outUris.firstOrNull()))
    }

    private fun split(dur: Double, segSec: Int): List<Segment> {
        val n = min(12, (dur / segSec).toInt())
        if (n <= 0) return listOf(Segment(0.0, dur, "الفيديو كامل"))
        return (0 until n).map { Segment(it * segSec.toDouble(), min(dur, (it + 1) * segSec.toDouble()), "مقطع ${it + 1}") }
            .filter { it.end - it.start >= 10 }
    }

    private fun scenes(path: String, dur: Double, durMs: Long): List<Segment> {
        val times = Engine.sceneBoundaries(path, durMs)
        if (times.size < 3) return split(dur, 45)
        val bounds = mutableListOf(0.0)
        for (t in times) { if (t - bounds.last() >= 20 && dur - t >= 15) bounds.add(t); if (bounds.size >= 9) break }
        bounds.add(dur)
        val segs = mutableListOf<Segment>()
        for (i in 0 until bounds.size - 1) {
            val s = bounds[i]; val e = bounds[i + 1]
            if (e - s >= 15 && e - s <= 75) segs.add(Segment(s, e, "مشهد ${i + 1}"))
        }
        return segs.take(8).ifEmpty { split(dur, 45) }
    }

    private fun saveGallery(file: File, name: String): Uri {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/LATCHI CUT SHORT")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("تعذّر الحفظ في المعرض")
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "LATCHI CUT SHORT")
            dir.mkdirs()
            val dst = File(dir, name)
            file.copyTo(dst, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), null, null)
            Uri.fromFile(dst)
        }
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9\\u0600-\\u06FF_-]"), "_").take(40)
}
