package dev.spikeysanju.expensetracker.data.remote.client

import android.content.Context
import dev.spikeysanju.expensetracker.BuildConfig
import dev.spikeysanju.expensetracker.utils.AuthSessionManager
import dev.spikeysanju.expensetracker.utils.SyncLogFile
import okhttp3.Interceptor
import okhttp3.Response

class SupabaseAuthInterceptor(
    private val appContext: Context
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = AuthSessionManager.getAccessToken(appContext)
        val incomingRequest = chain.request()

        val requestBuilder = incomingRequest.newBuilder()
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
            AuthSessionManager.touchSession(appContext)
        }

        val request = requestBuilder.build()
        SyncLogFile.append(
            appContext,
            "http.request ${request.method} ${request.url.encodedPath} token_present=${!token.isNullOrBlank()} token_len=${token?.length ?: 0}"
        )

        val response = chain.proceed(request)
        if (response.code >= 400) {
            val errorPreview = runCatching { response.peekBody(1024).string() }.getOrDefault("")
            SyncLogFile.append(
                appContext,
                "http.response_error code=${response.code} path=${request.url.encodedPath} body=${errorPreview.take(500)}"
            )
        }
        return response
    }
}
