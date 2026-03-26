package dev.spikeysanju.expensetracker.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyPreference @Inject constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
    fun getSymbol(): String = prefs.getString("currency_symbol", "₹") ?: "₹"
    fun setSymbol(symbol: String) = prefs.edit().putString("currency_symbol", symbol).apply()
}
