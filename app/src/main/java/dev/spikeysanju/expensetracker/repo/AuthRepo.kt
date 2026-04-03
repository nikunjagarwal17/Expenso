package dev.spikeysanju.expensetracker.repo

import android.content.Context
import dev.spikeysanju.expensetracker.data.remote.client.ApiResult
import dev.spikeysanju.expensetracker.data.remote.client.AuthApiClient
import dev.spikeysanju.expensetracker.utils.AuthSessionManager
import javax.inject.Inject

class AuthRepo @Inject constructor(
    private val authApiClient: AuthApiClient
) {
    suspend fun signup(email: String, password: String, fullName: String?): ApiResult<String> {
        return authApiClient.signup(email = email, password = password, fullName = fullName)
    }

    suspend fun login(
        context: Context,
        email: String,
        password: String
    ): ApiResult<Unit> {
        return when (val result = authApiClient.login(email = email, password = password)) {
            is ApiResult.Success -> {
                AuthSessionManager.saveSession(
                    context = context,
                    accessToken = result.data.accessToken,
                    refreshToken = result.data.refreshToken,
                    expiresAtSeconds = result.data.expiresAt
                )
                ApiResult.Success(Unit)
            }

            is ApiResult.Error -> result
        }
    }

    suspend fun refreshSessionIfNeeded(context: Context): ApiResult<Unit> {
        val refreshToken = AuthSessionManager.getRefreshToken(context)
            ?: return ApiResult.Error("No refresh token found")

        if (!AuthSessionManager.isAccessTokenExpired(context)) {
            return ApiResult.Success(Unit)
        }

        return when (val result = authApiClient.refresh(refreshToken)) {
            is ApiResult.Success -> {
                AuthSessionManager.saveSession(
                    context = context,
                    accessToken = result.data.accessToken,
                    refreshToken = result.data.refreshToken,
                    expiresAtSeconds = result.data.expiresAt
                )
                ApiResult.Success(Unit)
            }

            is ApiResult.Error -> {
                AuthSessionManager.clearSession(context)
                result
            }
        }
    }

    suspend fun resendVerification(email: String): ApiResult<String> {
        return authApiClient.resendVerification(email)
    }

    suspend fun forgotPassword(email: String): ApiResult<String> {
        return authApiClient.forgotPassword(email)
    }

    fun logout(context: Context) {
        AuthSessionManager.clearSession(context)
    }
}
