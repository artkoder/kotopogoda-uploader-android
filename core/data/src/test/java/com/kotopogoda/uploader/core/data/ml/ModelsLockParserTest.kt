package com.kotopogoda.uploader.core.data.ml

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelsLockParserTest {

    @Test
    fun `парсинг валидного models lock json`() {
        val json = """
            {
                "repository": "test-repo",
                "models": {
                    "test_model": {
                        "release": "v1.0",
                        "asset": "test_model.zip",
                        "sha256": "abc123",
                        "backend": "TFLITE",
                        "min_mb": 1.5,
                        "precision": "fp16",
                        "enabled": true,
                        "files": [
                            {
                                "path": "model.tflite",
                                "sha256": "def456",
                                "min_mb": 1.0
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val result = ModelsLockParser.parse(json)

        assertNotNull(result)
        assertEquals("test-repo", result.repository)
        assertEquals(1, result.models.size)
        
        val model = result.models["test_model"]
        assertNotNull(model)
        assertEquals("test_model", model.name)
        assertEquals("v1.0", model.release)
        assertEquals("test_model.zip", model.asset)
        assertEquals("abc123", model.sha256)
        assertEquals(ModelBackend.TFLITE, model.backend)
        assertEquals("fp16", model.precision)
        assertTrue(model.enabled)
        assertEquals(1, model.files.size)
        
        val file = model.files[0]
        assertEquals("model.tflite", file.path)
        assertEquals("def456", file.sha256)
    }

    @Test
    fun `ошибка при отсутствии объекта models`() {
        val json = """
            {
                "repository": "test-repo"
            }
        """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            ModelsLockParser.parse(json)
        }
        
        assertTrue(exception.message!!.contains("models"))
    }

    @Test
    fun `ошибка при неизвестном backend с информативным сообщением`() {
        val json = """
            {
                "models": {
                    "test_model": {
                        "release": "v1.0",
                        "asset": "test_model.zip",
                        "backend": "METADATA",
                        "files": [
                            {
                                "path": "model.bin",
                                "sha256": "abc123"
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            ModelsLockParser.parse(json)
        }
        
        assertTrue(exception.message!!.contains("METADATA"))
        assertTrue(exception.message!!.contains("Поддерживаемые backends"))
        assertTrue(exception.message!!.contains("устаревшая версия"))
    }

    @Test
    fun `ошибка при отсутствии массива files`() {
        val json = """
            {
                "models": {
                    "test_model": {
                        "release": "v1.0",
                        "asset": "test_model.zip",
                        "backend": "NCNN"
                    }
                }
            }
        """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            ModelsLockParser.parse(json)
        }
        
        assertTrue(exception.message!!.contains("files"))
    }

    @Test
    fun `ошибка при пустом массиве files`() {
        val json = """
            {
                "models": {
                    "test_model": {
                        "release": "v1.0",
                        "asset": "test_model.zip",
                        "backend": "NCNN",
                        "files": []
                    }
                }
            }
        """.trimIndent()

        val exception = assertFailsWith<IllegalArgumentException> {
            ModelsLockParser.parse(json)
        }
        
        assertTrue(exception.message!!.contains("не содержит описания файлов"))
    }

    @Test
    fun `парсинг с nullable полями`() {
        val json = """
            {
                "models": {
                    "test_model": {
                        "release": "v1.0",
                        "asset": "test_model.zip",
                        "backend": "NCNN",
                        "files": [
                            {
                                "path": "model.param",
                                "sha256": "abc123"
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val result = ModelsLockParser.parse(json)

        assertNotNull(result)
        assertEquals(null, result.repository)
        
        val model = result.models["test_model"]
        assertNotNull(model)
        assertEquals(null, model.sha256)
        assertEquals(null, model.precision)
        assertTrue(model.enabled)
        assertEquals(0L, model.minBytes)
    }

    @Test
    fun `enabled=false помечает модель как отключённую`() {
        val json = """
            {
                "models": {
                    "disabled_model": {
                        "release": "v1.0",
                        "asset": "disabled.zip",
                        "backend": "NCNN",
                        "enabled": false,
                        "files": [
                            {
                                "path": "model.bin",
                                "sha256": "abc123"
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val result = ModelsLockParser.parse(json)
        val model = result.models["disabled_model"]
        assertNotNull(model)
        assertFalse(model.enabled)
    }

    @Test
    fun `assetPath добавляет префикс models когда путь без директории`() {
        val file = ModelFile(path = "model.bin", sha256 = "abc123", minBytes = 0)
        assertEquals("models/model.bin", file.assetPath())

        val nested = ModelFile(path = "models/restormer.bin", sha256 = "def456", minBytes = 0)
        assertEquals("models/restormer.bin", nested.assetPath())

        val custom = ModelFile(path = "weights/zero.bin", sha256 = "ghi789", minBytes = 0)
        assertEquals("weights/zero.bin", custom.assetPath())
    }
}
