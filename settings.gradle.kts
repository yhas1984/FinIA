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

rootProject.name = "FinAI"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:common")
include(":feature:dashboard")
include(":feature:invoices")
include(":feature:products")
include(":feature:incomes")
include(":feature:ocr")
include(":feature:voice")
include(":feature:ai")
include(":feature:settings")
include(":feature:backup")
include(":feature:fiscal")
include(":feature:chatbot")
