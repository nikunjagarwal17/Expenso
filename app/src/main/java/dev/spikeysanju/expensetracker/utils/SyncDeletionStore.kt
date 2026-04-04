package dev.spikeysanju.expensetracker.utils

import android.content.Context

object SyncDeletionStore {
    private const val PREF_NAME = "sync_deletion_store"
    private const val KEY_TX_IDS = "deleted_transaction_ids"
    private const val KEY_ACCOUNT_IDS = "deleted_account_ids"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun markTransactionDeleted(context: Context, transactionId: String) {
        if (transactionId.isBlank()) return
        val current = prefs(context).getStringSet(KEY_TX_IDS, emptySet()).orEmpty().toMutableSet()
        current.add(transactionId)
        prefs(context).edit().putStringSet(KEY_TX_IDS, current).commit()
    }

    fun markAccountDeleted(context: Context, accountId: String) {
        if (accountId.isBlank()) return
        val current = prefs(context).getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty().toMutableSet()
        current.add(accountId)
        prefs(context).edit().putStringSet(KEY_ACCOUNT_IDS, current).commit()
    }

    fun getDeletedTransactionIds(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_TX_IDS, emptySet()).orEmpty().toSet()
    }

    fun getDeletedAccountIds(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty().toSet()
    }

    fun clearDeletedTransactionId(context: Context, transactionId: String) {
        val current = prefs(context).getStringSet(KEY_TX_IDS, emptySet()).orEmpty().toMutableSet()
        if (current.remove(transactionId)) {
            prefs(context).edit().putStringSet(KEY_TX_IDS, current).commit()
        }
    }

    fun clearDeletedAccountId(context: Context, accountId: String) {
        val current = prefs(context).getStringSet(KEY_ACCOUNT_IDS, emptySet()).orEmpty().toMutableSet()
        if (current.remove(accountId)) {
            prefs(context).edit().putStringSet(KEY_ACCOUNT_IDS, current).commit()
        }
    }

    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY_TX_IDS).remove(KEY_ACCOUNT_IDS).commit()
    }
}
