package dev.spikeysanju.expensetracker.utils

import android.content.Context

object AuthSessionManager {
    private const val PREF_NAME = "auth_session_pref"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT_SECONDS = "expires_at_seconds"
    private const val KEY_LAST_ACTIVE_AT_MS = "last_active_at_ms"
    private const val SESSION_INACTIVITY_LIMIT_MS = 30L * 24 * 60 * 60 * 1000

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtSeconds: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isLoggedIn(context: Context): Boolean {
        if (isSessionExpiredByInactivity(context)) {
            clearSession(context)
            return false
        }
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
        val cleanAccessToken = sanitizeToken(accessToken)
        val cleanRefreshToken = sanitizeToken(refreshToken)
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, cleanAccessToken)
            .putString(KEY_REFRESH_TOKEN, cleanRefreshToken)
            .putLong(KEY_EXPIRES_AT_SECONDS, expiresAtSeconds)
            .putLong(KEY_LAST_ACTIVE_AT_MS, System.currentTimeMillis())
            .commit()
    }

    fun getSession(context: Context): Session? {
        val accessToken = getAccessToken(context) ?: return null
        val refreshToken = getRefreshToken(context) ?: return null
        val expiresAtSeconds = prefs(context).getLong(KEY_EXPIRES_AT_SECONDS, 0L)
        return Session(accessToken, refreshToken, expiresAtSeconds)
    }

    fun getAccessToken(context: Context): String? {
        return prefs(context).getString(KEY_ACCESS_TOKEN, null)?.let { sanitizeToken(it) }
    }

    fun getRefreshToken(context: Context): String? {
        return prefs(context).getString(KEY_REFRESH_TOKEN, null)?.let { sanitizeToken(it) }
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_SECONDS)
            .remove(KEY_LAST_ACTIVE_AT_MS)
            .commit()
    }

    fun touchSession(context: Context) {
        if (getAccessToken(context).isNullOrBlank()) return
        prefs(context).edit()
            .putLong(KEY_LAST_ACTIVE_AT_MS, System.currentTimeMillis())
            .commit()
    }

    fun isSessionExpiredByInactivity(context: Context): Boolean {
        val lastActive = prefs(context).getLong(KEY_LAST_ACTIVE_AT_MS, 0L)
        if (lastActive <= 0L) {
            return false
        }
        return System.currentTimeMillis() - lastActive > SESSION_INACTIVITY_LIMIT_MS
    }

    fun isAccessTokenExpired(context: Context): Boolean {
        val expiresAtSeconds = prefs(context).getLong(KEY_EXPIRES_AT_SECONDS, 0L)
        if (expiresAtSeconds <= 0L) {
            // Some responses may omit expires_at; treat access token as usable and let server enforce auth.
            return false
        }

        val nowEpochSeconds = System.currentTimeMillis() / 1000
        return nowEpochSeconds >= (expiresAtSeconds - 30)
    }

    private fun sanitizeToken(token: String): String {
        return token
            .trim()
            .removeSurrounding("\"")
            .replace("\r", "")
            .replace("\n", "")
    }
}