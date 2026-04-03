package dev.spikeysanju.expensetracker.utils

import android.content.Context

object AuthSessionManager {
    private const val PREF_NAME = "auth_session_pref"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT_SECONDS = "expires_at_seconds"

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtSeconds: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isLoggedIn(context: Context): Boolean {
        val accessToken = getAccessToken(context)
        return !accessToken.isNullOrBlank()
    }

    fun setLoggedIn(context: Context, isLoggedIn: Boolean) {
        if (!isLoggedIn) {
            clearSession(context)
        }
    }

    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAtSeconds: Long
    ) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT_SECONDS, expiresAtSeconds)
            .apply()
    }

    fun getSession(context: Context): Session? {
        val accessToken = getAccessToken(context) ?: return null
        val refreshToken = getRefreshToken(context) ?: return null
        val expiresAtSeconds = prefs(context).getLong(KEY_EXPIRES_AT_SECONDS, 0L)
        return Session(accessToken, refreshToken, expiresAtSeconds)
    }

    fun getAccessToken(context: Context): String? {
        return prefs(context).getString(KEY_ACCESS_TOKEN, null)
    }

    fun getRefreshToken(context: Context): String? {
        return prefs(context).getString(KEY_REFRESH_TOKEN, null)
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_SECONDS)
            .apply()
    }

    fun isAccessTokenExpired(context: Context): Boolean {
        val expiresAtSeconds = prefs(context).getLong(KEY_EXPIRES_AT_SECONDS, 0L)
        if (expiresAtSeconds <= 0L) {
            return true
        }

        val nowEpochSeconds = System.currentTimeMillis() / 1000
        return nowEpochSeconds >= (expiresAtSeconds - 30)
    }
}