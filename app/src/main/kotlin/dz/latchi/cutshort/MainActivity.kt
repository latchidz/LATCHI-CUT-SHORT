package dz.latchi.cutshort

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dz.latchi.cutshort.core.AgentMsg
import dz.latchi.cutshort.ui.CutViewModel
import dz.latchi.cutshort.ui.Mode
import dz.latchi.cutshort.core.Orientation

val Navy = Color(0xFF0A0E1E)
val Navy2 = Color(0xFF131A33)
val Gold = Color(0xFFF2C14E)
val Blue = Color(0xFF3E7BD6)
val Red = Color(0xFFE23A4E)

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent { App(shared) }
    }
}

@Composable
fun App(initialUrl: String?) {
    val vm: CutViewModel = viewModel()
    var tab by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(Mode.AI) }
    var segSec by remember { mutableStateOf(45) }
    var orient by remember { mutableStateOf(Orientation.VERTICAL) }
    LaunchedEffect(initialUrl) { if (initialUrl != null) vm.url.value = initialUrl }

    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, secondary = Blue, surface = Navy2, background = Navy)) {
        Scaffold(
            containerColor = Navy,
            topBar = {
                Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Red, Blue))).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text("🎬 LATCHI CUT SHORT", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                TabRow(selectedTabIndex = tab, containerColor = Navy, contentColor = Gold) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("✂️ القص") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("🤖 الوكيل") })
                }
                if (tab == 0) CutScreen(vm, mode, { mode = it }, segSec, { segSec = it }, orient, { orient = it })
                else AgentScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutScreen(
    vm: CutViewModel,
    mode: Mode, onMode: (Mode) -> Unit,
    segSec: Int, onSeg: (Int) -> Unit,
    orient: Orientation, onOrient: (Orientation) -> Unit
) {
    val jobs by vm.jobs.collectAsState()
    val busy by vm.busy.collectAsState()
    val url by vm.url.collectAsState()
    val plan by vm.lastPlan.collectAsState()
    val ctx = LocalContext.current

    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(url, { vm.url.value = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("الصق رابط يوتيوب هنا… https://youtu.be/…") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Blue))
        }
        item {
            Text("وضع القص", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == Mode.AI, { onMode(Mode.AI) }, label = { Text("🤖 ذكاء") })
                FilterChip(mode == Mode.SCENE, { onMode(Mode.SCENE) }, label = { Text("👁 مشاهد") })
                FilterChip(mode == Mode.TIME, { onMode(Mode.TIME) }, label = { Text("⏱ زمني") })
            }
        }
        item {
            Text("مدة كل مقطع", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { s ->
                    FilterChip(segSec == s, { onSeg(s) }, label = { Text("${s}ث") })
                }
            }
        }
        item {
            Text("اتجاه الشورت", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(orient == Orientation.VERTICAL, { onOrient(Orientation.VERTICAL) }, label = { Text("📱 عمودي 9:16") })
                FilterChip(orient == Orientation.HORIZONTAL, { onOrient(Orientation.HORIZONTAL) }, label = { Text("📺 أفقي 16:9") })
            }
        }
        item {
            Button(
                onClick = { vm.start(url, mode, segSec, orient, if (mode == Mode.AI) plan else null) },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    when (mode) {
                        Mode.AI -> if (plan != null) "🤖 نفّذ خطة الذكاء (${plan.size} مقاطع)" else "🤖 قص بالذكاء الاصطناعي"
                        Mode.SCENE -> "👁 قص بكشف المشاهد"
                        Mode.TIME -> "⏱ قص كل ${segSec} ثانية"
                    },
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            }
        }
        item {
            Text("الوضع 🤖: وكيل AI يخطط اللحظات المثيرة (عبر Hugging Face). إذا ما كانش رصيد → محرك كشف المشاهد الذكي المحلي يخدم فورًا. القص والترميز يتمان على جهازك بكودك عتادي سريع — الشورتات تتحفظ في Movies/LATCHI CUT SHORT.",
                color = Color(0xFF8A93B8), fontSize = 11.sp)
        }
        items(jobs) { j ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Navy2)) {
                Column(Modifier.padding(12.dp)) {
                    Text(j.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(j.phase, color = if (j.err == null) Gold else Red, fontSize = 12.sp)
                    j.err?.let { Text(it, color = Red, fontSize = 11.sp) }
                    if (!j.done && j.err == null) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(j.pct / 100f, Modifier.fillMaxWidth(), color = Gold, trackColor = Navy)
                    }
                    j.uri?.let { uri ->
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "مشاركة الشورت"))
                            }, label = { Text("📤 مشاركة") })
                            AssistChip(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }, label = { Text("▶️ عرض") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentScreen(vm: CutViewModel) {
    val chat by vm.chat.collectAsState()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat) { m ->
                val align = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart
                Box(Modifier.fillMaxWidth(), contentAlignment = align) {
                    Surface(shape = RoundedCornerShape(16.dp), color = if (m.mine) Blue else Navy2) {
                        Text(m.text, Modifier.padding(10.dp), color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
        val lastPlan by vm.lastPlan.collectAsState()
        if (lastPlan != null) {
            Button(onClick = { vm.startWithPlan() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                Text("⚡ نفّذ الخطة الآن: ${lastPlan!!.size} مقاطع")
            }
        }
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f),
                placeholder = { Text("اسأل الوكيل أو الصق الرابط…") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.send(text); text = "" }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy)) { Text("إرسال") }
        }
    }
}
