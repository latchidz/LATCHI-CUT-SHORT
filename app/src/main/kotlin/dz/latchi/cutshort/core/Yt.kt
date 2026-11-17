package dz.latchi.cutshort.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

/**
 * v1.3.0 — multi-layer YouTube extraction.
 *
 * Lesson from the 2026 YouTube changes (NewPipe#13082, yt-dlp#16212):
 * a single extraction path WILL keep breaking. So we chain every known
 * working path, exactly like yt-dlp does with its player clients:
 *
 *   1. NewPipe Extractor 0.27.x  (retry once — playability can be transient)
 *   2. InnerTube ANDROID client  (direct progressive mp4 urls, no cipher)
 *   3. InnerTube IOS client      (same trick, different device profile)
 *   4. InnerTube TV-embed client (last resort for embeddable videos)
 *
 * All layers run on the user's own phone IP (residential) which YouTube
 * bot-checks far less than datacenter IPs — this is the Android app's
 * structural advantage over any server-side (Netlify/Vercel/VPS) design.
 */
object Yt {
    @Volatile private var inited = false

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun init(ctx: android.content.Context) {
        if (inited) return
        synchronized(this) { if (!inited) { NewPipe.init(OkDownloader()); inited = true } }
    }

    data class Info(val title: String, val durationSec: Int, val streamUrl: String)

    fun normalize(url: String): String? {
        val re = Regex("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
        return re.find(url)?.let { "https://www.youtube.com/watch?v=${it.groupValues[1]}" }
    }

    fun videoId(link: String): String? {
        val re = Regex("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
        return re.find(link)?.groupValues?.get(1)
    }

    // ───────────────────────── layer 1: NewPipe ─────────────────────────

    private fun viaNewPipe(link: String): Info {
        val svc = NewPipe.getService("YouTube")
        val info = StreamInfo.getInfo(svc, link)
        val progressive = info.videoStreams.filter { !it.isVideoOnly }
        val v = progressive.firstOrNull { it.format?.suffix == "mp4" }
            ?: progressive.firstOrNull()
            ?: throw IllegalStateException("لا توجد صيغة MP4 مباشرة لهذا الفيديو")
        return Info(info.name ?: "فيديو", info.duration.toInt(), v.content)
    }

    // ─────────────── layers 2-4: InnerTube player clients ───────────────

    private fun androidClient(): JSONObject {
        val c = JSONObject()
        c.put("clientName", "ANDROID")
        c.put("clientVersion", "19.09.37")
        c.put("androidSdkVersion", 30)
        c.put("hl", "en")
        c.put("gl", "US")
        return c
    }

    private fun iosClient(): JSONObject {
        val c = JSONObject()
        c.put("clientName", "IOS")
        c.put("clientVersion", "19.09.3")
        c.put("deviceModel", "iPhone14,3")
        c.put("hl", "en")
        c.put("gl", "US")
        return c
    }

    private fun tvEmbedClient(): JSONObject {
        val c = JSONObject()
        c.put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
        c.put("clientVersion", "2.0")
        c.put("clientScreen", "EMBED")
        c.put("hl", "en")
        c.put("gl", "US")
        return c
    }

    private fun userAgentFor(clientName: String): String {
        return when (clientName) {
            "ANDROID" -> "com.google.android.youtube/19.09.37 (Linux; U; Android 11; SM-G973F) gzip"
            "IOS" -> "com.google.ios.youtube/19.09.3 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X; en_US)"
            else -> "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15"
        }
    }

    private fun innerTubePlayer(videoId: String, client: JSONObject): JSONObject {
        val body = JSONObject()
        val ctx = JSONObject()
        ctx.put("client", client)
        body.put("context", ctx)
        body.put("videoId", videoId)
        body.put("contentCheckOk", true)
        body.put("racyCheckOk", true)
        if (client.optString("clientName") == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
            val third = JSONObject()
            third.put("embedUrl", "https://www.youtube.com/")
            body.put("thirdParty", third)
        }
        val req = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgentFor(client.optString("clientName")))
            .header("X-YouTube-Client-Name", if (client.optString("clientName") == "ANDROID") "3" else "5")
            .header("X-YouTube-Client-Version", client.optString("clientVersion"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw IllegalStateException("InnerTube HTTP ${r.code}")
            }
            return JSONObject(text)
        }
    }

    private fun pickFromPlayer(resp: JSONObject): Info {
        val status = resp.optJSONObject("playabilityStatus")
        val st = status?.optString("status") ?: ""
        if (st != "OK") {
            val reason = status?.optString("reason") ?: ""
            throw IllegalStateException("playability=$st $reason")
        }
        val sd = resp.optJSONObject("streamingData")
            ?: throw IllegalStateException("لا توجد بيانات بث في الرد")
        val formats = sd.optJSONArray("formats")
            ?: throw IllegalStateException("لا توجد صيغ progressive")
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.contains("video/mp4")) continue
            // progressive = has an audio codec inside the same mp4
            if (!mime.contains("mp4a")) continue
            // only formats with a ready-to-use url (no signatureCipher)
            if (!f.has("url")) continue
            val score = f.optInt("height", 0) * 10000 + f.optInt("bitrate", 0) / 1000
            if (score > bestScore) {
                bestScore = score
                best = f
            }
        }
        val pick = best ?: throw IllegalStateException("لا يوجد MP4 كامل (صورة+صوت) متاح")
        val vd = resp.optJSONObject("videoDetails")
        val title = vd?.optString("title") ?: "فيديو"
        val dur = vd?.optString("lengthSeconds")?.toIntOrNull() ?: 0
        return Info(title, dur, pick.getString("url"))
    }

    private fun viaInnerTube(videoId: String, client: JSONObject): Info {
        val resp = innerTubePlayer(videoId, client)
        return pickFromPlayer(resp)
    }

    // ─────────────────────── public entry point ───────────────────────

    fun extract(link: String): Info {
        val errors = StringBuilder()

        // Layer 1: NewPipe (twice — playability errors are often transient)
        try {
            return viaNewPipe(link)
        } catch (e: Exception) {
            errors.append("NewPipe: ").append((e.message ?: e.javaClass.simpleName).take(90)).append('\n')
        }
        try {
            Thread.sleep(900)
        } catch (_: InterruptedException) {
        }
        try {
            return viaNewPipe(link)
        } catch (e: Exception) {
            errors.append("NewPipe-retry: ").append((e.message ?: e.javaClass.simpleName).take(90)).append('\n')
        }

        val id = videoId(link)
        if (id != null) {
            // Layer 2: InnerTube ANDROID
            try {
                return viaInnerTube(id, androidClient())
            } catch (e: Exception) {
                errors.append("InnerTube-Android: ").append((e.message ?: e.javaClass.simpleName).take(90)).append('\n')
            }
            // Layer 3: InnerTube IOS
            try {
                return viaInnerTube(id, iosClient())
            } catch (e: Exception) {
                errors.append("InnerTube-iOS: ").append((e.message ?: e.javaClass.simpleName).take(90)).append('\n')
            }
            // Layer 4: InnerTube TV embed
            try {
                return viaInnerTube(id, tvEmbedClient())
            } catch (e: Exception) {
                errors.append("InnerTube-TV: ").append((e.message ?: e.javaClass.simpleName).take(90)).append('\n')
            }
        }

        throw IllegalStateException(
            "تعذر استخراج الفيديو بكل الطرق المتاحة.\n" +
                "قد يكون الفيديو مقيدًا (عمر/حقوق) أو غير متاح في منطقتك.\n" +
                "التفاصيل:\n$errors"
        )
    }
}
