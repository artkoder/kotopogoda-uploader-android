plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kotopogoda.uploader.core.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
            }
        }
    }

    kapt {
        correctErrorTypes = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "-Xmx2048m",
                    "-XX:MaxMetaspaceSize=512m",
                    "-Dkotlinx.coroutines.debug=off"
                )
                it.systemProperty("kotlinx.coroutines.debug", "off")
            }
        }
    }

    sourceSets {
        getByName("test") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":core:work"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.dagger.hilt.android)
    kapt(libs.dagger.hilt.compiler)
    implementation(libs.androidx.documentfile)
    implementation(libs.exif)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(project(":core:settings"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation("io.mockk:mockk-agent-jvm:1.13.8")
    testImplementation("androidx.room:room-testing:${libs.versions.room.get()}")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation(project(":core:logging"))
    kaptTest(libs.dagger.hilt.compiler)
}

// Временно отключаем проблемные SAF тесты из-за mockkStatic OOM
// TODO: Переписать с Robolectric после релиза
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("com.kotopogoda.uploader.core.data.sa.SaFileRepositoryTest_MediaStoreCrossDrive")
        excludeTestsMatching("com.kotopogoda.uploader.core.data.sa.SaFileRepositoryTest_SafDocuments")
    }
}
