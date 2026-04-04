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
import dev.spikeysanju.expensetracker.data.remote.client.RemoteSyncService
import dev.spikeysanju.expensetracker.model.Account
import dev.spikeysanju.expensetracker.model.Transaction
import dev.spikeysanju.expensetracker.repo.AuthRepo
import dev.spikeysanju.expensetracker.repo.AccountRepo
import dev.spikeysanju.expensetracker.repo.TransactionRepo
import dev.spikeysanju.expensetracker.services.exportcsv.ExportCsvService
import dev.spikeysanju.expensetracker.utils.AuthSessionManager
import dev.spikeysanju.expensetracker.utils.SyncDeletionStore
import dev.spikeysanju.expensetracker.utils.SyncLogFile
import dev.spikeysanju.expensetracker.utils.viewState.DetailState
import dev.spikeysanju.expensetracker.utils.viewState.ExportState
import dev.spikeysanju.expensetracker.utils.viewState.ViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
    private val remoteSyncService: RemoteSyncService
) : ViewModel() {

    data class SyncConflictSnapshot(
        val localAccounts: Int,
        val localTransactions: Int,
        val remoteAccounts: Int,
        val remoteTransactions: Int
    ) {
        val hasLocalData: Boolean
            get() = localAccounts > 0 || localTransactions > 0
        val hasRemoteData: Boolean
            get() = remoteAccounts > 0 || remoteTransactions > 0
    }

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
    private var deleteBurstSyncJob: Job? = null
    private val syncMutex = Mutex()
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
    fun insertTransaction(transaction: Transaction, context: Context? = null) = viewModelScope.launch(IO) {
        transactionRepo.insert(transaction)
        if (transaction.accountId.isNotBlank()) {
            updateAccountBalanceForTransaction(transaction, isAdding = true)
        }
        triggerBackgroundSync(context)
    }

    // update transaction
    fun updateTransaction(transaction: Transaction, context: Context? = null) = viewModelScope.launch(IO) {
        transactionRepo.update(transaction)
        triggerBackgroundSync(context)
    }

    // delete transaction
    fun deleteTransaction(transaction: Transaction, context: Context? = null) = viewModelScope.launch(IO) {
        transactionRepo.delete(transaction)
        if (transaction.accountId.isNotBlank()) {
            updateAccountBalanceForTransaction(transaction, isAdding = false)
        }

        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            SyncDeletionStore.markTransactionDeleted(context, transaction.id)
            scheduleDeleteBurstSync(context)
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

    fun insertAccount(account: Account, context: Context? = null) = viewModelScope.launch(IO) {
        accountRepo.insert(account)
        triggerBackgroundSync(context)
    }

    fun updateAccount(account: Account, context: Context? = null) = viewModelScope.launch(IO) {
        accountRepo.update(account)
        triggerBackgroundSync(context)
    }

    fun deleteAccount(account: Account, context: Context? = null) = viewModelScope.launch(IO) {
        accountRepo.delete(account)

        if (context != null && AuthSessionManager.isLoggedIn(context)) {
            SyncDeletionStore.markAccountDeleted(context, account.id)
            scheduleDeleteBurstSync(context)
        }
    }

    private fun triggerBackgroundSync(context: Context?) {
        if (context == null || !AuthSessionManager.isLoggedIn(context)) {
            return
        }
        syncFromRemoteIfLoggedIn(context)
    }

    private fun scheduleDeleteBurstSync(context: Context) {
        deleteBurstSyncJob?.cancel()
        deleteBurstSyncJob = viewModelScope.launch(IO) {
            delay(1200)
            syncFromRemoteIfLoggedIn(context)
        }
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

        triggerBackgroundSync(context)
    }

    fun syncFromRemoteIfLoggedIn(context: Context) = viewModelScope.launch(IO) {
        if (!syncMutex.tryLock()) {
            SyncLogFile.append(context, "sync.skip_already_running")
            return@launch
        }

        try {
        SyncLogFile.append(context, "sync.start")
        if (!AuthSessionManager.isLoggedIn(context)) {
            SyncLogFile.append(context, "sync.skip_not_logged_in")
            return@launch
        }

        val refresh = authRepo.refreshSessionIfNeeded(context)
        if (refresh is ApiResult.Error) {
            Log.e("TransactionVM", "Session refresh failed: ${refresh.message}")
            SyncLogFile.append(context, "sync.refresh_failed: ${refresh.message}")
            return@launch
        }
        SyncLogFile.append(context, "sync.refresh_ok")

        when (val sync = remoteSyncService.syncAllFromRemote(pushLocalChanges = true)) {
            is ApiResult.Success -> {
                loadAccounts()
                applyFilters()
                SyncLogFile.append(context, "sync.success")
            }

            is ApiResult.Error -> {
                Log.e("TransactionVM", "Remote sync failed: ${sync.message}")
                SyncLogFile.append(context, "sync.failed: ${sync.message}")
            }
        }
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun getSyncConflictSnapshot(context: Context): ApiResult<SyncConflictSnapshot> {
        return withContext(IO) {
            if (!AuthSessionManager.isLoggedIn(context)) {
                return@withContext ApiResult.Error("Not logged in")
            }

            val refresh = authRepo.refreshSessionIfNeeded(context)
            if (refresh is ApiResult.Error) {
                return@withContext refresh
            }

            val localAccounts = accountRepo.getAllAccounts().first().size
            val localTransactions = transactionRepo.getAllTransactions().first().size

            val remoteAccountsResult = expenseApiClient.getAccounts()
            if (remoteAccountsResult is ApiResult.Error) {
                return@withContext remoteAccountsResult
            }

            val remoteTransactionsResult = expenseApiClient.getTransactions()
            if (remoteTransactionsResult is ApiResult.Error) {
                return@withContext remoteTransactionsResult
            }

            ApiResult.Success(
                SyncConflictSnapshot(
                    localAccounts = localAccounts,
                    localTransactions = localTransactions,
                    remoteAccounts = (remoteAccountsResult as ApiResult.Success).data.size,
                    remoteTransactions = (remoteTransactionsResult as ApiResult.Success).data.size
                )
            )
        }
    }

    suspend fun resolveInitialSyncChoice(context: Context, keepLocalData: Boolean): ApiResult<Unit> {
        return withContext(IO) {
            if (!AuthSessionManager.isLoggedIn(context)) {
                return@withContext ApiResult.Error("Not logged in")
            }

            val refresh = authRepo.refreshSessionIfNeeded(context)
            if (refresh is ApiResult.Error) {
                return@withContext refresh
            }

            val result = if (keepLocalData) {
                SyncLogFile.append(context, "sync.choice.keep_local")
                remoteSyncService.replaceRemoteWithLocal()
            } else {
                SyncLogFile.append(context, "sync.choice.keep_server")
                remoteSyncService.syncAllFromRemote(pushLocalChanges = false)
            }

            if (result is ApiResult.Success) {
                loadAccounts()
                applyFilters()
            }
            result
        }
    }

}
