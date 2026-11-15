package com.example.autoclipai

import androidx.activity.compose.setContent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class ClipResult(
    val name: String,
    val url: String,
    val reason: String,
    val start: Double,
    val end: Double,
    val subtitleUrl: String
)

private data class ProcessResult(
    val jobId: String,
    val clips: List<ClipResult>,
    val duration: Float
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoClipScreen()
        }
    }
}

@Composable
fun AutoClipScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var instruction by remember {
        mutableStateOf("Cari bagian paling lucu dan menarik. Jangan potong punchline.")
    }
    var count by remember { mutableStateOf("3") }
    var maxDuration by remember { mutableStateOf("45") }
    var status by remember {
        mutableStateOf("Pilih video dan tulis instruksi AI.")
    }
    var serverStatus by remember {
        mutableStateOf("Server belum diperiksa")
    }
    var busy by remember { mutableStateOf(false) }
    var jobId by remember { mutableStateOf("") }
    var sourceDuration by remember { mutableFloatStateOf(0f) }
    var clips by remember { mutableStateOf(listOf<ClipResult>()) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var editStart by remember { mutableFloatStateOf(0f) }
    var editEnd by remember { mutableFloatStateOf(0f) }
    var editSubtitle by remember { mutableStateOf("") }
    var previewUrl by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { picked ->
        sourceUri = picked
        clips = emptyList()
        jobId = ""
        previewUrl = null
        sourceDuration = 0f

        status = if (picked != null) {
            "Video siap diproses."
        } else {
            "Belum ada video."
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "AutoClip AI",
                        style = MaterialTheme.typography.headlineLarge
                    )

                    Text("Editor video pendek dengan bantuan AI")

                    Text(
                        text = serverStatus,
                        style = MaterialTheme.typography.labelMedium
                    )

                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                serverStatus = try {
                                    checkServer()
                                } catch (e: Exception) {
                                    "Server tidak terjangkau: ${e.message}"
                                }
                            }
                        }
                    ) {
                        Text("PERIKSA SERVER")
                    }
                }

                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    picker.launch("video/*")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("UPLOAD VIDEO")
                            }

                            Text(
                                text = sourceUri?.let {
                                    DocumentFile.fromSingleUri(context, it)?.name
                                        ?: "Video dipilih"
                                } ?: "Belum ada video"
                            )

                            sourceUri?.let { uri ->
                                VideoPreview(
                                    uri = uri,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(210.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Instruksi AI",
                                style = MaterialTheme.typography.titleMedium
                            )

                            OutlinedTextField(
                                value = instruction,
                                onValueChange = { instruction = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                label = {
                                    Text("Contoh: cari bagian lucu / promosi / motivasi")
                                }
                            )

                            OutlinedTextField(
                                value = count,
                                onValueChange = {
                                    count = it.filter(Char::isDigit).take(2)
                                },
                                label = { Text("Jumlah clip (1–10)") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = maxDuration,
                                onValueChange = {
                                    maxDuration = it.filter(Char::isDigit).take(3)
                                },
                                label = { Text("Durasi maksimal (8–180 detik)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    Button(
                        enabled = sourceUri != null &&
                            !busy &&
                            instruction.isNotBlank(),
                        onClick = {
                            val selected = sourceUri

                            if (selected != null) {
                                scope.launch {
                                    busy = true
                                    status = "Mengunggah dan menganalisis video dengan AI..."

                                    try {
                                        val result = processVideo(
                                            context,
                                            selected,
                                            instruction,
                                            count.toIntOrNull() ?: 3,
                                            maxDuration.toIntOrNull() ?: 45
                                        )

                                        jobId = result.jobId
                                        clips = result.clips
                                        sourceDuration = result.duration
                                        editIndex = -1

                                        status = "Analisis AI selesai. Pilih clip untuk melihat atau mengedit timeline."
                                    } catch (e: Exception) {
                                        status = "Gagal: ${e.message}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (busy) "MEMPROSES..."
                            else "GENERATE CLIP DENGAN AI"
                        )
                    }
                }

                item {
                    Text(status)
                }

                if (clips.isNotEmpty()) {
                    item {
                        Text(
                            "Hasil & editor timeline",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                itemsIndexed(clips) { index, clip ->
                    Card {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                clip.name,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(clip.reason)

                            Text(
                                "Rentang: ${"%.1f".format(clip.start)} – ${"%.1f".format(clip.end)} detik"
                            )

                            OutlinedButton(
                                onClick = {
                                    previewUrl = clip.url
                                }
                            ) {
                                Text("PUTAR HASIL CLIP")
                            }

                            if (previewUrl == clip.url) {
                                VideoPreview(
                                    url = clip.url,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(210.dp)
                                )
                            }

                            Text(
                                "Subtitle: ${clip.subtitleUrl}",
                                style = MaterialTheme.typography.bodySmall
                            )

                            OutlinedButton(
                                onClick = {
                                    editIndex = index
                                    editStart = clip.start.toFloat()
                                    editEnd = clip.end.toFloat()
                                    editSubtitle = ""
                                }
                            ) {
                                Text("EDIT TIMELINE & SUBTITLE")
                            }

                            if (editIndex == index) {
                                HorizontalDivider()

                                Text(
                                    "Editor timeline",
                                    style = MaterialTheme.typography.titleSmall
                                )

                                Text("Mulai: ${"%.1f".format(editStart)} detik")

                                Slider(
                                    value = editStart.coerceIn(
                                        0f,
                                        (sourceDuration - 3f).coerceAtLeast(0f)
                                    ),
                                    onValueChange = {
                                        editStart = it.coerceAtMost(editEnd - 3f)
                                    },
                                    valueRange = 0f..(sourceDuration - 3f)
                                        .coerceAtLeast(0.1f)
                                )

                                Text("Akhir: ${"%.1f".format(editEnd)} detik")

                                Slider(
                                    value = editEnd.coerceIn(
                                        (editStart + 3f).coerceAtMost(sourceDuration),
                                        sourceDuration.coerceAtLeast(0.1f)
                                    ),
                                    onValueChange = {
                                        editEnd = it.coerceAtLeast(editStart + 3f)
                                    },
                                    valueRange = 0f..sourceDuration.coerceAtLeast(0.1f)
                                )

                                OutlinedTextField(
                                    value = editSubtitle,
                                    onValueChange = { editSubtitle = it },
                                    label = {
                                        Text("Ganti subtitle (opsional)")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2
                                )

                                Button(
                                    enabled = !busy &&
                                        editEnd > editStart + 2.9f,
                                    onClick = {
                                        scope.launch {
                                            busy = true

                                            try {
                                                val revised = reviseClip(
                                                    jobId,
                                                    index + 1,
                                                    editStart.toDouble(),
                                                    editEnd.toDouble(),
                                                    editSubtitle
                                                )

                                                clips = clips.toMutableList().also {
                                                    it[index] = revised
                                                }

                                                previewUrl = revised.url
                                                status = "Revisi berhasil dibuat."
                                            } catch (e: Exception) {
                                                status = "Revisi gagal: ${e.message}"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("SIMPAN & RENDER REVISI")
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Catatan: editor ini menyediakan preview dan pengaturan rentang waktu berbasis slider. Pelacakan wajah/pembicara otomatis belum diaktifkan pada build ini.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPreview(
    uri: Uri? = null,
    url: String? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(uri ?: Uri.parse(url ?: ""))
                setOnPreparedListener {
                    it.isLooping = false
                    start()
                }
            }
        },
        update = { view ->
            val target = uri ?: Uri.parse(url ?: "")

            if (view.tag != target.toString()) {
                view.tag = target.toString()
                view.setVideoURI(target)

                view.setOnPreparedListener {
                    it.isLooping = false
                    view.start()
                }
            }
        }
    )
}

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.MINUTES)
    .writeTimeout(10, TimeUnit.MINUTES)
    .build()

private fun apiUrl(path: String): String {
    return BuildConfig.API_BASE_URL.trimEnd('/') +
        "/" + path.trimStart('/')
}

private suspend fun checkServer(): String =
    withContext(Dispatchers.IO) {
        val response = http.newCall(
            Request.Builder()
                .url(apiUrl("/"))
                .get()
                .build()
        ).execute()

        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            error("HTTP ${response.code}")
        }

        val json = JSONObject(body)

        if (json.optBoolean("ai_ready")) {
            "Server terhubung • AI siap"
        } else {
            "Server terhubung • API key AI belum dipasang"
        }
    }

private suspend fun processVideo(
    ctx: Context,
    uri: Uri,
    instruction: String,
    count: Int,
    maxDuration: Int
): ProcessResult = withContext(Dispatchers.IO) {

    val document = DocumentFile.fromSingleUri(ctx, uri)
    val name = document?.name ?: "video.mp4"

    val videoBody = object : RequestBody() {
        override fun contentType() = "video/mp4".toMediaType()

        override fun contentLength() = document?.length() ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val stream = ctx.contentResolver.openInputStream(uri)
                ?: error("Tidak dapat membaca video.")

            stream.source().use { source ->
                sink.writeAll(source)
            }
        }
    }

    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", name, videoBody)
        .addFormDataPart("instruction", instruction)
        .addFormDataPart(
            "clip_count",
            count.coerceIn(1, 10).toString()
        )
        .addFormDataPart(
            "max_duration",
            maxDuration.coerceIn(8, 180).toString()
        )
        .build()

    val response = http.newCall(
        Request.Builder()
            .url(apiUrl("process"))
            .post(body)
            .build()
    ).execute()

    val raw = response.body?.string().orEmpty()

    if (!response.isSuccessful) {
        error("Server ${response.code}: $raw")
    }

    val json = JSONObject(raw)
    val array = json.getJSONArray("clips")

    val resultClips = (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)

        ClipResult(
            item.getString("name"),
            apiUrl(item.getString("url")),
            item.optString("reason"),
            item.optDouble("start"),
            item.optDouble("end"),
            apiUrl(item.optString("subtitle_url"))
        )
    }

    ProcessResult(
        json.getString("job_id"),
        resultClips,
        json.optDouble("source_duration").toFloat()
    )
}

private suspend fun reviseClip(
    job: String,
    index: Int,
    start: Double,
    end: Double,
    subtitle: String
): ClipResult = withContext(Dispatchers.IO) {

    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("start", start.toString())
        .addFormDataPart("end", end.toString())
        .addFormDataPart("subtitle_text", subtitle)
        .addFormDataPart("burn_subtitles", "true")
        .build()

    val response = http.newCall(
        Request.Builder()
            .url(apiUrl("revise/$job/$index"))
            .post(body)
            .build()
    ).execute()

    val raw = response.body?.string().orEmpty()

    if (!response.isSuccessful) {
        error("Server ${response.code}: $raw")
    }

    val item = JSONObject(raw)

    ClipResult(
        item.getString("name"),
        apiUrl(item.getString("url")),
        item.optString("reason"),
        item.optDouble("start"),
        item.optDouble("end"),
        apiUrl(item.optString("subtitle_url"))
    )
}
