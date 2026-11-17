package dz.latchi.cutshort.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

class OkDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody("application/json".toMediaType())
        val rb = okhttp3.Request.Builder().url(request.url())
        request.headers().forEach { (k, vals) -> vals.forEach { rb.addHeader(k, it) } }
        rb.method(request.httpMethod(), body)
        client.newCall(rb.build()).execute().use { r ->
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string() ?: "", r.request.url.toString())
        }
    }
}

object Yt {
    @Volatile private var inited = false
    fun init(ctx: android.content.Context) {
        if (inited) return
        synchronized(this) { if (!inited) { NewPipe.init(ctx, OkDownloader()); inited = true } }
    }

    data class Info(val title: String, val durationSec: Int, val streamUrl: String)

    fun extract(link: String): Info {
        val svc = NewPipe.getService("YouTube")
        val info = StreamInfo.getInfo(svc, link)
        val progressive = info.videoStreams.filter { !it.isVideoOnly }
        val v = progressive.firstOrNull { it.mediaFormat?.suffix == "mp4" }
            ?: progressive.firstOrNull()
            ?: throw IllegalStateException("ما لقيتنش صيغة MP4 مباشرة لهذا الفيديو")
        return Info(info.name ?: "فيديو", info.duration, v.content)
    }

    fun normalize(url: String): String? {
        val re = Regex("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
        return re.find(url)?.let { "https://www.youtube.com/watch?v=${it.groupValues[1]}" }
    }
}
