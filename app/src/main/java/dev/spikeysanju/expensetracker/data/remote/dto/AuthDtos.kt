package dev.spikeysanju.expensetracker.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SignupRequest(
    val email: String,
    val password: String,
    @SerializedName("full_name")
    val fullName: String?
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class RefreshRequest(
    @SerializedName("refresh_token")
    val refreshToken: String
)

data class ForgotPasswordRequest(
    val email: String
)

data class ResendVerificationRequest(
    val email: String
)

data class ApiUser(
    val id: String,
    val email: String?,
    @SerializedName("full_name")
    val fullName: String?
)

data class AuthTokenResponse(
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("refresh_token")
    val refreshToken: String,
    @SerializedName("expires_at")
    val expiresAt: Long,
    @SerializedName("token_type")
    val tokenType: String,
    val user: ApiUser?
)

data class SignupResponse(
    val message: String?,
    @SerializedName("requires_email_verification")
    val requiresEmailVerification: Boolean?
)

data class MessageResponse(
    val message: String?
)

data class ErrorResponse(
    val error: String?
)
