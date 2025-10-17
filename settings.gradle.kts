import java.io.File

val androidSdkPath = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")

if (androidSdkPath != null) {
    System.setProperty("android.home", androidSdkPath)
    System.setProperty("sdk.dir", androidSdkPath)

    val localProperties = File(rootDir, "local.properties")
    if (!localProperties.exists()) {
        val escapedPath = androidSdkPath.replace("\\", "\\\\")
        localProperties.writeText("sdk.dir=$escapedPath\n")
    }
}

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
include(":core:logging")
include(":core:work")
include(":feature:onboarding")
include(":feature:pairing")
include(":feature:viewer")
include(":feature:queue")
include(":feature:status")
