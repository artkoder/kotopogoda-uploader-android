package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.kotopogoda.uploader.core.data.ml.ModelDefinition
import com.kotopogoda.uploader.core.data.ml.ModelsLock
import com.kotopogoda.uploader.core.data.ml.ModelsLockParser
import com.kotopogoda.uploader.feature.viewer.BuildConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@MediumTest
class NativeEnhanceIntegrityTest {

    private lateinit var context: Context
    private lateinit var installer: EnhancerModelsInstaller
    private lateinit var lock: ModelsLock

    @Before
    fun setUp() {
        NativeEnhanceController.loadLibrary()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        installer = EnhancerModelsInstaller(context)
        lock = ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)
    }

    @Test
    fun initializeFailsWhenModelChecksumMismatch() = runTest {
        val modelsDir = installer.ensureInstalled()
        val tamperedFile = modelsDir.resolve("zerodcepp_fp16.bin")
        tamperedFile.writeText("tampered")

        val controller = NativeEnhanceController()
        val params = NativeEnhanceController.InitParams(
            assetManager = context.assets,
            modelsDir = modelsDir,
            zeroDceChecksums = lock.require("zerodcepp_fp16").toChecksums(),
            restormerChecksums = lock.require("restormer_fp16").toChecksums(),
            previewProfile = NativeEnhanceController.PreviewProfile.BALANCED,
        )

        try {
            val error = assertFailsWith<NativeEnhanceController.ModelIntegrityException> {
                controller.initialize(params)
            }

            assertTrue(
                actual = error.failure.filePath.contains("zerodcepp_fp16.bin"),
                message = "Имя поврежденного файла должно быть в сообщении об ошибке",
            )
            assertFalse(controller.isInitialized(), "Движок не должен инициализироваться после ошибки")
        } finally {
            installer.ensureInstalled()
        }
    }
}

private fun ModelDefinition.toChecksums(): NativeEnhanceController.ModelChecksums {
    val filesByExt = filesByExtension()
    val param = filesByExt["param"]?.sha256
        ?: error("Модель ${name} не содержит param файла")
    val bin = filesByExt["bin"]?.sha256
        ?: error("Модель ${name} не содержит bin файла")
    return NativeEnhanceController.ModelChecksums(param, bin)
}
