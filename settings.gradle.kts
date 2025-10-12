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
include(":core:security")
include(":core:settings")
include(":feature:onboarding")
include(":feature:pairing")
include(":feature:viewer")
include(":feature:queue")
