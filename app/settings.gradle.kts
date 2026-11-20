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
        // Only for the experimental :runtime module (Termux terminal libraries).
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}

rootProject.name = "DroidHand"
include(":portal")
// EXPERIMENTAL Linux runtime (separate APK). Skip with -Pdroidhand.runtime=false; remove by deleting this line + app/runtime.
if (providers.gradleProperty("droidhand.runtime").orNull != "false") include(":runtime")
