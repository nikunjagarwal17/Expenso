package dev.spikeysanju.expensetracker.data.remote.dto

import com.google.gson.annotations.SerializedName

data class RemoteProfile(
    val id: String,
    val email: String,
    @SerializedName("full_name")
    val fullName: String?
)

data class ProfileResponse(
    val data: RemoteProfile?
)

data class UpdateProfileRequest(
    @SerializedName("full_name")
    val fullName: String?
)

data class RemoteAccount(
    val id: String,
    val name: String,
    @SerializedName("opening_balance")
    val openingBalance: Double,
    @SerializedName("current_balance")
    val currentBalance: Double
)

data class AccountsResponse(
    val data: List<RemoteAccount>
)

data class AccountResponse(
    val data: RemoteAccount
)

data class CreateAccountRequest(
    val name: String,
    @SerializedName("opening_balance")
    val openingBalance: Double
)

data class UpdateAccountRequest(
    val name: String
)

data class RemoteTransaction(
    val id: String,
    @SerializedName("account_id")
    val accountId: String,
    val title: String,
    val amount: Double,
    @SerializedName("transaction_type")
    val transactionType: String,
    val tag: String,
    @SerializedName("occurred_on")
    val occurredOn: String,
    val note: String,
    @SerializedName("is_transfer")
    val isTransfer: Boolean
)

data class TransactionsResponse(
    val data: List<RemoteTransaction>
)

data class TransactionResponse(
    val data: RemoteTransaction
)

data class CreateTransactionRequest(
    @SerializedName("account_id")
    val accountId: String,
    val title: String,
    val amount: Double,
    @SerializedName("transaction_type")
    val transactionType: String,
    val tag: String,
    @SerializedName("occurred_on")
    val occurredOn: String,
    val note: String,
    @SerializedName("is_transfer")
    val isTransfer: Boolean = false
)

data class UpdateTransactionRequest(
    @SerializedName("account_id")
    val accountId: String? = null,
    val title: String,
    val amount: Double,
    @SerializedName("transaction_type")
    val transactionType: String,
    val tag: String,
    @SerializedName("occurred_on")
    val occurredOn: String,
    val note: String
)

data class CreateTransferRequest(
    @SerializedName("from_account_id")
    val fromAccountId: String,
    @SerializedName("to_account_id")
    val toAccountId: String,
    val amount: Double,
    @SerializedName("tax_amount")
    val taxAmount: Double,
    val title: String,
    val note: String,
    @SerializedName("occurred_on")
    val occurredOn: String,
    val tag: String = "Transfer"
)
