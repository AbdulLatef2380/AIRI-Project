pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://alphacephei.com/maven/") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://alphacephei.com/maven/") }
    }
}

rootProject.name = "AIRI"
include(":app")
include(":core-domain")
include(":app-desktop")
