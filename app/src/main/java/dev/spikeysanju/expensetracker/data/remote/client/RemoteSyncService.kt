package dev.spikeysanju.expensetracker.data.remote.client

import androidx.room.withTransaction
import dev.spikeysanju.expensetracker.data.local.AppDatabase
import dev.spikeysanju.expensetracker.model.Account
import dev.spikeysanju.expensetracker.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

class RemoteSyncService @Inject constructor(
    private val db: AppDatabase,
    private val expenseApiClient: ExpenseApiClient
) {
    suspend fun syncAllFromRemote(): ApiResult<Unit> {
        return withContext(Dispatchers.IO) {
            val accountsResult = expenseApiClient.getAccounts()
            val transactionsResult = expenseApiClient.getTransactions()

            if (accountsResult is ApiResult.Error) {
                return@withContext accountsResult
            }

            if (transactionsResult is ApiResult.Error) {
                return@withContext transactionsResult
            }

            val remoteAccounts = (accountsResult as ApiResult.Success).data
            val remoteTransactions = (transactionsResult as ApiResult.Success).data

            val accountMap = linkedMapOf<String, String>()
            val accountEntities = remoteAccounts.map { remote ->
                val localAccount = Account(
                    id = remote.id,
                    name = remote.name,
                    balance = remote.currentBalance
                )
                accountMap[remote.id] = localAccount.id
                localAccount
            }

            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

            val transactionEntities = remoteTransactions.mapNotNull { remote ->
                val localAccountId = accountMap[remote.accountId] ?: return@mapNotNull null
                val displayDate = runCatching {
                    formatter.format(parser.parse(remote.occurredOn)!!)
                }.getOrElse { remote.occurredOn }

                Transaction(
                    id = remote.id,
                    title = remote.title,
                    amount = remote.amount,
                    transactionType = remote.transactionType,
                    tag = remote.tag,
                    date = displayDate,
                    note = remote.note,
                    accountId = localAccountId,
                    isTransfer = remote.isTransfer,
                    createdAt = System.currentTimeMillis()
                )
            }

            db.withTransaction {
                db.getTransactionDao().deleteAllTransactions()
                db.getAccountDao().deleteAllAccounts()
                accountEntities.forEach { db.getAccountDao().insertAccount(it) }
                transactionEntities.forEach { db.getTransactionDao().insertTransaction(it) }
            }

            ApiResult.Success(Unit)
        }
    }
}