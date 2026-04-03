package dev.spikeysanju.expensetracker.data.remote.client

import android.content.Context
import dev.spikeysanju.expensetracker.BuildConfig
import dev.spikeysanju.expensetracker.utils.AuthSessionManager
import okhttp3.Interceptor
import okhttp3.Response

class SupabaseAuthInterceptor(
    private val appContext: Context
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = AuthSessionManager.getAccessToken(appContext)

        val requestBuilder = chain.request().newBuilder()
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
