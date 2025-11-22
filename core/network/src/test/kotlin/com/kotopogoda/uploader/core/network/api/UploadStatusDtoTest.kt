package com.kotopogoda.uploader.core.network.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

class UploadStatusDtoTest {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(UploadStatusDto::class.java)

    @Test
    fun `json with ocr_remaining_percent is parsed correctly`() {
        val json = """
            {
                "status": "done",
                "processed": true,
                "ocr_remaining_percent": 95
            }
        """.trimIndent()

        val dto = adapter.fromJson(json)

        assertNotNull(dto)
        assertEquals(95, dto.ocrRemainingPercent)
    }
}
