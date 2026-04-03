package dev.spikeysanju.expensetracker.data.remote.api

import dev.spikeysanju.expensetracker.data.remote.dto.AccountResponse
import dev.spikeysanju.expensetracker.data.remote.dto.AccountsResponse
import dev.spikeysanju.expensetracker.data.remote.dto.CreateAccountRequest
import dev.spikeysanju.expensetracker.data.remote.dto.CreateTransactionRequest
import dev.spikeysanju.expensetracker.data.remote.dto.CreateTransferRequest
import dev.spikeysanju.expensetracker.data.remote.dto.MessageResponse
import dev.spikeysanju.expensetracker.data.remote.dto.ProfileResponse
import dev.spikeysanju.expensetracker.data.remote.dto.TransactionResponse
import dev.spikeysanju.expensetracker.data.remote.dto.TransactionsResponse
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateAccountRequest
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateProfileRequest
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateTransactionRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ExpenseApiService {
    @GET("expense-api/profile")
    suspend fun getProfile(): Response<ProfileResponse>

    @PATCH("expense-api/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<ProfileResponse>

    @GET("expense-api/accounts")
    suspend fun getAccounts(): Response<AccountsResponse>

    @POST("expense-api/accounts")
    suspend fun createAccount(@Body request: CreateAccountRequest): Response<AccountResponse>

    @PATCH("expense-api/accounts/{id}")
    suspend fun updateAccount(
        @Path("id") accountId: String,
        @Body request: UpdateAccountRequest
    ): Response<AccountResponse>

    @DELETE("expense-api/accounts/{id}")
    suspend fun deleteAccount(@Path("id") accountId: String): Response<MessageResponse>

    @GET("expense-api/transactions")
    suspend fun getTransactions(
        @Query("account_id") accountId: String? = null,
        @Query("transaction_type") transactionType: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<TransactionsResponse>

    @POST("expense-api/transactions")
    suspend fun createTransaction(@Body request: CreateTransactionRequest): Response<TransactionResponse>

    @PATCH("expense-api/transactions/{id}")
    suspend fun updateTransaction(
        @Path("id") transactionId: String,
        @Body request: UpdateTransactionRequest
    ): Response<TransactionResponse>

    @DELETE("expense-api/transactions/{id}")
    suspend fun deleteTransaction(@Path("id") transactionId: String): Response<MessageResponse>

    @POST("expense-api/transfers")
    suspend fun createTransfer(@Body request: CreateTransferRequest): Response<Map<String, Any>>
}
