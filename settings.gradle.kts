pluginManagement {
    repositories {
        google()
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

rootProject.name = "kotopogoda-uploader-android"

include(":app")
include(":core:data")
include(":core:network")
include(":feature:onboarding")
include(":feature:viewer")
