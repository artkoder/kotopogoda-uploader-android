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

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    // OpenAPI spec comes from the contract submodule
    inputSpec.set("${rootDir}/api/contract/openapi/openapi.yaml")
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

    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
}
