plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.execution.TaskExecutionGraph
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26"
                )
            }
        }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
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

// Задача для загрузки предсобранных NCNN библиотек
tasks.register("fetchNcnn") {
    group = "build setup"
    description = "Скачивает и распаковывает предсобранные NCNN библиотеки"
    
    val ncnnVersion = "20240410"
    val ncnnUrl = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip"
    val expectedSha256 = "352f7f6b11e862c72b72de4b8133e0b237b43d030c079aa8ed653f3c8e944580"
    
    val downloadDir = layout.buildDirectory.dir("ncnn-download").get().asFile
    val cppDir = layout.projectDirectory.dir("src/main/cpp").asFile
    val ncnnIncludeDir = file("$cppDir/ncnn/include")
    val ncnnLibDir = file("$cppDir/ncnn-lib/arm64-v8a")
    
    outputs.dir(ncnnIncludeDir)
    outputs.dir(ncnnLibDir)
    
    doLast {
        // Проверяем, нужна ли загрузка
        val libFile = file("$ncnnLibDir/libncnn.a")
        if (libFile.exists() && ncnnIncludeDir.exists()) {
            logger.lifecycle("NCNN уже распакован, пропускаем загрузку")
            return@doLast
        }
        
        downloadDir.mkdirs()
        val zipFile = file("$downloadDir/ncnn-$ncnnVersion-android-vulkan.zip")
        
        // Скачиваем архив
        logger.lifecycle("Скачивание NCNN из $ncnnUrl")
        val connection = URL(ncnnUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        try {
            connection.inputStream.use { input ->
                zipFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
        
        // Проверяем SHA-256
        logger.lifecycle("Проверка SHA-256...")
        val digest = MessageDigest.getInstance("SHA-256")
        val actualSha256 = zipFile.inputStream().use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(8192)
                while (stream.read(buffer) != -1) {
                    // читаем до конца
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw GradleException("Неверный SHA-256 NCNN архива: ожидалось $expectedSha256, получено $actualSha256")
        }
        
        logger.lifecycle("SHA-256 проверен успешно")
        
        // Очищаем целевые директории
        ncnnIncludeDir.deleteRecursively()
        ncnnLibDir.deleteRecursively()
        
        // Распаковываем только нужные файлы
        logger.lifecycle("Распаковка NCNN...")
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                
                // Распаковываем заголовочные файлы из arm64-v8a/include/ncnn/
                if (name.startsWith("ncnn-$ncnnVersion-android-vulkan/arm64-v8a/include/ncnn/")) {
                    val relativePath = name.removePrefix("ncnn-$ncnnVersion-android-vulkan/arm64-v8a/include/")
                    val outFile = file("$cppDir/ncnn/include/$relativePath")
                    
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile.mkdirs()
                        outFile.outputStream().use { output ->
                            zip.copyTo(output)
                        }
                        logger.lifecycle("Распакован: include/$relativePath")
                    }
                }
                
                // Распаковываем все .a библиотеки для arm64-v8a
                if (name.startsWith("ncnn-$ncnnVersion-android-vulkan/arm64-v8a/lib/") && name.endsWith(".a")) {
                    val libName = name.substringAfterLast("/")
                    val outFile = file("$ncnnLibDir/$libName")
                    outFile.parentFile.mkdirs()
                    outFile.outputStream().use { output ->
                        zip.copyTo(output)
                    }
                    logger.lifecycle("Распакован: $libName для arm64-v8a")
                }
                
                entry = zip.nextEntry
            }
        }
        
        // Проверяем, что файлы распакованы
        if (!libFile.exists()) {
            throw GradleException("Не удалось распаковать libncnn.a")
        }
        if (!ncnnIncludeDir.exists() || ncnnIncludeDir.listFiles()?.isEmpty() != false) {
            throw GradleException("Не удалось распаковать заголовочные файлы NCNN")
        }
        
        logger.lifecycle("NCNN успешно загружен и распакован")
    }
}

// Делаем preBuild зависимым от fetchNcnn
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("fetchNcnn")
}

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
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
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
