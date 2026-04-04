package dev.spikeysanju.expensetracker.data.remote.client

import com.google.gson.Gson
import dev.spikeysanju.expensetracker.data.remote.api.ExpenseApiService
import dev.spikeysanju.expensetracker.data.remote.dto.CreateAccountRequest
import dev.spikeysanju.expensetracker.data.remote.dto.CreateTransactionRequest
import dev.spikeysanju.expensetracker.data.remote.dto.CreateTransferRequest
import dev.spikeysanju.expensetracker.data.remote.dto.ErrorResponse
import dev.spikeysanju.expensetracker.data.remote.dto.RemoteAccount
import dev.spikeysanju.expensetracker.data.remote.dto.RemoteProfile
import dev.spikeysanju.expensetracker.data.remote.dto.RemoteTransaction
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateAccountRequest
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateProfileRequest
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateTransactionRequest
import javax.inject.Inject
import retrofit2.Response

class ExpenseApiClient @Inject constructor(
    private val expenseApiService: ExpenseApiService,
    private val gson: Gson
) {
    suspend fun getProfile(): ApiResult<RemoteProfile?> {
        return runCatching {
            val response = expenseApiService.getProfile()
            if (response.isSuccessful) {
                ApiResult.Success(response.body()?.data)
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun updateProfile(fullName: String?): ApiResult<RemoteProfile?> {
        return runCatching {
            val response = expenseApiService.updateProfile(UpdateProfileRequest(fullName = fullName))
            if (response.isSuccessful) {
                ApiResult.Success(response.body()?.data)
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun getAccounts(): ApiResult<List<RemoteAccount>> {
        return runCatching {
            val response = expenseApiService.getAccounts()
            if (response.isSuccessful) {
                ApiResult.Success(response.body()?.data.orEmpty())
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun createAccount(name: String, openingBalance: Double): ApiResult<RemoteAccount> {
        return runCatching {
            val response = expenseApiService.createAccount(
                CreateAccountRequest(name = name, openingBalance = openingBalance)
            )
            if (response.isSuccessful) {
                response.body()?.data?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error("Invalid server response")
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun updateAccount(accountId: String, name: String): ApiResult<RemoteAccount> {
        return runCatching {
            val response = expenseApiService.updateAccount(
                accountId,
                UpdateAccountRequest(name = name)
            )
            if (response.isSuccessful) {
                response.body()?.data?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error("Invalid server response")
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun deleteAccount(accountId: String): ApiResult<Unit> {
        return runCatching {
            val response = expenseApiService.deleteAccount(accountId)
            if (response.isSuccessful) {
                ApiResult.Success(Unit)
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun getTransactions(): ApiResult<List<RemoteTransaction>> {
        return runCatching {
            val response = expenseApiService.getTransactions(limit = 500, offset = 0)
            if (response.isSuccessful) {
                ApiResult.Success(response.body()?.data.orEmpty())
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun createTransaction(request: CreateTransactionRequest): ApiResult<RemoteTransaction> {
        return runCatching {
            val response = expenseApiService.createTransaction(request)
            if (response.isSuccessful) {
                response.body()?.data?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error("Invalid server response")
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun updateTransaction(
        transactionId: String,
        request: UpdateTransactionRequest
    ): ApiResult<RemoteTransaction> {
        return runCatching {
            val response = expenseApiService.updateTransaction(transactionId, request)
            if (response.isSuccessful) {
                response.body()?.data?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error("Invalid server response")
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun deleteTransaction(transactionId: String): ApiResult<Unit> {
        return runCatching {
            val response = expenseApiService.deleteTransaction(transactionId)
            if (response.isSuccessful) {
                ApiResult.Success(Unit)
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    suspend fun createTransfer(request: CreateTransferRequest): ApiResult<Unit> {
        return runCatching {
            val response = expenseApiService.createTransfer(request)
            if (response.isSuccessful) {
                ApiResult.Success(Unit)
            } else {
                buildError(response)
            }
        }.getOrElse { ApiResult.Error(it.message ?: "Network error") }
    }

    private fun buildError(response: Response<*>): ApiResult.Error {
        val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
        val parsed = parseError(rawBody)
        val details = buildString {
            append("HTTP ")
            append(response.code())
            if (response.message().isNotBlank()) {
                append(" ")
                append(response.message())
            }
            append(" | ")
            append(parsed)
            if (!rawBody.isNullOrBlank()) {
                append(" | body=")
                append(rawBody.take(500))
            }
        }
        return ApiResult.Error(details, response.code())
    }

    private fun parseError(rawBody: String?): String {
        if (rawBody.isNullOrBlank()) {
            return "Request failed"
        }

        return runCatching {
            gson.fromJson(rawBody, ErrorResponse::class.java)?.error
        }.getOrNull().orEmpty().ifBlank { "Request failed" }
    }
}
