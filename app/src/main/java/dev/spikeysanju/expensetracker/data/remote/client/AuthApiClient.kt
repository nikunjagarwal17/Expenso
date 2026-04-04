package dev.spikeysanju.expensetracker.data.remote.client

import com.google.gson.Gson
import dev.spikeysanju.expensetracker.data.remote.api.AuthApiService
import dev.spikeysanju.expensetracker.data.remote.dto.AuthTokenResponse
import dev.spikeysanju.expensetracker.data.remote.dto.ErrorResponse
import dev.spikeysanju.expensetracker.data.remote.dto.ForgotPasswordRequest
import dev.spikeysanju.expensetracker.data.remote.dto.LoginRequest
import dev.spikeysanju.expensetracker.data.remote.dto.RefreshRequest
import dev.spikeysanju.expensetracker.data.remote.dto.ResendVerificationRequest
import dev.spikeysanju.expensetracker.data.remote.dto.SignupRequest
import javax.inject.Inject
import retrofit2.Response

class AuthApiClient @Inject constructor(
    private val authApiService: AuthApiService,
    private val gson: Gson
) {
    suspend fun signup(email: String, password: String, fullName: String?): ApiResult<String> {
        return runCatching {
            val response = authApiService.signup(
                SignupRequest(
                    email = email,
                    password = password,
                    fullName = fullName
                )
            )

            if (response.isSuccessful) {
                ApiResult.Success(
                    response.body()?.message
                        ?: "Signup created. Verify your email before logging in."
                )
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun login(email: String, password: String): ApiResult<AuthTokenResponse> {
        return runCatching {
            val response = authApiService.login(LoginRequest(email = email, password = password))
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error("Invalid server response")
                }
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun refresh(refreshToken: String): ApiResult<AuthTokenResponse> {
        return runCatching {
            val response = authApiService.refresh(RefreshRequest(refreshToken = refreshToken))
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error("Invalid server response")
                }
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun resendVerification(email: String): ApiResult<String> {
        return runCatching {
            val response = authApiService.resendVerification(ResendVerificationRequest(email = email))
            if (response.isSuccessful) {
                ApiResult.Success(
                    response.body()?.message ?: "Verification email sent"
                )
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun forgotPassword(email: String): ApiResult<String> {
        return runCatching {
            val response = authApiService.forgotPassword(ForgotPasswordRequest(email = email))
            if (response.isSuccessful) {
                ApiResult.Success(
                    response.body()?.message ?: "Password reset email sent"
                )
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    private fun buildError(response: Response<*>): ApiResult.Error {
        val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
        val parsed = parseError(rawBody)
        val details = buildString {
            append("HTTP ")
            append(response.code())
            if (response.message().isNotBlank()) {
                append(" ")
                append(response.message())
            }
            append(" | ")
            append(parsed)
            if (!rawBody.isNullOrBlank()) {
                append(" | body=")
                append(rawBody.take(500))
            }
        }
        return ApiResult.Error(details, response.code())
    }

    private fun parseError(rawBody: String?): String {
        if (rawBody.isNullOrBlank()) {
            return "Request failed"
        }

        return runCatching {
            gson.fromJson(rawBody, ErrorResponse::class.java)?.error
        }.getOrNull().orEmpty().ifBlank { "Request failed" }
    }
}
