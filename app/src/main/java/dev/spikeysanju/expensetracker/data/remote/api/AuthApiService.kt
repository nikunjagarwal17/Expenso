package dev.spikeysanju.expensetracker.data.remote.api

import dev.spikeysanju.expensetracker.data.remote.dto.AuthTokenResponse
import dev.spikeysanju.expensetracker.data.remote.dto.ForgotPasswordRequest
import dev.spikeysanju.expensetracker.data.remote.dto.LoginRequest
import dev.spikeysanju.expensetracker.data.remote.dto.MessageResponse
import dev.spikeysanju.expensetracker.data.remote.dto.RefreshRequest
import dev.spikeysanju.expensetracker.data.remote.dto.ResendVerificationRequest
import dev.spikeysanju.expensetracker.data.remote.dto.SignupRequest
import dev.spikeysanju.expensetracker.data.remote.dto.SignupResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {
    @POST("auth-api/signup")
    suspend fun signup(@Body request: SignupRequest): Response<SignupResponse>

    @POST("auth-api/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthTokenResponse>

    @POST("auth-api/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<AuthTokenResponse>

    @POST("auth-api/resend-verification")
    suspend fun resendVerification(@Body request: ResendVerificationRequest): Response<MessageResponse>

    @POST("auth-api/forgot-password")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequest): Response<MessageResponse>
}
