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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }


    sourceSets {
        getByName("main") {
            java.srcDir("$buildDir/generated/openapi/src/main/kotlin")
        }
    }

}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.com.squareup.retrofit2.retrofit)
    implementation(libs.com.squareup.retrofit2.converter.moshi)
    implementation(libs.com.squareup.okhttp3.okhttp)
    implementation(libs.com.squareup.okhttp3.logging.interceptor)
    implementation(libs.dagger.hilt.android)
    kapt(libs.dagger.hilt.compiler)
}

openApiGenerate {
    // Генератор Kotlin-интерфейсов под Retrofit2
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")

    // Берём спецификацию из субмодуля
    inputSpec.set("${rootDir}/api/contract/openapi/openapi.yaml")

    // Куда класть сгенерированный код
    outputDir.set("${buildDir}/generated/openapi")

    // Пакет итоговых классов
    packageName.set("com.kotopogoda.uploader.api")

    // Опции генератора
    additionalProperties.set(
        mapOf(
            "dateLibrary" to "java8",
            "useCoroutines" to "true"
        )
    )
}

// Компиляция должна ждать генерацию
tasks.named("preBuild").configure {
    dependsOn("openApiGenerate")
}

// Подключаем сгенерированные исходники в модуль
sourceSets {
        getByName("main") {
            java.srcDir("$buildDir/generated/openapi/src/main/kotlin")
        }
    }
