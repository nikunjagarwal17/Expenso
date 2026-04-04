package dev.spikeysanju.expensetracker.data.remote.client

import android.content.Context
import androidx.room.withTransaction
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spikeysanju.expensetracker.data.local.AppDatabase
import dev.spikeysanju.expensetracker.data.remote.dto.CreateTransactionRequest
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateTransactionRequest
import dev.spikeysanju.expensetracker.model.Account
import dev.spikeysanju.expensetracker.model.Transaction
import dev.spikeysanju.expensetracker.utils.SyncDeletionStore
import dev.spikeysanju.expensetracker.utils.SyncLogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

class RemoteSyncService @Inject constructor(
    private val db: AppDatabase,
    private val expenseApiClient: ExpenseApiClient,
    @ApplicationContext private val appContext: Context
) {
    private val remoteDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val localDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    suspend fun syncAllFromRemote(pushLocalChanges: Boolean = true): ApiResult<Unit> {
        return withContext(Dispatchers.IO) {
            if (pushLocalChanges) {
                processPendingDeletions()
            }

            val localAccounts = db.getAccountDao().getAllAccounts().first()
            val localTransactions = db.getTransactionDao().getAllTransactions().first()

            val initialAccountsResult = expenseApiClient.getAccounts()
            val initialTransactionsResult = expenseApiClient.getTransactions()

            if (initialAccountsResult is ApiResult.Error) {
                return@withContext initialAccountsResult
            }

            if (initialTransactionsResult is ApiResult.Error) {
                return@withContext initialTransactionsResult
            }

            val remoteAccounts = (initialAccountsResult as ApiResult.Success).data.toMutableList()
            val remoteTransactions = (initialTransactionsResult as ApiResult.Success).data.toMutableList()

            val localToRemoteAccountId = mutableMapOf<String, String>()
            remoteAccounts.forEach { localToRemoteAccountId[it.id] = it.id }

            if (pushLocalChanges) {
                // Upload local accounts that do not exist remotely.
                localAccounts.forEach { local ->
                    val byId = remoteAccounts.firstOrNull { it.id == local.id }
                    if (byId != null) {
                        localToRemoteAccountId[local.id] = byId.id
                        return@forEach
                    }

                    val byName = remoteAccounts.firstOrNull { it.name.equals(local.name, ignoreCase = true) }
                    if (byName != null) {
                        localToRemoteAccountId[local.id] = byName.id
                        return@forEach
                    }

                    when (val created = expenseApiClient.createAccount(local.name, local.balance)) {
                        is ApiResult.Success -> {
                            remoteAccounts.add(created.data)
                            localToRemoteAccountId[local.id] = created.data.id
                        }

                        is ApiResult.Error -> {
                            Log.e("RemoteSyncService", "Failed to upload account '${local.name}': ${created.message}")
                            SyncLogFile.append(appContext, "sync.upload_account_failed name=${local.name} error=${created.message}")
                        }
                    }
                }

                // Push local account name edits.
                localAccounts.forEach { local ->
                    val remoteId = localToRemoteAccountId[local.id] ?: return@forEach
                    val remote = remoteAccounts.firstOrNull { it.id == remoteId } ?: return@forEach
                    if (remote.name != local.name) {
                        when (val updated = expenseApiClient.updateAccount(remoteId, local.name)) {
                            is ApiResult.Success -> {
                                val idx = remoteAccounts.indexOfFirst { it.id == remoteId }
                                if (idx >= 0) {
                                    remoteAccounts[idx] = updated.data
                                }
                            }

                            is ApiResult.Error -> {
                                Log.e("RemoteSyncService", "Failed to update account '${local.name}': ${updated.message}")
                                SyncLogFile.append(appContext, "sync.update_account_failed id=${remoteId} error=${updated.message}")
                            }
                        }
                    }
                }

                val remoteAccountIds = remoteAccounts.map { it.id }.toSet()

                // Upload local transactions that do not exist remotely, and push local edits for existing IDs.
                localTransactions.forEach { local ->
                    val remoteAccountId = localToRemoteAccountId[local.accountId] ?: local.accountId
                    if (!remoteAccountIds.contains(remoteAccountId)) {
                        return@forEach
                    }

                    val localApiDate = toApiDate(local.date)

                    val existingRemote = remoteTransactions.firstOrNull { it.id == local.id }
                    if (existingRemote != null) {
                        val hasChanges = existingRemote.accountId != remoteAccountId ||
                            existingRemote.title != local.title ||
                            existingRemote.amount != local.amount ||
                            existingRemote.transactionType != local.transactionType ||
                            existingRemote.tag != local.tag ||
                            existingRemote.occurredOn != localApiDate ||
                            existingRemote.note != local.note

                        if (hasChanges) {
                            val request = UpdateTransactionRequest(
                                accountId = remoteAccountId,
                                title = local.title,
                                amount = local.amount,
                                transactionType = local.transactionType,
                                tag = local.tag,
                                occurredOn = localApiDate,
                                note = local.note
                            )

                            when (val updated = expenseApiClient.updateTransaction(existingRemote.id, request)) {
                                is ApiResult.Success -> {
                                    val idx = remoteTransactions.indexOfFirst { it.id == existingRemote.id }
                                    if (idx >= 0) {
                                        remoteTransactions[idx] = updated.data
                                    }
                                }

                                is ApiResult.Error -> {
                                    Log.e("RemoteSyncService", "Failed to update transaction '${local.title}': ${updated.message}")
                                    SyncLogFile.append(appContext, "sync.update_tx_failed id=${existingRemote.id} error=${updated.message}")
                                }
                            }
                        }
                        return@forEach
                    }

                    val hasEquivalentRemote = remoteTransactions.any {
                        it.accountId == remoteAccountId &&
                            it.title == local.title &&
                            it.amount == local.amount &&
                            it.transactionType == local.transactionType &&
                            it.occurredOn == localApiDate &&
                            it.note == local.note
                    }

                    if (hasEquivalentRemote) {
                        return@forEach
                    }

                    val request = CreateTransactionRequest(
                        accountId = remoteAccountId,
                        title = local.title,
                        amount = local.amount,
                        transactionType = local.transactionType,
                        tag = local.tag,
                        occurredOn = localApiDate,
                        note = local.note,
                        isTransfer = local.isTransfer
                    )

                    when (val created = expenseApiClient.createTransaction(request)) {
                        is ApiResult.Success -> {
                            remoteTransactions.add(created.data)
                        }

                        is ApiResult.Error -> {
                            Log.e("RemoteSyncService", "Failed to upload transaction '${local.title}': ${created.message}")
                            SyncLogFile.append(appContext, "sync.upload_tx_failed title=${local.title} error=${created.message}")
                        }
                    }
                }
            }

            replaceLocalWithRemote()
        }
    }

    suspend fun replaceRemoteWithLocal(): ApiResult<Unit> {
        return withContext(Dispatchers.IO) {
            when (val wipe = wipeRemoteData()) {
                is ApiResult.Error -> wipe
                is ApiResult.Success -> {
                    SyncDeletionStore.clearAll(appContext)
                    syncAllFromRemote(pushLocalChanges = true)
                }
            }
        }
    }

    private suspend fun processPendingDeletions() {
        val deletedTransactionIds = SyncDeletionStore.getDeletedTransactionIds(appContext)
        deletedTransactionIds.forEach { txId ->
            when (val result = expenseApiClient.deleteTransaction(txId)) {
                is ApiResult.Success -> {
                    SyncDeletionStore.clearDeletedTransactionId(appContext, txId)
                    SyncLogFile.append(appContext, "sync.delete_tx_remote_ok id=${txId}")
                }

                is ApiResult.Error -> {
                    SyncLogFile.append(appContext, "sync.delete_tx_remote_failed id=${txId} error=${result.message}")
                }
            }
        }

        val deletedAccountIds = SyncDeletionStore.getDeletedAccountIds(appContext)
        deletedAccountIds.forEach { accountId ->
            when (val result = expenseApiClient.deleteAccount(accountId)) {
                is ApiResult.Success -> {
                    SyncDeletionStore.clearDeletedAccountId(appContext, accountId)
                    SyncLogFile.append(appContext, "sync.delete_account_remote_ok id=${accountId}")
                }

                is ApiResult.Error -> {
                    SyncLogFile.append(appContext, "sync.delete_account_remote_failed id=${accountId} error=${result.message}")
                }
            }
        }
    }

    private suspend fun wipeRemoteData(): ApiResult<Unit> {
        val remoteTransactionsResult = expenseApiClient.getTransactions()
        if (remoteTransactionsResult is ApiResult.Error) {
            return remoteTransactionsResult
        }

        val remoteTransactions = (remoteTransactionsResult as ApiResult.Success).data
        remoteTransactions.forEach { tx ->
            when (val result = expenseApiClient.deleteTransaction(tx.id)) {
                is ApiResult.Success -> Unit
                is ApiResult.Error -> return result
            }
        }

        val remoteAccountsResult = expenseApiClient.getAccounts()
        if (remoteAccountsResult is ApiResult.Error) {
            return remoteAccountsResult
        }

        val remoteAccounts = (remoteAccountsResult as ApiResult.Success).data
        remoteAccounts.forEach { account ->
            when (val result = expenseApiClient.deleteAccount(account.id)) {
                is ApiResult.Success -> Unit
                is ApiResult.Error -> return result
            }
        }

        return ApiResult.Success(Unit)
    }

    private suspend fun replaceLocalWithRemote(): ApiResult<Unit> {
        val finalAccountsResult = expenseApiClient.getAccounts()
        val finalTransactionsResult = expenseApiClient.getTransactions()

        if (finalAccountsResult is ApiResult.Error) {
            return finalAccountsResult
        }

        if (finalTransactionsResult is ApiResult.Error) {
            return finalTransactionsResult
        }

        val latestRemoteAccounts = (finalAccountsResult as ApiResult.Success).data
        val latestRemoteTransactions = (finalTransactionsResult as ApiResult.Success).data
        val deletedAccountIds = SyncDeletionStore.getDeletedAccountIds(appContext)
        val deletedTransactionIds = SyncDeletionStore.getDeletedTransactionIds(appContext)

        val accountMap = linkedMapOf<String, String>()
        val accountEntities = latestRemoteAccounts
            .filterNot { deletedAccountIds.contains(it.id) }
            .map { remote ->
            val localAccount = Account(
                id = remote.id,
                name = remote.name,
                balance = remote.currentBalance
            )
            accountMap[remote.id] = localAccount.id
            localAccount
            }

        val transactionEntities = latestRemoteTransactions
            .filterNot { deletedTransactionIds.contains(it.id) }
            .mapNotNull { remote ->
            val localAccountId = accountMap[remote.accountId] ?: return@mapNotNull null
            val displayDate = toLocalDate(remote.occurredOn)

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

        return ApiResult.Success(Unit)
    }

    private fun toApiDate(localDate: String): String {
        return runCatching {
            val parsed = localDateFormat.parse(localDate)
            if (parsed != null) remoteDateFormat.format(parsed) else localDate
        }.getOrElse { localDate }
    }

    private fun toLocalDate(apiDate: String): String {
        return runCatching {
            val parsed = remoteDateFormat.parse(apiDate)
            if (parsed != null) localDateFormat.format(parsed) else apiDate
        }.getOrElse { apiDate }
    }
}