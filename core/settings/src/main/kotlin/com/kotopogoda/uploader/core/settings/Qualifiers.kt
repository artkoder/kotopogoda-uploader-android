package com.kotopogoda.uploader.core.settings

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultBaseUrl

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsPreferencesStore
