package com.kotopogoda.uploader.feature.pairing.data

import com.kotopogoda.uploader.core.network.api.AttachDeviceRequest
import com.kotopogoda.uploader.core.network.api.AttachDeviceResponse
import com.kotopogoda.uploader.core.network.api.PairingApi
import com.kotopogoda.uploader.core.network.api.toDomain
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.ResponseBody
import retrofit2.HttpException

interface PairingRepository {
    suspend fun attach(token: String): AttachDeviceResponse
}

@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val networkClientProvider: NetworkClientProvider,
    private val moshi: Moshi,
) : PairingRepository {
    private val errorAdapter by lazy { moshi.adapter(ErrorResponse::class.java) }

    override suspend fun attach(token: String): AttachDeviceResponse {
        val api = networkClientProvider.create(PairingApi::class.java)
        return runCatching {
            api.attach(AttachDeviceRequest(token = token)).toDomain()
        }.getOrElse { throwable ->
            throw mapError(throwable)
        }
    }

    private fun mapError(throwable: Throwable): PairingException {
        if (throwable is HttpException) {
            val message = if (throwable.code() == 400) {
                parseErrorMessage(throwable.response()?.errorBody())
                    ?: "Неверный токен или срок действия истёк"
            } else {
                parseErrorMessage(throwable.response()?.errorBody())
            }
            return PairingException(message ?: "Не удалось привязать устройство", throwable)
        }
        return PairingException(throwable.message ?: "Не удалось привязать устройство", throwable)
    }

    private fun parseErrorMessage(body: ResponseBody?): String? {
        if (body == null) return null
        val content = runCatching { body.string() }.getOrNull() ?: return null
        return runCatching { errorAdapter.fromJson(content)?.error }
            .getOrNull()
            ?.takeUnless { it.isNullOrBlank() }
    }
}

@JsonClass(generateAdapter = true)
data class ErrorResponse(
    @Json(name = "error") val error: String? = null,
)

class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)
