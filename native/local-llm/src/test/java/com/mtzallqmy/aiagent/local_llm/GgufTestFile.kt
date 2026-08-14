package com.mtzallqmy.aiagent.local_llm

import java.io.File
import java.io.RandomAccessFile

internal fun createGguf(
    file: File,
    contextSize: Long = 4096,
    paddedSize: Long = 2L * 1024 * 1024,
) {
    RandomAccessFile(file, "rw").use { output ->
        output.write(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        output.writeU32(3)
        output.writeU64(1)
        output.writeU64(4)
        output.writeStringValue("general.architecture", "llama")
        output.writeStringValue("general.name", "Test Model")
        output.writeU32Value("general.file_type", 7)
        output.writeU32Value("llama.context_length", contextSize)
        output.setLength(paddedSize)
    }
}

private fun RandomAccessFile.writeStringValue(key: String, value: String) {
    writeGgufString(key)
    writeU32(8)
    writeGgufString(value)
}

private fun RandomAccessFile.writeU32Value(key: String, value: Long) {
    writeGgufString(key)
    writeU32(4)
    writeU32(value)
}

private fun RandomAccessFile.writeGgufString(value: String) {
    val bytes = value.encodeToByteArray()
    writeU64(bytes.size.toLong())
    write(bytes)
}

private fun RandomAccessFile.writeU32(value: Long) {
    repeat(4) { index -> write(((value ushr (index * 8)) and 0xff).toInt()) }
}

private fun RandomAccessFile.writeU64(value: Long) {
    repeat(8) { index -> write(((value ushr (index * 8)) and 0xff).toInt()) }
}
