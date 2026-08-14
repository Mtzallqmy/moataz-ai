package com.mtzallqmy.aiagent.local_llm

import java.io.File

class LocalModelDiscovery(
    roots: Collection<File>,
    private val metadataReader: GgufMetadataReader = GgufMetadataReader(),
) {
    private val canonicalRoots = roots.map { it.canonicalFile }.distinct()

    init {
        require(canonicalRoots.isNotEmpty()) { "At least one model root is required" }
    }

    fun requireConfinedFile(path: String): File {
        val file = File(path).canonicalFile
        require(canonicalRoots.any { root -> file == root || file.toPath().startsWith(root.toPath()) }) {
            "Model path is outside configured model roots"
        }
        require(file.isFile && file.canRead()) { "Model file is not readable" }
        require(file.extension.equals("gguf", ignoreCase = true)) { "Only GGUF model files are supported" }
        return file
    }

    fun discover(): List<DiscoveredLocalModel> = canonicalRoots
        .asSequence()
        .filter { it.isDirectory && it.canRead() }
        .flatMap { root -> root.walkTopDown().maxDepth(3).asSequence() }
        .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
        .mapNotNull { file ->
            runCatching {
                val confined = requireConfinedFile(file.path)
                DiscoveredLocalModel(
                    reference = LocalModelReference(confined.path),
                    fileSizeBytes = confined.length(),
                    lastModifiedMillis = confined.lastModified(),
                    metadata = metadataReader.read(confined),
                )
            }.getOrNull()
        }
        .sortedBy { it.reference.canonicalPath }
        .toList()
}
