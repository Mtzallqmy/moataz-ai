pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AiAgent"

include(":app")

// Core modules
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:security")
include(":core:permissions")
include(":core:ui")
include(":core:agent")
include(":core:tools")
include(":core:capabilities")
include(":core:memory")
include(":core:workspace")
include(":core:sandbox")
include(":core:workflow")

// Native security runtime
include(":native:runtime-rust")
include(":native:local-llm")

// Feature modules
include(":feature:chat")
include(":feature:providers")
include(":feature:device")
include(":feature:browser")
include(":feature:terminal")
include(":feature:sandbox")
include(":feature:files")
include(":feature:security")
include(":feature:settings")
include(":feature:logs")
include(":feature:schedules")

// Tool modules
include(":tool:android")
include(":tool:filesystem")
include(":tool:terminal")
include(":tool:http")
include(":tool:mcp")
include(":tool:clipboard")
include(":tool:ssh")

// Provider modules
include(":provider:openai")
include(":provider:anthropic")
include(":provider:google")
include(":provider:openai-compatible")
include(":provider:openrouter")
include(":provider:local")
