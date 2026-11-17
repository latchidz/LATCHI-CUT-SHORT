package dz.latchi.cutshort.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Segment(val start: Double, val end: Double, val title: String)
data class AgentMsg(val mine: Boolean, val text: String, val plan: List<Segment>? = null)

/// 🤖 الوكيل الذكي — إعدادات AI تُجلب من قناة النشر (بدون توكن داخل الكود)
object Agent {
    private const val CFG_URL = "https://raw.githubusercontent.com/latchidz/LATCHI-RELEASES/main/cut_ai.json"
    private var cfgToken: String? = null
    private var cfgModel: String = "Qwen/Qwen2.5-72B-Instruct"
    private var cfgTried = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    private fun loadCfg() {
        if (cfgTried) return
        cfgTried = true
        try {
            val r = client.newCall(okhttp3.Request.Builder().url(CFG_URL).build()).execute()
            if (r.isSuccessful) {
                val j = JSONObject(r.body?.string() ?: "")
                val parts = j.optJSONArray("hf_token_parts"); var tok = j.optString("hf_token", ""); if (tok.isBlank() && parts != null) { val sb = StringBuilder(); for (i in 0 until parts.length()) sb.append(parts.getString(i)); tok = sb.toString() }; cfgToken = tok.takeIf { it.isNotBlank() }
                cfgModel = j.optString("model", cfgModel)
            }
        } catch (_: Exception) { }
    }

    val aiAvailable: Boolean get() { loadCfg(); return cfgToken != null }

    private const val SYS =
        "أنت وكيل تطبيق LATCHI CUT SHORT لقص فيديوهات يوتيوب إلى شورتات بالذكاء الاصطناعي. " +
                "إذا أرسل المستخدم رابط يوتيوب (أو طلب قص/شورتات)، اختر لحظات مثيرة متوقعة وأرد ONLY JSON: " +
                "{\"segments\":[{\"start\":0,\"end\":45,\"title\":\"عنوان المقطع\"}]} " +
                "من 3 إلى 6 مقاطع، طول كل مقطع حسب طلب المستخدم (افتراضيًا 30-60 ثانية). " +
                "في أي حالة أخرى ترد بالعربية مساعدة قصيرة ودودة."

    class NoCredits : Exception("no-credits")

    suspend fun ask(history: List<AgentMsg>, text: String): AgentMsg = withContext(Dispatchers.IO) {
        try { hf(history, text) } catch (e: NoCredits) { local(text, true) } catch (e: Exception) { local(text, false) }
    }

    /// 🧠 تخطيط ذكي مباشر لرابط (يُستدعى من وضع «قص بالذكاء»)
    suspend fun planFor(url: String, segSec: Int): List<Segment>? = withContext(Dispatchers.IO) {
        try {
            val reply = hf(emptyList(), "هذا الرابط: $url — اعطني خطة شورتات مدة كل واحد تقريبا $segSec ثانية")
            reply.plan
        } catch (_: Exception) { null }
    }

    private fun hf(history: List<AgentMsg>, text: String): AgentMsg {
        loadCfg()
        val tok = cfgToken ?: throw NoCredits()
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", SYS))
        history.takeLast(6).forEach {
            msgs.put(JSONObject().put("role", if (it.mine) "user" else "assistant").put("content", it.text))
        }
        msgs.put(JSONObject().put("role", "user").put("content", text))
        val body = JSONObject().put("model", cfgModel).put("messages", msgs).put("max_tokens", 600).toString()
        val req = okhttp3.Request.Builder().url("https://router.huggingface.co/v1/chat/completions")
            .addHeader("Authorization", "Bearer $tok")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 402 || r.code == 401) throw NoCredits()
            if (!r.isSuccessful) throw Exception("hf ${r.code}")
            val j = JSONObject(r.body?.string() ?: throw Exception("empty"))
            val content = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val plan = parsePlan(content)
            return if (plan != null) AgentMsg(false, "🧠 خطة الذكاء الاصطناعي جاهزة: ${plan.size} مقاطع ✅", plan)
            else AgentMsg(false, content)
        }
    }

    fun parsePlan(s: String): List<Segment>? = try {
        val st = s.indexOf('{'); val en = s.lastIndexOf('}')
        if (st < 0 || en <= st) null
        else {
            val arr = JSONObject(s.substring(st, en + 1)).getJSONArray("segments")
            val out = mutableListOf<Segment>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Segment(o.optDouble("start", 0.0), o.optDouble("end", 0.0), o.optString("title", "مقطع ${i + 1}")))
            }
            out.filter { it.end > it.start }.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) { null }

    /// ⚡ وكيل محلي فوري بلا إنترنت (احتياطي الذكاء)
    fun local(text: String, creditsOut: Boolean): AgentMsg {
        val url = Yt.normalize(text)
        val every = Regex("(?:كل|طول)\\s*([0-9٠-٩]+)\\s*(?:ثانية|ث)").find(text)?.let { m ->
            m.groupValues[1].map { if (it in '٠'..'٩') ('0' + (it - '٠')) else it }.joinToString("").toIntOrNull()
        }
        return when {
            url != null && every != null -> AgentMsg(false, "وصلني الرابط 🎬 الخطة: مقطع كل $every ثانية${if (creditsOut) note() else ""} — نفّذ من شاشة القص (وضع زمني).")
            url != null -> AgentMsg(false, "وصلني الرابط 🎬${if (creditsOut) note() else ""} اختر في شاشة القص: 🤖 ذكاء / 👁 مشاهد / ⏱ زمني (15/30/45/60/90 ثانية) والاتجاه عمودي أو أفقي.")
            else -> AgentMsg(false, "أنا وكيل LATCHI CUT SHORT 🤖 الصق رابط يوتيوب هنا أو في شاشة القص، واختار المدة والاتجاه — وأنا نقصها لك شورتات جاهزة.")
        }
    }

    private fun note() = "\nℹ️ رصيد AI (HF) غير متوفر حاليًا — محرك المشاهد الذكي المحلي يخدم مكانه فورًا."
}
