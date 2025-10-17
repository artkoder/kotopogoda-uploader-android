import java.io.File as JFile

import java.io.OutputStreamWriter
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory

fun downloadFile(url: String, destination: JFile) {
    destination.parentFile?.mkdirs()
    URL(url).openStream().use { input ->
        destination.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

fun unzip(zipFile: JFile, targetDir: JFile) {
    ZipInputStream(zipFile.inputStream().buffered()).use { zipStream ->
        var entry = zipStream.nextEntry
        while (entry != null) {
            val outFile = JFile(targetDir, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output ->
                    zipStream.copyTo(output)
                }
            }
            entry = zipStream.nextEntry
        }
    }
}

fun ensureCommandLineTools(sdkRoot: JFile): JFile {
    val cmdlineToolsRoot = JFile(sdkRoot, "cmdline-tools")
    val latestDir = JFile(cmdlineToolsRoot, "latest")
    val sdkManager = JFile(latestDir, "bin/sdkmanager")
    if (sdkManager.exists()) {
        if (!sdkManager.canExecute()) {
            val updated = sdkManager.setExecutable(true, false)
            if (!updated) {
                error("Не удалось сделать sdkmanager исполняемым")
            }
        }
        return sdkManager
    }

    println("Downloading Android command line tools for CI...")
    val archive = JFile(sdkRoot, "commandlinetools.zip")
    downloadFile(
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip",
        archive
    )

    val tempDir = kotlin.io.path.createTempDirectory("cmdline-tools").toFile()
    unzip(archive, tempDir)

    val extractedRoot = JFile(tempDir, "cmdline-tools")
    if (!extractedRoot.exists()) {
        error("Не удалось распаковать command line tools: отсутствует каталог cmdline-tools")
    }

    if (latestDir.exists()) {
        latestDir.deleteRecursively()
    }
    extractedRoot.copyRecursively(latestDir, overwrite = true)

    tempDir.deleteRecursively()
    archive.delete()

    val sdkManagerFile = JFile(latestDir, "bin/sdkmanager")
    if (!sdkManagerFile.canExecute()) {
        val updated = sdkManagerFile.setExecutable(true, false)
        if (!updated) {
            error("Не удалось сделать sdkmanager исполняемым")
        }
    }
    return sdkManagerFile
}

fun resolveJavaHome(): String {
    val javaHomeCandidate = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.home")
    val javaHomeDir = JFile(javaHomeCandidate)
    return if (JFile(javaHomeDir, "bin/java").exists()) {
        javaHomeDir.absolutePath
    } else {
        javaHomeDir.parentFile.absolutePath
    }
}

fun runSdkManager(sdkRoot: JFile, sdkManager: JFile, vararg packages: String) {
    val command = mutableListOf(sdkManager.absolutePath, "--sdk_root=${sdkRoot.absolutePath}")
    command.addAll(packages)

    val process = ProcessBuilder(command)
        .directory(sdkManager.parentFile)
        .apply {
            environment()["JAVA_HOME"] = resolveJavaHome()
        }
        .redirectErrorStream(true)
        .start()

    val reader = Thread {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { println(it) }
        }
    }
    reader.isDaemon = true
    reader.start()

    OutputStreamWriter(process.outputStream).use { writer ->
        repeat(30) {
            writer.appendLine("y")
        }
    }

    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("sdkmanager завершился с кодом $exitCode при установке: ${packages.joinToString()}")
    }
}

fun ensureAndroidSdk(sdkRoot: JFile) {
    sdkRoot.mkdirs()
    val sdkManager = ensureCommandLineTools(sdkRoot)

    val platform = JFile(sdkRoot, "platforms/android-35/android.jar")
    if (!platform.exists()) {
        println("Installing Android SDK Platform 35...")
        runSdkManager(sdkRoot, sdkManager, "platforms;android-35")
    }

    val buildTools = JFile(sdkRoot, "build-tools/35.0.0")
    if (!buildTools.exists()) {
        println("Installing Android SDK Build-Tools 35.0.0...")
        runSdkManager(sdkRoot, sdkManager, "build-tools;35.0.0")
    }

    val platformTools = JFile(sdkRoot, "platform-tools")
    if (!platformTools.exists()) {
        println("Installing Android SDK Platform-Tools...")
        runSdkManager(sdkRoot, sdkManager, "platform-tools")
    }
}

fun ensureOpenApiContract(rootDir: JFile) {
    val contractDir = JFile(rootDir, "api/contract")
    val specFile = JFile(contractDir, "openapi/openapi.yaml")
    if (specFile.exists()) {
        return
    }

    println("Downloading OpenAPI contract for CI...")
    specFile.parentFile?.mkdirs()
    downloadFile(
        "https://raw.githubusercontent.com/artkoder/kotopogoda-api-contract/main/openapi/openapi.yaml",
        specFile
    )
}

val androidSdkPath = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")

val resolvedSdkDir = if (androidSdkPath != null) JFile(androidSdkPath) else JFile(rootDir, ".android-sdk")

if (androidSdkPath == null) {
    ensureAndroidSdk(resolvedSdkDir)
}

if (resolvedSdkDir.exists()) {
    val sdkPath = resolvedSdkDir.absolutePath
    System.setProperty("android.home", sdkPath)
    System.setProperty("sdk.dir", sdkPath)

    val localProperties = JFile(rootDir, "local.properties")
    val escapedPath = sdkPath.replace("\\", "\\\\")
    if (localProperties.exists()) {
        val lines = localProperties.readLines().filterNot { it.startsWith("sdk.dir=") }
        localProperties.writeText((lines + "sdk.dir=$escapedPath").joinToString(separator = System.lineSeparator()) + System.lineSeparator())
    } else {
        localProperties.writeText("sdk.dir=$escapedPath\n")
    }
}

ensureOpenApiContract(rootDir)

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
include(":android-stubs")
