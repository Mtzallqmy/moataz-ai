package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.common.SecretSanitizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * Typed tool input pipeline (replaces unsafe `parseToolInput(arguments: String): Any = arguments`).
 *
 * LLM arguments  →  JSON parser  →  schema validator  →  typed input (JsonObject)
 *                →  policy checks (size / secret scan)  →  execute
 *
 * Validates a JSON-Schema-like `type`/`required`/`properties` document. Only the
 * subset needed for tool descriptors is implemented: object with typed properties
 * (string / number / integer / boolean / array / object) plus required-field
 * checking, string size limits, and nested recursion with a bounded depth.
 */
object ToolSchemaValidator {

    /** Max total characters a tool input JSON may carry (memory/DoS protection). */
    const val MAX_INPUT_CHARS: Int = 64_000

    /** Max string length inside any single property (path traversal / log spam protection). */
    const val MAX_STRING_LENGTH: Int = 16_000

    /** Max array size. */
    const val MAX_ARRAY_SIZE: Int = 500

    /** Recursion depth limit for nested objects/arrays. */
    const val MAX_DEPTH: Int = 6

    /** Result of validating raw LLM arguments. */
    sealed class ValidationResult {
        data class Valid(val input: JsonObject) : ValidationResult()
        data class Invalid(val errors: List<String>) : ValidationResult()
    }

    /**
     * Full pipeline: parse → validate → policy checks.
     * Never returns `Any`; a failed parse returns Invalid, never raw String.
     */
    fun validate(rawArguments: String, schemaJson: String): ValidationResult {
        val errors = mutableListOf<String>()

        val parsed = try {
            Json.parseToJsonElement(rawArguments)
        } catch (e: SerializationException) {
            return ValidationResult.Invalid(listOf("Invalid JSON: ${e.message?.take(120)}"))
        }

        if (parsed !is JsonObject) {
            return ValidationResult.Invalid(listOf("Tool input must be a JSON object"))
        }

        if (rawArguments.length > MAX_INPUT_CHARS) {
            errors.add("Input exceeds maximum size (${MAX_INPUT_CHARS} chars)")
        }

        val schema = try {
            Json.parseToJsonElement(schemaJson) as? JsonObject ?: buildJsonObject { }
        } catch (_: SerializationException) {
            buildJsonObject { }
        }

        validateNode(parsed, schema, depth = 0, path = "$", errors = errors)

        // Policy check: secrets must never reach tool execution as arguments.
        val sanitized = SecretSanitizer.sanitize(rawArguments)
        if (sanitized != rawArguments) {
            errors.add("Input contains a value matching a secret pattern; rejected for safety")
        }

        return if (errors.isEmpty()) ValidationResult.Valid(parsed)
        else ValidationResult.Invalid(errors.distinct())
    }

    private fun validateNode(node: JsonElement, schema: JsonObject, depth: Int, path: String, errors: MutableList<String>) {
        if (depth > MAX_DEPTH) {
            errors.add("$path exceeds maximum nesting depth ($MAX_DEPTH)"); return
        }
        val type = (schema["type"] as? JsonPrimitive)?.content ?: return

        when (type) {
            "object" -> validateObject(node, schema, depth, path, errors)
            "array" -> validateArray(node, schema, depth, path, errors)
            "string" -> validateString(node, path, errors)
            "number", "integer" -> validateNumber(node, type, path, errors)
            "boolean" -> if (node !is JsonPrimitive || node.booleanOrNull == null) errors.add("$path must be a boolean")
            "null" -> if (node !is JsonNull) errors.add("$path must be null")
        }
    }

    private fun validateObject(node: JsonElement, schema: JsonObject, depth: Int, path: String, errors: MutableList<String>) {
        if (node !is JsonObject) { errors.add("$path must be an object"); return }
        val properties = (schema["properties"] as? JsonObject) ?: return
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

        for (req in required) {
            if (node[req] == null) errors.add("$path.$req is required")
        }

        for ((key, childSchema) in properties) {
            val child = node[key] ?: continue
            validateNode(child, childSchema as? JsonObject ?: buildJsonObject { }, depth + 1, "$path.$key", errors)
        }
    }

    private fun validateArray(node: JsonElement, schema: JsonObject, depth: Int, path: String, errors: MutableList<String>) {
        if (node !is JsonArray) { errors.add("$path must be an array"); return }
        if (node.size > MAX_ARRAY_SIZE) errors.add("$path exceeds maximum array size ($MAX_ARRAY_SIZE)")
        val items = (schema["items"] as? JsonObject) ?: return
        node.forEachIndexed { index, element ->
            validateNode(element, items, depth + 1, "$path[$index]", errors)
        }
    }

    private fun validateString(node: JsonElement, path: String, errors: MutableList<String>) {
        if (node !is JsonPrimitive || !node.isString) { errors.add("$path must be a string"); return }
        val value = node.content
        if (value.length > MAX_STRING_LENGTH) errors.add("$path string exceeds maximum length ($MAX_STRING_LENGTH)")
    }

    private fun validateNumber(node: JsonElement, type: String, path: String, errors: MutableList<String>) {
        if (node !is JsonPrimitive || node.contentOrNull?.toDoubleOrNull() == null) {
            errors.add("$path must be a number"); return
        }
        if (type == "integer") {
            val d = node.content.toDoubleOrNull()
            if (d != null && d != d.toLong().toDouble()) errors.add("$path must be an integer")
        }
    }
}
