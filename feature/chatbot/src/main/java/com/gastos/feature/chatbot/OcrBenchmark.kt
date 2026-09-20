package com.gastos.feature.chatbot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.domain.model.*
import com.gastos.feature.ai.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import javax.inject.Inject

data class BenchmarkRead(val result: DocumentReadResult, val durationMs: Long)
data class BenchmarkPair(val uri: Uri, val fast: BenchmarkRead, val thorough: BenchmarkRead)
@Serializable
data class BenchmarkGrade(val fastMs: Long, val thoroughMs: Long, val fastAccurate: Boolean, val thoroughAccurate: Boolean,
    val fastComplete: Boolean, val thoroughComplete: Boolean, val fastReview: Boolean, val thoroughReview: Boolean)
data class BenchmarkState(val active: Boolean = false, val busy: Boolean = false, val pair: BenchmarkPair? = null,
    val grades: List<BenchmarkGrade> = emptyList(), val selectedCount: Int = 0)

@HiltViewModel
class OcrBenchmarkViewModel @Inject constructor(private val reader: AIService) : ViewModel() {
    private val mutableState: MutableStateFlow<BenchmarkState> = MutableStateFlow(BenchmarkState())
    val state: StateFlow<BenchmarkState> = mutableState.asStateFlow()
    private var files: List<Uri> = emptyList()
    private var job: Job? = null

    fun start(uris: List<Uri>) {
        if (state.value.busy || uris.isEmpty()) return
        files = uris.distinct().take(25)
        mutableState.value = BenchmarkState(active = true, selectedCount = files.size)
        next()
    }

