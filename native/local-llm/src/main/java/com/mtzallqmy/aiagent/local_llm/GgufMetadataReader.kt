package com.mtzallqmy.aiagent.local_llm

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class GgufMetadataReader {
    fun read(file: File): GgufModelMetadata = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= MIN_GGUF_BYTES) { "GGUF file is truncated" }
        val magic = ByteArray(4).also(input::readFully)
        require(magic.contentEquals(GGUF_MAGIC)) { "Invalid GGUF magic" }
        val version = input.readU32().toInt()
        require(version in 2..3) { "Unsupported GGUF version: $version" }
        val tensorCount = input.readU64Checked("tensor count")
        val metadataCount = input.readU64Checked("metadata count")
        require(metadataCount <= MAX_METADATA_ENTRIES) { "GGUF metadata count is unreasonable" }

        val scalarValues = linkedMapOf<String, Any>()
        repeat(metadataCount.toInt()) {
            val key = input.readGgufString()
            val type = input.readU32().toInt()
            val keep = key == "general.architecture" ||
                key == "general.name" ||
                key == "general.basename" ||
                key == "general.file_type" ||
                key.endsWith(".context_length")
            val value = input.readOrSkipValue(type, keep, depth = 0)
            if (keep && value != null) scalarValues[key] = value
        }

        val architecture = scalarValues["general.architecture"] as? String
        GgufModelMetadata(
            version = version,
            tensorCount = tensorCount,
            metadataCount = metadataCount,
            architecture = architecture,
            name = (scalarValues["general.name"] ?: scalarValues["general.basename"]) as? String,
            quantizationType = (scalarValues["general.file_type"] as? Number)?.toLong(),
            trainedContextSize = architecture?.let { (scalarValues["$it.context_length"] as? Number)?.toLong() }
                ?: scalarValues.entries.firstOrNull { it.key.endsWith(".context_length") }?.value?.let {
                    (it as? Number)?.toLong()
                },
        )
    }

    private fun RandomAccessFile.readOrSkipValue(type: Int, keep: Boolean, depth: Int): Any? {
        require(depth <= 2) { "Nested GGUF arrays are unsupported" }
        return when (type) {
            TYPE_UINT8 -> readUnsignedByte().takeIf { keep }
            TYPE_INT8 -> readByte().takeIf { keep }
            TYPE_UINT16 -> readU16().takeIf { keep }
            TYPE_INT16 -> readI16().takeIf { keep }
            TYPE_UINT32 -> readU32().takeIf { keep }
            TYPE_INT32 -> readI32().takeIf { keep }
            TYPE_FLOAT32 -> Float.fromBits(readI32()).takeIf { keep }
            TYPE_BOOL -> (readUnsignedByte() != 0).takeIf { keep }
            TYPE_STRING -> readGgufString().takeIf { keep }
            TYPE_ARRAY -> {
                val elementType = readU32().toInt()
                val count = readU64Checked("array length")
                require(count <= MAX_ARRAY_ENTRIES) { "GGUF array is unreasonable" }
                repeat(count.toInt()) { readOrSkipValue(elementType, keep = false, depth = depth + 1) }
                null
            }
            TYPE_UINT64 -> readU64Checked("uint64 value").takeIf { keep }
            TYPE_INT64 -> readI64().takeIf { keep }
            TYPE_FLOAT64 -> Double.fromBits(readI64()).takeIf { keep }
            else -> throw IllegalArgumentException("Unknown GGUF metadata type: $type")
        }
    }

    private fun RandomAccessFile.readGgufString(): String {
        val size = readU64Checked("string length")
        require(size <= MAX_STRING_BYTES) { "GGUF string is too large" }
        require(filePointer + size <= length()) { "GGUF string is truncated" }
        val bytes = ByteArray(size.toInt())
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun RandomAccessFile.readU16(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readI16(): Short = readU16().toShort()

    private fun RandomAccessFile.readU32(): Long =
        readUnsignedByte().toLong() or
            (readUnsignedByte().toLong() shl 8) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 24)

    private fun RandomAccessFile.readI32(): Int = readU32().toInt()

    private fun RandomAccessFile.readI64(): Long {
        var value = 0L
        repeat(8) { index -> value = value or (readUnsignedByte().toLong() shl (index * 8)) }
        return value
    }

    private fun RandomAccessFile.readU64Checked(label: String): Long {
        val value = readI64()
        if (value < 0) throw IllegalArgumentException("$label exceeds signed 64-bit limits")
        return value
    }

    private companion object {
        val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
        const val MIN_GGUF_BYTES = 24L
        const val MAX_METADATA_ENTRIES = 1_000_000L
        const val MAX_ARRAY_ENTRIES = 20_000_000L
        const val MAX_STRING_BYTES = 16L * 1024 * 1024
        const val TYPE_UINT8 = 0
        const val TYPE_INT8 = 1
        const val TYPE_UINT16 = 2
        const val TYPE_INT16 = 3
        const val TYPE_UINT32 = 4
        const val TYPE_INT32 = 5
        const val TYPE_FLOAT32 = 6
        const val TYPE_BOOL = 7
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
        const val TYPE_UINT64 = 10
        const val TYPE_INT64 = 11
        const val TYPE_FLOAT64 = 12
    }
}
