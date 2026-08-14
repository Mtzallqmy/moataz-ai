package com.mtzallqmy.aiagent.local_llm

import com.mtzallqmy.aiagent.local_llm.internal.LlamaCppJniBridge
import com.mtzallqmy.aiagent.local_llm.internal.LlamaNativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LlamaCppLocalModelBackend internal constructor(
    private val discovery: LocalModelDiscovery,
    resources: LocalDeviceResources,
    nativeOverride: LlamaNativeBridge?,
) : LocalModelBackend {
    constructor(
        modelRoots: Collection<File>,
        resources: LocalDeviceResources,
    ) : this(LocalModelDiscovery(modelRoots), resources, null)

    // Do not load a 64-bit JNI library at app startup on an unsupported process.
    // Preflight rejects the ABI before this lazy boundary is reached.
    private val native: LlamaNativeBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        nativeOverride ?: LlamaCppJniBridge()
    }

    private val preflight = LocalModelPreflight(discovery, resources)
    private val operationMutex = Mutex()
    private val assessments = ConcurrentHashMap<String, AssessmentRecord>()
    private val mutableState = MutableStateFlow<LocalModelState>(LocalModelState.Idle)
    override val state = mutableState.asStateFlow()

    @Volatile private var modelHandle = 0L
    @Volatile private var generationHandle = 0L
    private var loadedModel: LoadedLocalModel? = null

    override suspend fun discoverModels(): List<DiscoveredLocalModel> = withContext(Dispatchers.IO) {
        discovery.discover()
    }

    override suspend fun assessLoad(
        reference: LocalModelReference,
        options: LocalModelLoadOptions,
    ): LocalModelLoadAssessment = withContext(Dispatchers.IO) {
        val assessmentId = UUID.randomUUID().toString()
        val assessment = preflight.assess(reference, options, assessmentId)
        val file = discovery.requireConfinedFile(reference.canonicalPath)
        assessments[assessmentId] = AssessmentRecord(
            reference = reference,
            options = options,
            assessment = assessment,
            fileLength = file.length(),
            lastModified = file.lastModified(),
            issuedAtMillis = System.currentTimeMillis(),
        )
        assessment
    }

    override suspend fun load(
        reference: LocalModelReference,
        options: LocalModelLoadOptions,
        assessmentId: String,
        acknowledgeWarnings: Boolean,
    ): LoadedLocalModel = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            check(generationHandle == 0L) { "Cannot load while generation is active" }
            check(modelHandle == 0L) { "Unload the current model before loading another model" }
            val record = assessments.remove(assessmentId)
                ?: throw LocalModelLoadRejectedException("Load assessment is missing, expired, or already used")
            validateAssessment(record, reference, options, acknowledgeWarnings)

            val file = discovery.requireConfinedFile(reference.canonicalPath)
            if (file.length() != record.fileLength || file.lastModified() != record.lastModified) {
                throw LocalModelLoadRejectedException("Model changed after assessment; assess it again")
            }
            if (preflight.sha256(file) != record.assessment.sha256) {
                throw LocalModelLoadRejectedException("Model checksum changed after assessment; assess it again")
            }
            mutableState.value = LocalModelState.Loading(file.path)
            var newHandle = 0L
            try {
                newHandle = native.loadModel(file.path, options.useMemoryMap)
                check(newHandle != 0L) { "llama.cpp rejected the model" }
                val nativeInfo = native.modelInfo(newHandle)
                val loaded = LoadedLocalModel(
                    reference = reference.copy(canonicalPath = file.path),
                    metadata = record.assessment.metadata,
                    sha256 = record.assessment.sha256,
                    nativeDescription = nativeInfo.description,
                    parameterCount = nativeInfo.parameterCount,
                    tensorBytes = nativeInfo.tensorBytes,
                    embeddingDimension = nativeInfo.embeddingDimension,
                    options = options,
                )
                modelHandle = newHandle
                loadedModel = loaded
                mutableState.value = LocalModelState.Ready(loaded)
                loaded
            } catch (error: Throwable) {
                if (newHandle != 0L) runCatching { native.unloadModel(newHandle) }
                modelHandle = 0L
                loadedModel = null
                mutableState.value = LocalModelState.Failed(error.message ?: "Model load failed")
                throw error
            }
        }
    }

    override suspend fun unload() = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            cancelGeneration()
            val handle = modelHandle
            modelHandle = 0L
            loadedModel = null
            if (handle != 0L) native.unloadModel(handle)
            mutableState.value = LocalModelState.Idle
        }
    }

    override fun generate(
        prompt: String,
        options: LocalGenerationOptions,
    ): Flow<LocalGenerationEvent> = flow {
        require(prompt.isNotBlank()) { "Prompt cannot be blank" }
        operationMutex.withLock {
            val model = checkNotNull(loadedModel) { "No local model is loaded" }
            val handle = modelHandle
            check(handle != 0L) { "No native model is loaded" }
            check(generationHandle == 0L) { "A generation is already active" }

            val generation = native.startGeneration(
                modelHandle = handle,
                prompt = prompt,
                contextSize = model.options.contextSize,
                threads = model.options.threads,
                maxTokens = options.maxTokens,
                temperature = options.temperature,
                seed = options.seed,
            )
            check(generation != 0L) { "llama.cpp could not initialize generation" }
            generationHandle = generation
            mutableState.value = LocalModelState.Generating(model)
            try {
                while (true) {
                    val bytes = native.nextToken(generation) ?: break
                    if (bytes.isNotEmpty()) emit(LocalGenerationEvent.Text(bytes.decodeToString()))
                }
                val usage = native.generationUsage(generation)
                check(usage.size >= 3) { "Native usage response is invalid" }
                emit(
                    LocalGenerationEvent.Completed(
                        LocalTokenUsage(
                            promptTokens = usage[0].toInt(),
                            generatedTokens = usage[1].toInt(),
                            elapsedMillis = usage[2],
                        ),
                    ),
                )
            } catch (cancelled: CancellationException) {
                native.cancelGeneration(generation)
                throw cancelled
            } finally {
                native.freeGeneration(generation)
                generationHandle = 0L
                mutableState.value = LocalModelState.Ready(model)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancelGeneration() = withContext(Dispatchers.IO) {
        val handle = generationHandle
        if (handle != 0L) native.cancelGeneration(handle)
    }

    override suspend fun embed(text: String, options: LocalEmbeddingOptions): LocalEmbedding =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                require(text.isNotBlank() && text.length <= 1_000_000) { "Invalid embedding input" }
                check(generationHandle == 0L) { "Cannot embed while generation is active" }
                val model = checkNotNull(loadedModel) { "No local model is loaded" }
                val handle = modelHandle
                check(handle != 0L) { "No native model is loaded" }
                mutableState.value = LocalModelState.Embedding(model)
                val started = System.currentTimeMillis()
                try {
                    val values = native.embed(
                        modelHandle = handle,
                        text = text,
                        contextSize = options.contextSize,
                        threads = options.threads,
                        normalize = options.normalize,
                    )
                    check(values.isNotEmpty() && values.all(Float::isFinite)) {
                        "llama.cpp returned an invalid embedding"
                    }
                    LocalEmbedding(
                        values = values.map(Float::toDouble),
                        modelSha256 = model.sha256,
                        elapsedMillis = System.currentTimeMillis() - started,
                    )
                } finally {
                    mutableState.value = LocalModelState.Ready(model)
                }
            }
        }

    private fun validateAssessment(
        record: AssessmentRecord,
        reference: LocalModelReference,
        options: LocalModelLoadOptions,
        acknowledgeWarnings: Boolean,
    ) {
        if (System.currentTimeMillis() - record.issuedAtMillis > ASSESSMENT_TTL_MILLIS) {
            throw LocalModelLoadRejectedException("Load assessment expired; assess the model again")
        }
        if (record.reference != reference || record.options != options) {
            throw LocalModelLoadRejectedException("Load request does not match its assessment")
        }
        if (record.assessment.blockers.isNotEmpty()) {
            throw LocalModelLoadRejectedException(record.assessment.blockers.joinToString("; "))
        }
        if (record.assessment.warnings.isNotEmpty() && !acknowledgeWarnings) {
            throw LocalModelLoadRejectedException("Load warnings require explicit acknowledgement")
        }
    }

    private data class AssessmentRecord(
        val reference: LocalModelReference,
        val options: LocalModelLoadOptions,
        val assessment: LocalModelLoadAssessment,
        val fileLength: Long,
        val lastModified: Long,
        val issuedAtMillis: Long,
    )

    private companion object {
        const val ASSESSMENT_TTL_MILLIS = 10 * 60 * 1000L
    }
}
