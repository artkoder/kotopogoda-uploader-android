import org.gradle.api.GradleException
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    id("org.openapi.generator")
}

android {
    namespace = "com.kotopogoda.uploader.core.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 35
    }

    // IMPORTANT: wire generated sources via Android DSL (not global sourceSets)
    sourceSets {
        getByName("main") {
            java.srcDir("$buildDir/generated/openapi/src/main/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val contractSpec = rootProject.layout.projectDirectory.file("api/contract/openapi/openapi.yaml")

val ensureOpenApiSpec = tasks.register("ensureOpenApiSpec") {
    doLast {
        if (!contractSpec.asFile.exists()) {
            logger.lifecycle("OpenAPI contract not found. Initialising git submodule api/contract…")
            val process = ProcessBuilder("git", "submodule", "update", "--init", "api/contract")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (output.isNotEmpty()) {
                logger.lifecycle(output)
            }
            if (exitCode != 0) {
                throw GradleException("Failed to initialise api/contract submodule (exit code $exitCode).")
            }
        }

        if (!contractSpec.asFile.exists()) {
            throw GradleException("Missing OpenAPI contract at ${contractSpec.asFile}. Run `git submodule update --init`.")
        }
    }
}

tasks.named<GenerateTask>("openApiGenerate") {
    dependsOn(ensureOpenApiSpec)
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(contractSpec.asFile.absolutePath)
    outputDir.set("${buildDir}/generated/openapi")
    packageName.set("com.kotopogoda.uploader.api")
    additionalProperties.set(
        mapOf(
            "dateLibrary" to "java8",
            "useCoroutines" to "true"
        )
    )
}

// Ensure generation runs before any build of this module
tasks.named("preBuild").configure {
    dependsOn("openApiGenerate")
}

dependencies {
    implementation(project(":core:security"))
    implementation(project(":core:settings"))
    implementation(libs.com.squareup.retrofit2.retrofit)
    implementation(libs.com.squareup.retrofit2.converter.moshi)
    implementation(libs.com.squareup.retrofit2.converter.scalars)
    implementation(libs.com.squareup.okhttp3.okhttp)
    implementation(libs.com.squareup.okhttp3.logging.interceptor)
    implementation(libs.com.squareup.moshi.moshi)
    implementation(libs.com.squareup.moshi.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation("javax.inject:javax.inject:1")
    implementation(libs.dagger.hilt.android)
    kapt(libs.dagger.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)
    implementation(libs.androidx.documentfile)
}
