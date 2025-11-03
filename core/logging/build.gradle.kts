plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kotopogoda.uploader.core.logging"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:settings"))
    implementation(project(":core:work"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dagger.hilt.android)
    kapt(libs.dagger.hilt.compiler)
    api(libs.timber)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.com.squareup.okhttp3.logging.interceptor)

    // Экспортируем test utilities для использования в других модулях
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
    api("org.robolectric:robolectric:4.12.1")
    api("androidx.test:core-ktx:1.5.0")

    testImplementation(kotlin("test"))
    testImplementation(libs.org.json)
}