    private fun next() {
        val index: Int = state.value.grades.size
        val uri: Uri = files.getOrNull(index) ?: return
        mutableState.update { it.copy(busy = true, pair = null) }
        job = viewModelScope.launch {
            try {
                suspend fun read(profile: OcrProfile): BenchmarkRead {
                    val started: Long = SystemClock.elapsedRealtime()
                    val result: DocumentReadResult = reader.readDocument(uri, profile)
                    return BenchmarkRead(result, SystemClock.elapsedRealtime() - started)
                }
                // Alternate call order to reduce warmup/network bias. Identical schema, resolution and validations.
                val first: OcrProfile = if (index % 2 == 0) OcrProfile.FAST else OcrProfile.THOROUGH
                val firstRead: BenchmarkRead = read(first)
                val secondRead: BenchmarkRead = read(if (first == OcrProfile.FAST) OcrProfile.THOROUGH else OcrProfile.FAST)
                mutableState.update { it.copy(pair = BenchmarkPair(uri,
                    if (first == OcrProfile.FAST) firstRead else secondRead,
                    if (first == OcrProfile.THOROUGH) firstRead else secondRead)) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun grade(fastAccurate: Boolean, thoroughAccurate: Boolean, fastComplete: Boolean, thoroughComplete: Boolean) {
        val pair: BenchmarkPair = state.value.pair ?: return
        val grade: BenchmarkGrade = BenchmarkGrade(pair.fast.durationMs, pair.thorough.durationMs,
            fastAccurate && pair.fast.result !is DocumentReadResult.Failure,
            thoroughAccurate && pair.thorough.result !is DocumentReadResult.Failure,
            fastComplete && pair.fast.result !is DocumentReadResult.Failure,
            thoroughComplete && pair.thorough.result !is DocumentReadResult.Failure,
            pair.fast.result !is DocumentReadResult.Ready, pair.thorough.result !is DocumentReadResult.Ready)
        mutableState.update { it.copy(grades = it.grades + grade, pair = null) }
        next()
    }

    fun close() { job?.cancel(); mutableState.update { it.copy(active = false) } }
    fun report(): String = DocumentEvidenceCodec.json.encodeToString(state.value.grades)
}

@Composable
internal fun OcrBenchmarkButton(enabled: Boolean, model: OcrBenchmarkViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { model.start(it) }
    TextButton(onClick = { confirm = true }, enabled = enabled) { Text(stringResource(R.string.benchmark_start)) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.benchmark_start)) },
        text = { Text(stringResource(R.string.benchmark_consent)) },
        confirmButton = { TextButton(onClick = { confirm = false; picker.launch("image/*") }) { Text(stringResource(R.string.benchmark_select)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.capture_close)) } })
    if (state.active) BenchmarkDialog(state, model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BenchmarkDialog(state: BenchmarkState, model: OcrBenchmarkViewModel) {
    val context: Context = LocalContext.current
    val shareTitle: String = stringResource(R.string.benchmark_share)
    var fastAccurate by remember(state.pair) { mutableStateOf(false) }
    var thoroughAccurate by remember(state.pair) { mutableStateOf(false) }
    var fastComplete by remember(state.pair) { mutableStateOf(false) }
    var thoroughComplete by remember(state.pair) { mutableStateOf(false) }
    var checked by remember(state.pair) { mutableStateOf(false) }
    Dialog(onDismissRequest = model::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.benchmark_title, state.grades.size, state.selectedCount)) },
                navigationIcon = { TextButton(onClick = model::close) { Text(stringResource(R.string.capture_close)) } }) }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.benchmark_explanation)) }
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.pair?.let { pair ->
                        item { DocumentPhoto(pair.uri.toString()) }
                        item { BenchmarkReading(pair.fast, stringResource(R.string.benchmark_fast)) }
                        item { BenchmarkReading(pair.thorough, stringResource(R.string.benchmark_thorough)) }
                        item {
                            GradeCheck(stringResource(R.string.benchmark_fast_accurate), fastAccurate) { fastAccurate = it }
                            GradeCheck(stringResource(R.string.benchmark_thorough_accurate), thoroughAccurate) { thoroughAccurate = it }
                            GradeCheck(stringResource(R.string.benchmark_fast_complete), fastComplete) { fastComplete = it }
                            GradeCheck(stringResource(R.string.benchmark_thorough_complete), thoroughComplete) { thoroughComplete = it }
                            GradeCheck(stringResource(R.string.benchmark_checked), checked) { checked = it }
                            Button(onClick = { model.grade(fastAccurate, thoroughAccurate, fastComplete, thoroughComplete) }, enabled = checked) {
                                Text(stringResource(R.string.benchmark_next))
                            }
                        }
                    }
                    if (state.grades.isNotEmpty() && !state.busy) item {
                        val summary: BenchmarkSummary = summarizeBenchmark(state.grades)
                        Text(stringResource(R.string.benchmark_summary, summary.fastMedian, summary.thoroughMedian, summary.improvementPercent))
                        Text(stringResource(if (summary.eligible) R.string.benchmark_eligible else R.string.benchmark_not_eligible))
                        TextButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, model.report()), shareTitle))
                        }) { Text(stringResource(R.string.benchmark_share)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeCheck(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row { Checkbox(value, onCheckedChange = onChange); Text(label, Modifier.padding(top = 12.dp)) }
}

@Composable
private fun BenchmarkReading(read: BenchmarkRead, title: String) {
    val evidence: DocumentEvidence? = when (val result = read.result) {
        is DocumentReadResult.Ready -> result.evidence
        is DocumentReadResult.NeedsReview -> result.evidence
        else -> null
    }
    Text("$title · ${read.durationMs} ms", style = MaterialTheme.typography.titleMedium)
    val locale = LocalLocale.current.platformLocale
    evidence?.let { DocumentEditor.fields(it, locale).forEach { (field, value) ->
        Text("${captureFieldLabel(field)}: $value", style = MaterialTheme.typography.bodySmall)
    } }
    (read.result as? DocumentReadResult.Failure)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    (read.result as? DocumentReadResult.NeedsReview)?.issues?.forEach { Text("${captureFieldLabel(it.field)}: ${captureReasonLabel(it.reason)}") }
}

data class BenchmarkSummary(val fastMedian: Long, val thoroughMedian: Long, val improvementPercent: Int, val eligible: Boolean)
fun summarizeBenchmark(grades: List<BenchmarkGrade>): BenchmarkSummary {
    fun median(values: List<Long>): Long {
        val sorted: List<Long> = values.sorted()
        if (sorted.isEmpty()) return 0
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    val fast: Long = median(grades.map { it.fastMs })
    val thorough: Long = median(grades.map { it.thoroughMs })
    val improvement: Int = if (thorough == 0L) 0 else ((thorough - fast) * 100.0 / thorough).toInt()
    return BenchmarkSummary(fast, thorough, improvement, grades.size >= 25 && improvement >= 20 &&
        grades.count { it.fastAccurate } >= grades.count { it.thoroughAccurate } &&
        grades.count { it.fastComplete } >= grades.count { it.thoroughComplete } &&
        grades.count { it.fastReview } <= grades.count { it.thoroughReview })
}
