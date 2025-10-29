plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.execution.TaskExecutionGraph

val modelsLockLiteral: String by rootProject.extra

android {
    namespace = "com.kotopogoda.uploader"
    compileSdk = 35

    val appTag = (project.findProperty("appTag") as? String) ?: "0.0.0"
    val appCode = project.findProperty("appCode")?.toString()?.toIntOrNull() ?: 1
    val contractTag = (project.findProperty("contractTag") as? String) ?: "v1.4.1"
    val prodApiBaseUrl = "https://cat-weather-new.fly.dev"

    defaultConfig {
        applicationId = "com.kotopogoda.uploader"
        minSdk = 26
        targetSdk = 35
        versionCode = appCode
        versionName = appTag

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "API_BASE_URL", "\"$prodApiBaseUrl\"")
        buildConfigField("String", "MODELS_LOCK_JSON", modelsLockLiteral)
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE").orEmpty()
            if (keystorePath.isNotBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_ALIAS_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"$prodApiBaseUrl\"")
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            buildConfigField("String", "API_BASE_URL", "\"$prodApiBaseUrl\"")
        }
    }

    buildTypes.all {
        buildConfigField("String", "CONTRACT_VERSION", "\"$contractTag\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() // 1.5.11
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

gradle.taskGraph.whenReady(object : Action<TaskExecutionGraph> {
    override fun execute(graph: TaskExecutionGraph) {
        val releaseTaskPaths = listOf(
            "${project.path}:assembleRelease",
            "${project.path}:bundleRelease",
        )
        val releaseTaskRequested = releaseTaskPaths.any(graph::hasTask)

        if (releaseTaskRequested) {
            fun requireEnv(name: String) {
                if (System.getenv(name).isNullOrBlank()) {
                    throw GradleException("$name is not set. Release signing cannot proceed.")
                }
            }

            listOf(
                "ANDROID_KEYSTORE_FILE",
                "ANDROID_KEYSTORE_PASSWORD",
                "ANDROID_KEY_ALIAS",
                "ANDROID_KEY_ALIAS_PASSWORD",
            ).forEach(::requireEnv)
        }
    }
})

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:settings"))
    implementation(project(":core:logging"))
    implementation(project(":core:work"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:viewer"))
    implementation(project(":feature:queue"))
    implementation(project(":feature:status"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.navigation)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.com.squareup.retrofit2.retrofit)
    implementation(libs.com.squareup.retrofit2.converter.moshi)
    implementation(libs.com.squareup.okhttp3.okhttp)
    implementation(libs.com.squareup.okhttp3.logging.interceptor)
    implementation(libs.timber)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.dagger.hilt.android)
    kapt(libs.dagger.hilt.compiler)
    kapt(libs.androidx.hilt.compiler)
    implementation(libs.material)


    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
