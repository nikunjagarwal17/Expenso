package dev.spikeysanju.expensetracker.data.remote.client

import dev.spikeysanju.expensetracker.data.remote.dto.CreateTransactionRequest
import dev.spikeysanju.expensetracker.data.remote.dto.CreateTransferRequest
import dev.spikeysanju.expensetracker.model.Account
import dev.spikeysanju.expensetracker.model.Transaction
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

class RemoteMapper @Inject constructor() {
    private val displayFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val apiFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun toApiDate(displayDate: String): String {
        return runCatching {
            val parsed = displayFormatter.parse(displayDate)
            if (parsed != null) apiFormatter.format(parsed) else displayDate
        }.getOrElse { displayDate }
    }

    fun toCreateTransactionRequest(
        transaction: Transaction,
        remoteAccountId: String
    ): CreateTransactionRequest {
        return CreateTransactionRequest(
            accountId = remoteAccountId,
            title = transaction.title,
            amount = transaction.amount,
            transactionType = transaction.transactionType,
            tag = transaction.tag,
            occurredOn = toApiDate(transaction.date),
            note = transaction.note,
            isTransfer = transaction.isTransfer
        )
    }

    fun toCreateTransferRequest(
        fromRemoteAccountId: String,
        toRemoteAccountId: String,
        amount: Double,
        taxAmount: Double,
        title: String,
        note: String,
        date: String
    ): CreateTransferRequest {
        return CreateTransferRequest(
            fromAccountId = fromRemoteAccountId,
            toAccountId = toRemoteAccountId,
            amount = amount,
            taxAmount = taxAmount,
            title = title,
            note = note,
            occurredOn = toApiDate(date)
        )
    }

    fun findLocalAccountByName(accounts: List<Account>, name: String): Account? {
        return accounts.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
