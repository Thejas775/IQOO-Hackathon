package com.knoxx.dailyflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.llm.BackendChoice
import com.knoxx.dailyflow.llm.GenerationStats
import com.knoxx.dailyflow.llm.InferenceEngine
import com.knoxx.dailyflow.llm.ModelCatalog
import com.knoxx.dailyflow.llm.ModelDownloader
import com.knoxx.dailyflow.llm.ModelLocator
import com.knoxx.dailyflow.llm.ModelSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class ModelStatus { Checking, Missing, Downloading, Loading, Ready, Error }

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val stats: GenerationStats? = null,
    val id: Long
)

data class BenchUiState(
    val modelStatus: ModelStatus = ModelStatus.Checking,
    val modelName: String = "",
    val modelSizeMb: Long = 0,
    val pushPath: String = "",
    /** Which catalog build the user has picked to download. */
    val selectedSpec: ModelSpec = ModelCatalog.default,
    /** Bytes already on disk for [selectedSpec] from an interrupted download. */
    val resumableBytes: Long = 0,
    val download: ModelDownloader.Progress.Running? = null,
    val requestedBackend: BackendChoice = BackendChoice.GPU,
    val activeBackend: BackendChoice? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val errorMessage: String = "",
    val runs: List<GenerationStats> = emptyList()
) {
    val bestDecodeRate: Double get() = runs.maxOfOrNull { it.decodeTokensPerSec } ?: 0.0

    /** Excludes the first run — it carries one-time native warm-up (kernel
     *  compilation, weight paging) that isn't representative of steady state. */
    val meanDecodeRate: Double
        get() = runs.drop(1).takeIf { it.isNotEmpty() }
            ?.map { it.decodeTokensPerSec }?.average() ?: 0.0

    val warmRunCount: Int get() = (runs.size - 1).coerceAtLeast(0)
}

class BenchViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = InferenceEngine(application)

    private val _state = MutableStateFlow(BenchUiState())
    val state: StateFlow<BenchUiState> = _state.asStateFlow()

    private var generationJob: Job? = null
    private var downloadJob: Job? = null
    private var nextId = 0L

    init { checkModel() }

    /** Looks for a usable model — downloaded or sideloaded — and loads it. */
    fun checkModel() {
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            modelStatus = ModelStatus.Checking,
            pushPath = ModelLocator.pushPath(ctx),
            errorMessage = ""
        )

        val model = ModelLocator.find(ctx)
        if (model == null) {
            _state.value = _state.value.copy(
                modelStatus = ModelStatus.Missing,
                modelName = "",
                modelSizeMb = 0,
                resumableBytes = ModelDownloader.partialBytes(ctx, _state.value.selectedSpec)
            )
            return
        }

        _state.value = _state.value.copy(
            modelName = model.name,
            modelSizeMb = model.length() / (1024 * 1024)
        )
        loadModel(model.absolutePath)
    }

    fun selectSpec(spec: ModelSpec) {
        if (_state.value.modelStatus == ModelStatus.Downloading) return
        _state.value = _state.value.copy(
            selectedSpec = spec,
            resumableBytes = ModelDownloader.partialBytes(getApplication(), spec),
            errorMessage = ""
        )
    }

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        val ctx = getApplication<Application>()
        val spec = _state.value.selectedSpec

        _state.value = _state.value.copy(
            modelStatus = ModelStatus.Downloading,
            errorMessage = "",
            download = null
        )

        downloadJob = viewModelScope.launch {
            ModelDownloader.download(ctx, spec)
                .catch { t ->
                    _state.value = _state.value.copy(
                        modelStatus = ModelStatus.Missing,
                        errorMessage = t.message ?: "Download failed",
                        resumableBytes = ModelDownloader.partialBytes(ctx, spec)
                    )
                }
                .collect { progress ->
                    when (progress) {
                        is ModelDownloader.Progress.Running ->
                            _state.value = _state.value.copy(download = progress)

                        is ModelDownloader.Progress.Done -> {
                            _state.value = _state.value.copy(
                                download = null,
                                resumableBytes = 0,
                                modelName = progress.file.name,
                                modelSizeMb = progress.file.length() / (1024 * 1024)
                            )
                            loadModel(progress.file.absolutePath)
                        }

                        is ModelDownloader.Progress.Failed ->
                            _state.value = _state.value.copy(
                                modelStatus = ModelStatus.Missing,
                                download = null,
                                errorMessage = progress.reason,
                                resumableBytes = ModelDownloader.partialBytes(ctx, spec)
                            )
                    }
                }
        }
    }

    /** Cancels in flight. The .part file is left alone so the next attempt resumes. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            modelStatus = ModelStatus.Missing,
            download = null,
            resumableBytes = ModelDownloader.partialBytes(ctx, _state.value.selectedSpec)
        )
    }

    /** Throws away partial bytes — for when a resume keeps failing. */
    fun discardPartial() {
        val ctx = getApplication<Application>()
        ModelDownloader.partFile(ctx, _state.value.selectedSpec).delete()
        _state.value = _state.value.copy(resumableBytes = 0, errorMessage = "")
    }

    private fun loadModel(path: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(modelStatus = ModelStatus.Loading, errorMessage = "")
            try {
                val actual = engine.initialize(path, _state.value.requestedBackend)
                _state.value = _state.value.copy(
                    modelStatus = ModelStatus.Ready,
                    activeBackend = actual
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    modelStatus = ModelStatus.Error,
                    errorMessage = t.message ?: "Failed to load model"
                )
            }
        }
    }

    /** Switching backend reloads the engine — a loaded one can't be retargeted.
     *  Clears runs too: averaging GPU and CPU numbers together is meaningless. */
    fun setBackend(choice: BackendChoice) {
        if (choice == _state.value.requestedBackend) return
        val model = ModelLocator.find(getApplication()) ?: return
        _state.value = _state.value.copy(
            requestedBackend = choice,
            runs = emptyList(),
            messages = emptyList()
        )
        loadModel(model.absolutePath)
    }

    fun send(prompt: String) {
        if (prompt.isBlank() || _state.value.isGenerating) return
        if (_state.value.modelStatus != ModelStatus.Ready) return

        val userMsg = ChatMessage(prompt, isUser = true, id = nextId++)
        val replyId = nextId++
        _state.value = _state.value.copy(
            messages = _state.value.messages + userMsg +
                ChatMessage("", isUser = false, id = replyId),
            isGenerating = true,
            errorMessage = ""
        )

        generationJob = viewModelScope.launch {
            val started = System.nanoTime()
            var firstEmissionAt = 0L
            var lastEmissionAt = 0L
            var emissions = 0
            val builder = StringBuilder()

            engine.chatStream(prompt)
                .catch { t ->
                    _state.value = _state.value.copy(
                        isGenerating = false,
                        errorMessage = t.message ?: "Generation failed"
                    )
                }
                .collect { delta ->
                    val now = System.nanoTime()
                    if (emissions == 0) firstEmissionAt = now
                    lastEmissionAt = now
                    emissions++
                    builder.append(delta)

                    val current = builder.toString()
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map {
                            if (it.id == replyId) it.copy(text = current) else it
                        }
                    )
                }

            if (emissions == 0) {
                _state.value = _state.value.copy(isGenerating = false)
                return@launch
            }

            val stats = GenerationStats(
                tokens = emissions,
                chars = builder.length,
                ttftMs = (firstEmissionAt - started) / 1_000_000,
                decodeMs = (lastEmissionAt - firstEmissionAt) / 1_000_000,
                totalMs = (lastEmissionAt - started) / 1_000_000,
                backend = engine.activeBackend ?: _state.value.requestedBackend
            )

            _state.value = _state.value.copy(
                isGenerating = false,
                runs = _state.value.runs + stats,
                messages = _state.value.messages.map {
                    if (it.id == replyId) it.copy(stats = stats) else it
                }
            )

            // Fresh context per run: otherwise prefill grows with accumulated
            // history and TTFT drifts upward across a session, which would read
            // as the model getting slower.
            engine.resetConversation()
        }
    }

    fun stop() {
        generationJob?.cancel()
        _state.value = _state.value.copy(isGenerating = false)
    }

    fun clearRuns() {
        _state.value = _state.value.copy(runs = emptyList(), messages = emptyList())
        engine.resetConversation()
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
