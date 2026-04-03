package dev.spikeysanju.expensetracker.view.main.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.spikeysanju.expensetracker.data.local.datastore.UIModeImpl
import dev.spikeysanju.expensetracker.data.remote.client.ApiResult
import dev.spikeysanju.expensetracker.data.remote.client.ExpenseApiClient
import dev.spikeysanju.expensetracker.data.remote.client.RemoteMapper
import dev.spikeysanju.expensetracker.data.remote.client.RemoteSyncService
import dev.spikeysanju.expensetracker.data.remote.dto.UpdateTransactionRequest
import dev.spikeysanju.expensetracker.model.Account
import dev.spikeysanju.expensetracker.model.Transaction
import dev.spikeysanju.expensetracker.repo.AuthRepo
import dev.spikeysanju.expensetracker.repo.AccountRepo
import dev.spikeysanju.expensetracker.repo.TransactionRepo
import dev.spikeysanju.expensetracker.services.exportcsv.ExportCsvService
import dev.spikeysanju.expensetracker.utils.AuthSessionManager
import dev.spikeysanju.expensetracker.utils.viewState.DetailState
import dev.spikeysanju.expensetracker.utils.viewState.ExportState
import dev.spikeysanju.expensetracker.utils.viewState.ViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject


import dev.spikeysanju.expensetracker.data.local.datastore.CurrencyPreference
import dev.spikeysanju.expensetracker.data.local.datastore.QuickTilesPreference

