plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    // Retrofit brings OkHttp transitively; enough to compile generated interfaces
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
}
