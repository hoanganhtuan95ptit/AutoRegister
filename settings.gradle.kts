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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AutoRegister"
include(":app")
include(":auto-register")
include(":auto-register-processor")
include(":auto-register-dynamic-feature")

include(":samples:feature_library")
project(":samples:feature_library").projectDir = File("samples/feature_library")

include(":samples:feature_dynamic")
project(":samples:feature_dynamic").projectDir = File("samples/feature_dynamic")