@HiltViewModel
class TransactionViewModel @Inject constructor(
    private val transactionRepo: TransactionRepo,
    private val accountRepo: AccountRepo,
    private val exportService: ExportCsvService,
    private val uiModeDataStore: UIModeImpl,
    private val currencyPreference: CurrencyPreference,
    private val quickTilesPreference: QuickTilesPreference,
    private val authRepo: AuthRepo,
    private val expenseApiClient: ExpenseApiClient,
    private val remoteSyncService: RemoteSyncService,
    private val remoteMapper: RemoteMapper
) : ViewModel() {

    // Bulk replace all accounts and transactions (for import)
    fun replaceAllData(newAccounts: List<Account>, newTransactions: List<Transaction>) = viewModelScope.launch {
        // Delete all transactions and accounts
        transactionRepo.deleteAll()
        accountRepo.deleteAll()
        // Insert new accounts with their balances
        newAccounts.forEach { accountRepo.insert(it) }
        // Insert new transactions without updating balances
        newTransactions.forEach { transactionRepo.insert(it) }
        // Reload state
        loadAccounts()
        applyFilters()
    }

    // Insert transaction without updating account balance (for import)
    suspend fun insertTransactionRaw(transaction: Transaction) {
        transactionRepo.insert(transaction)
    }
    // Currency symbol state
    private val _currencySymbol = MutableStateFlow(currencyPreference.getSymbol())
    val currencySymbol: StateFlow<String> = _currencySymbol

    fun setCurrencySymbol(symbol: String) {
        currencyPreference.setSymbol(symbol)
        _currencySymbol.value = symbol
    }

    // Quick tiles state
    private val _quickTiles = MutableStateFlow(quickTilesPreference.getTiles())
    val quickTiles: StateFlow<List<String>> = _quickTiles

    fun setQuickTiles(tiles: List<String>) {
        quickTilesPreference.setTiles(tiles)
        _quickTiles.value = tiles
    }

    fun getQuickTiles(): List<String> = quickTilesPreference.getTiles()

    // state for export csv status
    private val _exportCsvState = MutableStateFlow<ExportState>(ExportState.Empty)
    val exportCsvState: StateFlow<ExportState> = _exportCsvState

    private val _typeFilter = MutableStateFlow("Overall")
    val typeFilter: StateFlow<String> = _typeFilter

    private val _accountFilter = MutableStateFlow<String?>(null) // null = All Accounts
    val accountFilter: StateFlow<String?> = _accountFilter

    private var filterJob: Job? = null
    private val sortDateFormat = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())

    private val _uiState = MutableStateFlow<ViewState>(ViewState.Loading)
    private val _detailState = MutableStateFlow<DetailState>(DetailState.Loading)

    // UI collect from this stateFlow to get the state updates
    val uiState: StateFlow<ViewState> = _uiState
    val detailState: StateFlow<DetailState> = _detailState

    // Account state
    private val _allAccounts = MutableStateFlow<List<Account>>(emptyList())
    val allAccounts: StateFlow<List<Account>> = _allAccounts

    // get ui mode
    val getUIMode = uiModeDataStore.uiMode

    init {
        loadAccounts()
        applyFilters()
    }

    // save ui mode
    fun setDarkMode(isNightMode: Boolean) {
        viewModelScope.launch(IO) {
            uiModeDataStore.saveToDataStore(isNightMode)
        }
    }

    // export all Transactions to xlsx file
    fun exportTransactionsToCsv(xlsxFileUri: Uri) = viewModelScope.launch {
        _exportCsvState.value = ExportState.Loading
        transactionRepo
            .getAllTransactions()
            .flowOn(Dispatchers.IO)
            .flatMapMerge { transactions ->
                val accounts = _allAccounts.value
                exportService.writeToXlsx(xlsxFileUri, transactions, accounts)
            }
            .catch { error ->
                _exportCsvState.value = ExportState.Error(error)
            }.collect { uriString ->
                _exportCsvState.value = ExportState.Success(uriString)
            }
    }

    // insert transaction
    fun insertTransaction(transaction: Transaction, context: Context? = null) = viewModelScope.launch {
        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            val refreshResult = authRepo.refreshSessionIfNeeded(context)
            if (refreshResult is ApiResult.Error) {
                Log.e("TransactionVM", "Refresh failed: ${refreshResult.message}")
                return@launch
            }

            val localAccount = accountRepo.getByIDSync(transaction.accountId)
            val remoteAccountId = localAccount?.let { resolveRemoteAccountIdByName(it.name) }
            if (remoteAccountId != null) {
                val request = remoteMapper.toCreateTransactionRequest(transaction, remoteAccountId)
                when (expenseApiClient.createTransaction(request)) {
                    is ApiResult.Success -> {
                        remoteSyncService.syncAllFromRemote()
                    }

                    is ApiResult.Error -> {
                        Log.e("TransactionVM", "Remote create failed")
                    }
                }
                return@launch
            }
        }

        transactionRepo.insert(transaction)
        if (transaction.accountId.isNotBlank()) {
            updateAccountBalanceForTransaction(transaction, isAdding = true)
        }
    }

    // update transaction
    fun updateTransaction(transaction: Transaction, context: Context? = null) = viewModelScope.launch {
        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            val refreshResult = authRepo.refreshSessionIfNeeded(context)
            if (refreshResult is ApiResult.Error) {
                Log.e("TransactionVM", "Refresh failed: ${refreshResult.message}")
                return@launch
            }

            val remoteId = findRemoteTransactionId(transaction)
            if (remoteId != null) {
                val updateRequest = UpdateTransactionRequest(
                    title = transaction.title,
                    amount = transaction.amount,
                    transactionType = transaction.transactionType,
                    tag = transaction.tag,
                    occurredOn = remoteMapper.toApiDate(transaction.date),
                    note = transaction.note
                )

                when (expenseApiClient.updateTransaction(remoteId, updateRequest)) {
                    is ApiResult.Success -> remoteSyncService.syncAllFromRemote()
                    is ApiResult.Error -> Log.e("TransactionVM", "Remote update failed")
                }
                return@launch
            }
        }

        transactionRepo.update(transaction)
    }

    // delete transaction
    fun deleteTransaction(transaction: Transaction, context: Context? = null) = viewModelScope.launch {
        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            val refreshResult = authRepo.refreshSessionIfNeeded(context)
            if (refreshResult is ApiResult.Error) {
                Log.e("TransactionVM", "Refresh failed: ${refreshResult.message}")
                return@launch
            }

            val remoteId = findRemoteTransactionId(transaction)
            if (remoteId != null) {
                when (expenseApiClient.deleteTransaction(remoteId)) {
                    is ApiResult.Success -> remoteSyncService.syncAllFromRemote()
                    is ApiResult.Error -> Log.e("TransactionVM", "Remote delete failed")
                }
                return@launch
            }
        }

        transactionRepo.delete(transaction)
        if (transaction.accountId.isNotBlank()) {
            updateAccountBalanceForTransaction(transaction, isAdding = false)
        }
    }

    private suspend fun updateAccountBalanceForTransaction(transaction: Transaction, isAdding: Boolean) {
        val account = accountRepo.getByIDSync(transaction.accountId) ?: return
        val multiplier = if (isAdding) 1.0 else -1.0
        when (transaction.transactionType) {
            "Income" -> account.balance += transaction.amount * multiplier
            "Expense" -> account.balance -= transaction.amount * multiplier
        }
        accountRepo.update(account)
    }

    private fun parseDateSafe(dateStr: String): Long {
        return try {
            sortDateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun applyFilters() {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            transactionRepo.getAllTransactions().collect { allTransactions ->
                var filtered = allTransactions

                when (_typeFilter.value) {
                    "Income" -> filtered = filtered.filter { it.transactionType == "Income" }
                    "Expense" -> filtered = filtered.filter { it.transactionType == "Expense" }
                }

                if (_accountFilter.value != null) {
                    filtered = filtered.filter { it.accountId == _accountFilter.value }
                }

                // Sort by date (dd/MM/yyyy) DESC then createdAt DESC for app display
                filtered = filtered.sortedWith(
                    compareByDescending<Transaction> { parseDateSafe(it.date) }
                        .thenByDescending { it.createdAt }
                )

                if (filtered.isEmpty()) {
                    _uiState.value = ViewState.Empty
                } else {
                    _uiState.value = ViewState.Success(filtered)
                }
            }
        }
    }

    // get transaction by id
    fun getByID(id: String) = viewModelScope.launch {
        _detailState.value = DetailState.Loading
        transactionRepo.getByID(id).collect { result: Transaction? ->
            if (result != null) {
                _detailState.value = DetailState.Success(result)
            }
        }
    }

    // delete transaction
    fun deleteByID(id: String) = viewModelScope.launch {
        transactionRepo.deleteByID(id)
    }

    fun setTypeFilter(type: String) {
        if (_typeFilter.value != type) {
            _typeFilter.value = type
            applyFilters()
        }
    }

    fun setAccountFilter(accountId: String?) {
        if (_accountFilter.value != accountId) {
            _accountFilter.value = accountId
            applyFilters()
        }
    }

    fun allIncome() = setTypeFilter("Income")
    fun allExpense() = setTypeFilter("Expense")
    fun overall() = setTypeFilter("Overall")

    // ============ Account management ============

    private fun loadAccounts() {
        viewModelScope.launch {
            accountRepo.getAllAccounts().collect { accounts ->
                _allAccounts.value = accounts
            }
        }
    }

    fun insertAccount(account: Account, context: Context? = null) = viewModelScope.launch {
        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            when (expenseApiClient.createAccount(account.name, account.balance)) {
                is ApiResult.Success -> remoteSyncService.syncAllFromRemote()
                is ApiResult.Error -> Log.e("TransactionVM", "Remote account create failed")
            }
            return@launch
        }

        accountRepo.insert(account)
    }

    fun updateAccount(account: Account, context: Context? = null) = viewModelScope.launch {
        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            val remoteId = resolveRemoteAccountIdByName(account.name)
            if (remoteId != null) {
                when (expenseApiClient.updateAccount(remoteId, account.name)) {
                    is ApiResult.Success -> remoteSyncService.syncAllFromRemote()
                    is ApiResult.Error -> Log.e("TransactionVM", "Remote account update failed")
                }
                return@launch
            }
        }

        accountRepo.update(account)
    }

    fun deleteAccount(account: Account, context: Context? = null) = viewModelScope.launch {
        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            val remoteId = resolveRemoteAccountIdByName(account.name)
            if (remoteId != null) {
                when (expenseApiClient.deleteAccount(remoteId)) {
                    is ApiResult.Success -> remoteSyncService.syncAllFromRemote()
                    is ApiResult.Error -> Log.e("TransactionVM", "Remote account delete failed")
                }
                return@launch
            }
        }

        accountRepo.delete(account)
    }

    fun performTransfer(
        fromAccountId: String,
        toAccountId: String,
        amount: Double,
        taxAmount: Double = 0.0,
        context: Context? = null
    ) = viewModelScope.launch(IO) {
        val fromAccount = accountRepo.getByIDSync(fromAccountId) ?: return@launch
        val toAccount = accountRepo.getByIDSync(toAccountId) ?: return@launch

        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            val fromRemoteId = resolveRemoteAccountIdByName(fromAccount.name)
            val toRemoteId = resolveRemoteAccountIdByName(toAccount.name)
            if (fromRemoteId != null && toRemoteId != null) {
                val request = remoteMapper.toCreateTransferRequest(
                    fromRemoteAccountId = fromRemoteId,
                    toRemoteAccountId = toRemoteId,
                    amount = amount,
                    taxAmount = taxAmount,
                    title = "Transfer ${fromAccount.name} -> ${toAccount.name}",
                    note = "Transfer",
                    date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date())
                )

                when (expenseApiClient.createTransfer(request)) {
                    is ApiResult.Success -> remoteSyncService.syncAllFromRemote()
                    is ApiResult.Error -> Log.e("TransactionVM", "Remote transfer failed")
                }
                return@launch
            }
        }

        val totalDeduction = amount + taxAmount

        // Create expense transaction for the source account
        val expenseTransaction = Transaction(
            title = "Transfer to ${toAccount.name}",
            amount = amount,
            transactionType = "Expense",
            tag = "None",
            date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                .format(java.util.Date()),
            note = "Transfer",
            accountId = fromAccountId,
            isTransfer = true
        )
        transactionRepo.insert(expenseTransaction)

        // Create income transaction for the destination account
        val incomeTransaction = Transaction(
            title = "Transfer from ${fromAccount.name}",
            amount = amount,
            transactionType = "Income",
            tag = "None",
            date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                .format(java.util.Date()),
            note = "Transfer",
            accountId = toAccountId,
            isTransfer = true
        )
        transactionRepo.insert(incomeTransaction)

        // Create tax transaction if applicable
        if (taxAmount > 0) {
            val taxTransaction = Transaction(
                title = "Transfer Tax",
                amount = taxAmount,
                transactionType = "Expense",
                tag = "None",
                date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date()),
                note = "Tax on transfer to ${toAccount.name}",
                accountId = fromAccountId,
                isTransfer = true
            )
            transactionRepo.insert(taxTransaction)
        }

        // Update account balances
        fromAccount.balance -= totalDeduction
        toAccount.balance += amount
        accountRepo.update(fromAccount)
        accountRepo.update(toAccount)
    }

    fun syncFromRemoteIfLoggedIn(context: Context) = viewModelScope.launch(IO) {
        if (!AuthSessionManager.isLoggedIn(context)) {
            return@launch
        }

        val refresh = authRepo.refreshSessionIfNeeded(context)
        if (refresh is ApiResult.Error) {
            Log.e("TransactionVM", "Session refresh failed: ${refresh.message}")
            return@launch
        }

        when (val sync = remoteSyncService.syncAllFromRemote()) {
            is ApiResult.Success -> {
                loadAccounts()
                applyFilters()
            }

            is ApiResult.Error -> Log.e("TransactionVM", "Remote sync failed: ${sync.message}")
        }
    }

    private suspend fun resolveRemoteAccountIdByName(localName: String): String? {
        return when (val result = expenseApiClient.getAccounts()) {
            is ApiResult.Success -> {
                result.data.firstOrNull { it.name.equals(localName, ignoreCase = true) }?.id
            }

            is ApiResult.Error -> null
        }
    }

    private suspend fun findRemoteTransactionId(local: Transaction): String? {
        return when (val result = expenseApiClient.getTransactions()) {
            is ApiResult.Success -> {
                result.data.firstOrNull { it.id == local.id }?.id
                    ?: run {
                val localAccount = accountRepo.getByIDSync(local.accountId)
                val remoteAccountId = localAccount?.let { resolveRemoteAccountIdByName(it.name) }
                result.data.firstOrNull {
                    it.accountId == remoteAccountId &&
                        it.title == local.title &&
                        it.amount == local.amount &&
                        it.transactionType == local.transactionType
                }?.id
                    }
            }

            is ApiResult.Error -> null
        }
    }
}
