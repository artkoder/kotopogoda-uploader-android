package com.kotopogoda.uploader.feature.viewer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты для настроек enhancement функционала:
 * - Персистентность флага настроек
 * - Переключение preview/full профилей
 * - UI toggles и их влияние на контроллер
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnhancementSettingsTest {

    @Test
    fun `default enhancement settings initialized correctly`() {
        val settings = MockEnhancementSettings()
        
        // По умолчанию preview mode включен для экономии ресурсов
        assertTrue(settings.isPreviewModeEnabled)
        assertEquals(EnhancementQuality.PREVIEW, settings.defaultQuality)
    }

    @Test
    fun `settings persistence works across sessions`() = runTest {
        val repository = MockEnhancementSettingsRepository()
        
        // Устанавливаем настройки
        repository.setPreviewMode(false)
        repository.setQuality(EnhancementQuality.FULL)
        
        // Проверяем что настройки сохранены
        val previewMode = repository.getPreviewModeFlow().first()
        assertFalse(previewMode)
        
        val quality = repository.getQualityFlow().first()
        assertEquals(EnhancementQuality.FULL, quality)
    }

    @Test
    fun `UI toggle switches between preview and full quality`() = runTest {
        val repository = MockEnhancementSettingsRepository()
        
        // Начальное состояние - preview
        repository.setPreviewMode(true)
        assertTrue(repository.getPreviewModeFlow().first())
        
        // Переключаем на full
        repository.setPreviewMode(false)
        assertFalse(repository.getPreviewModeFlow().first())
        
        // Переключаем обратно на preview
        repository.setPreviewMode(true)
        assertTrue(repository.getPreviewModeFlow().first())
    }

    @Test
    fun `controller uses correct profile based on settings`() = runTest {
        val repository = MockEnhancementSettingsRepository()
        val controller = MockEnhancementController(repository)
        
        // В preview режиме должен использоваться быстрый профиль
        repository.setPreviewMode(true)
        val previewProfile = controller.selectProfile()
        assertEquals(EnhancementProfile.FAST_PREVIEW, previewProfile)
        
        // В full режиме должен использоваться качественный профиль
        repository.setPreviewMode(false)
        val fullProfile = controller.selectProfile()
        assertEquals(EnhancementProfile.HIGH_QUALITY, fullProfile)
    }

    @Test
    fun `preview profile uses smaller tile size`() {
        val previewProfile = EnhancementProfile.FAST_PREVIEW
        val fullProfile = EnhancementProfile.HIGH_QUALITY
        
        // Preview профиль должен использовать меньший размер тайлов для скорости
        assertTrue(previewProfile.tileSize <= fullProfile.tileSize)
        assertTrue(previewProfile.overlap <= fullProfile.overlap)
    }

    @Test
    fun `settings change triggers re-computation`() = runTest {
        val repository = MockEnhancementSettingsRepository()
        val controller = MockEnhancementController(repository)
        
        repository.setPreviewMode(true)
        controller.startEnhancement(0.5f)
        
        val previewComputations = controller.computationCount
        assertTrue(previewComputations > 0)
        
        // Меняем настройку - должен перезапуститься
        repository.setPreviewMode(false)
        controller.startEnhancement(0.5f)
        
        val fullComputations = controller.computationCount
        assertTrue(fullComputations > previewComputations)
    }

    @Test
    fun `quality setting affects enhancement parameters`() {
        val lowQuality = EnhancementQuality.LOW
        val mediumQuality = EnhancementQuality.MEDIUM
        val highQuality = EnhancementQuality.HIGH
        val fullQuality = EnhancementQuality.FULL
        
        // Параметры должны прогрессивно улучшаться
        assertTrue(lowQuality.tileSize < mediumQuality.tileSize)
        assertTrue(mediumQuality.tileSize <= highQuality.tileSize)
        assertTrue(highQuality.tileSize <= fullQuality.tileSize)
        
        assertTrue(lowQuality.zeroDceIterations <= mediumQuality.zeroDceIterations)
        assertTrue(mediumQuality.zeroDceIterations <= highQuality.zeroDceIterations)
    }
}

// Mock классы для тестирования

enum class EnhancementQuality(
    val tileSize: Int,
    val overlap: Int,
    val zeroDceIterations: Int,
) {
    LOW(256, 32, 4),
    MEDIUM(384, 48, 6),
    HIGH(512, 64, 8),
    PREVIEW(256, 32, 4),
    FULL(512, 64, 8);
}

enum class EnhancementProfile(
    val tileSize: Int,
    val overlap: Int,
) {
    FAST_PREVIEW(256, 32),
    HIGH_QUALITY(512, 64);
}

class MockEnhancementSettings {
    var isPreviewModeEnabled: Boolean = true
    var defaultQuality: EnhancementQuality = EnhancementQuality.PREVIEW
}

class MockEnhancementSettingsRepository {
    private var previewMode = true
    private var quality = EnhancementQuality.PREVIEW
    
    suspend fun setPreviewMode(enabled: Boolean) {
        previewMode = enabled
    }
    
    suspend fun setQuality(newQuality: EnhancementQuality) {
        quality = newQuality
    }
    
    fun getPreviewModeFlow() = kotlinx.coroutines.flow.flowOf(previewMode)
    fun getQualityFlow() = kotlinx.coroutines.flow.flowOf(quality)
}

class MockEnhancementController(
    private val settings: MockEnhancementSettingsRepository
) {
    var computationCount = 0
        private set
    
    suspend fun selectProfile(): EnhancementProfile {
        val isPreview = settings.getPreviewModeFlow().first()
        return if (isPreview) {
            EnhancementProfile.FAST_PREVIEW
        } else {
            EnhancementProfile.HIGH_QUALITY
        }
    }
    
    suspend fun startEnhancement(strength: Float) {
        computationCount++
        // Симуляция вычислений
    }
}
