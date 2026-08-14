package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.model.CapabilityId

/**
 * Skills are instructions/workflows composed over Tools (primitives).
 * Format: YAML front matter + Markdown instructions.
 *
 *   name: android-app-navigation
 *   version: 1.0.0
 *   required_capabilities: [ui.tree, ui.tap, ui.scroll]
 *
 *   # Navigation skill instructions...
 */
data class SkillDescriptor(
    val name: String,
    val version: String,
    val description: String,
    val requiredCapabilities: List<CapabilityId>,
    val instructions: String,
)

/** Parses YAML front matter delimited by --- fences. */
object SkillLoader {
    fun parse(markdown: String): SkillDescriptor? {
        val fences = markdown.indexOf("---")
        if (fences < 0) return null
        val endFence = markdown.indexOf("---", fences + 3)
        if (endFence < 0) return null
        val front = markdown.substring(fences + 3, endFence).trim()
        val body = markdown.substring(endFence + 3).trim()
        val fields = front.lines().associate { line ->
            val (k, v) = line.split(":", limit = 2).let { it[0].trim() to (it.getOrNull(1)?.trim() ?: "") }
            k to v
        }
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
        val caps = (fields["required_capabilities"] ?: "")
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { CapabilityId(it) }
        return SkillDescriptor(
            name = name,
            version = fields["version"] ?: "1.0.0",
            description = fields["description"] ?: "",
            requiredCapabilities = caps,
            instructions = body,
        )
    }
}

/** Validates skills against security policy: no privilege escalation via skill files. */
class SkillValidator {
    /** Skill content may not reference forbidden markers. */
    fun validate(skill: SkillDescriptor): List<String> {
        val issues = mutableListOf<String>()
        if (skill.name.isBlank()) issues.add("Skill name is required")
        if (skill.instructions.contains("system prompt override", ignoreCase = true)) {
            issues.add("Skills may not attempt to override system instructions (prompt injection)")
        }
        return issues
    }
}

/** Central registry for loaded skills. */
class SkillRegistry {
    private val skills = mutableMapOf<String, SkillDescriptor>()

    fun load(skill: SkillDescriptor, validator: SkillValidator = SkillValidator()) {
        val issues = validator.validate(skill)
        if (issues.isNotEmpty()) error("Skill '${skill.name}' invalid: ${issues.joinToString()}")
        skills[skill.name] = skill
    }

    fun get(name: String): SkillDescriptor? = skills[name]

    fun all(): List<SkillDescriptor> = skills.values.toList()

    fun remove(name: String) { skills.remove(name) }
}
