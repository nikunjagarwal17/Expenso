package dev.spikeysanju.expensetracker.view.dashboard

import action
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.NavOptions
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.data.local.datastore.UIModeImpl
import dev.spikeysanju.expensetracker.databinding.FragmentDashboardBinding
import dev.spikeysanju.expensetracker.model.Transaction
import dev.spikeysanju.expensetracker.services.exportcsv.CreateCsvContract
import dev.spikeysanju.expensetracker.services.exportcsv.OpenCsvContract
import dev.spikeysanju.expensetracker.utils.AuthSessionManager
import dev.spikeysanju.expensetracker.utils.viewState.ExportState
import dev.spikeysanju.expensetracker.utils.viewState.ViewState
import dev.spikeysanju.expensetracker.view.adapter.TransactionAdapter
import dev.spikeysanju.expensetracker.view.base.BaseFragment
import dev.spikeysanju.expensetracker.view.main.viewmodel.TransactionViewModel
import hide
// ...existing code...
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import show
import snack
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class DashboardFragment :
    BaseFragment<FragmentDashboardBinding, TransactionViewModel>() {
    private lateinit var transactionAdapter: TransactionAdapter
    override val viewModel: TransactionViewModel by activityViewModels()

    @Inject
    lateinit var themeManager: UIModeImpl

    private val csvCreateRequestLauncher =
        registerForActivityResult(CreateCsvContract()) { uri: Uri? ->
            if (uri != null) {
                exportCSV(uri)
            } else {
                binding.root.snack(
                    string = R.string.failed_transaction_export
                )
            }
        }

    private val previewCsvRequestLauncher = registerForActivityResult(OpenCsvContract()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRV()
        initViews()
        observeFilter()
        observeTransaction()
        observeAccounts()
        swipeToDelete()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().invalidateOptionsMenu()
    }

    private fun observeAccounts() {
        lifecycleScope.launchWhenStarted {
            viewModel.allAccounts.collect { accounts ->
                if (accounts.isEmpty()) {
                    binding.btnAddTransaction.hide()
                } else {
                    binding.btnAddTransaction.show()
                }
            }
        }
    }

    private fun observeFilter() = with(binding) {
        lifecycleScope.launchWhenCreated {
            viewModel.typeFilter.collect { type ->
                when (type) {
                    "Overall" -> {
                        totalBalanceView.totalBalanceTitle.text =
                            getString(R.string.text_total_balance)
                        totalIncomeExpenseView.show()
                        incomeCardView.totalTitle.text = getString(R.string.text_total_income)
                        expenseCardView.totalTitle.text = getString(R.string.text_total_expense)
                        expenseCardView.totalIcon.setImageResource(R.drawable.ic_expense)
                    }
                    "Income" -> {
                        totalBalanceView.totalBalanceTitle.text =
                            getString(R.string.text_total_income)
                        totalIncomeExpenseView.hide()
                    }
                    "Expense" -> {
                        totalBalanceView.totalBalanceTitle.text =
                            getString(R.string.text_total_expense)
                        totalIncomeExpenseView.hide()
                    }
                }
            }
        }
    }

    private fun setupRV() = with(binding) {
        transactionAdapter = TransactionAdapter()
        transactionRv.apply {
            adapter = transactionAdapter
            layoutManager = LinearLayoutManager(activity)
        }
        // Observe currency symbol and update adapter
        lifecycleScope.launchWhenStarted {
            viewModel.currencySymbol.collect { symbol ->
                transactionAdapter.setCurrencySymbol(symbol)
            }
        }
    }

    private fun swipeToDelete() {
        // init item touch callback for swipe action
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // get item position & delete notes
                val position = viewHolder.adapterPosition
                val transaction = transactionAdapter.differ.currentList[position]
                val transactionItem = Transaction(
                    title = transaction.title,
                    amount = transaction.amount,
                    transactionType = transaction.transactionType,
                    tag = transaction.tag,
                    date = transaction.date,
                    note = transaction.note,
                    createdAt = transaction.createdAt,
                    id = transaction.id,
                    accountId = transaction.accountId,
                    isTransfer = transaction.isTransfer
                )
                viewModel.deleteTransaction(transactionItem)
                Snackbar.make(
                    binding.root,
                    getString(R.string.success_transaction_delete),
                    Snackbar.LENGTH_LONG
                )
                    .apply {
                        setAction(getString(R.string.text_undo)) {
                            viewModel.insertTransaction(
                                transactionItem
                            )
                        }
                        show()
                    }
            }
        }

        // attach swipe callback to rv
        ItemTouchHelper(itemTouchHelperCallback).apply {
            attachToRecyclerView(binding.transactionRv)
        }
    }

    private fun onTotalTransactionLoaded(transaction: List<Transaction>) = with(binding) {
        val (totalIncome, totalExpense) = transaction.partition { it.transactionType == "Income" }
        val income = totalIncome.sumOf { it.amount }
        val expense = totalExpense.sumOf { it.amount }
        lifecycleScope.launchWhenStarted {
            viewModel.currencySymbol.collect { symbol ->
                incomeCardView.total.text = "+ $symbol".plus(income)
                expenseCardView.total.text = "- $symbol".plus(expense)
                totalBalanceView.totalBalance.text = "$symbol".plus((income - expense))
            }
        }
    }

    private fun observeTransaction() = lifecycleScope.launchWhenStarted {
        viewModel.uiState.collect { uiState ->
            when (uiState) {
                is ViewState.Loading -> {
                }
                is ViewState.Success -> {
                    showAllViews()
                    onTransactionLoaded(uiState.transaction)
                    onTotalTransactionLoaded(uiState.transaction)
                }
                is ViewState.Error -> {
                    binding.root.snack(
                        string = R.string.text_error
                    )
                }
                is ViewState.Empty -> {
                    hideAllViews()
                }
            }
        }
    }

    private fun showAllViews() = with(binding) {
        dashboardGroup.show()
        emptyStateLayout.hide()
        transactionRv.show()
    }

    private fun hideAllViews() = with(binding) {
        dashboardGroup.hide()
        emptyStateLayout.show()
        val hasAccounts = viewModel.allAccounts.value.isNotEmpty()
        if (hasAccounts) {
            root.findViewById<TextView>(R.id.empty_state_title)?.text =
                getString(R.string.text_transaction_empty_title)
            root.findViewById<TextView>(R.id.empty_state_description)?.text =
                getString(R.string.text_transaction_empty_desc)
            root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddAccountFirst)?.apply {
                text = getString(R.string.text_manage_accounts)
                setOnClickListener {
                    findNavController().navigate(R.id.action_dashboardFragment_to_accountFragment)
                }
            }
        } else {
            root.findViewById<TextView>(R.id.empty_state_title)?.text =
                getString(R.string.text_account_empty_title)
            root.findViewById<TextView>(R.id.empty_state_description)?.text =
                getString(R.string.text_account_empty_desc)
            root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddAccountFirst)?.apply {
                text = getString(R.string.text_add_account_first)
                setOnClickListener {
                    findNavController().navigate(R.id.action_dashboardFragment_to_accountFragment)
                }
            }
        }
    }

    private fun onTransactionLoaded(list: List<Transaction>) =
        transactionAdapter.differ.submitList(list)

    private fun initViews() = with(binding) {
        btnAddTransaction.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_addTransactionFragment)
        }

        btnManageAccounts.setOnClickListener {
            findNavController().navigate(R.id.action_dashboardFragment_to_accountFragment)
        }

        mainDashboardScrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, sX, sY, oX, oY ->
                if (abs(sY - oY) > 10) {
                    val hasAccounts = viewModel.allAccounts.value.isNotEmpty()
                    when {
                        sY > oY -> btnAddTransaction.hide()
                        oY > sY -> if (hasAccounts) btnAddTransaction.show()
                    }
                }
            }
        )

        transactionAdapter.setOnItemClickListener {
            val bundle = Bundle().apply {
                putSerializable("transaction", it)
            }
            findNavController().navigate(
                R.id.action_dashboardFragment_to_transactionDetailsFragment,
                bundle
            )
        }
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentDashboardBinding.inflate(inflater, container, false)

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_ui, menu)
        val compactDropDownWidth = resources.getDimensionPixelSize(R.dimen.dimen_150)

        // Type filter spinner
        val typeItem = menu.findItem(R.id.spinner)
        val typeSpinner = typeItem.actionView as Spinner
        typeSpinner.dropDownWidth = compactDropDownWidth
        typeSpinner.dropDownHorizontalOffset = 0

        val typeItems = listOf("Overall", "All Income", "All Expense")
        val typeAdapter = ArrayAdapter(
            applicationContext(),
            R.layout.item_filter_spinner_compact,
            typeItems
        )
        typeAdapter.setDropDownViewResource(R.layout.item_filter_dropdown)
        typeSpinner.adapter = typeAdapter

        val currentType = viewModel.typeFilter.value
        typeSpinner.setSelection(
            when (currentType) {
                "Income" -> 1
                "Expense" -> 2
                else -> 0
            }
        )

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val type = when (position) {
                    1 -> "Income"
                    2 -> "Expense"
                    else -> "Overall"
                }
                viewModel.setTypeFilter(type)
                (view as? TextView)?.setTextColor(resources.getColor(R.color.black))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                viewModel.setTypeFilter("Overall")
            }
        }

        // Account filter spinner
        val accountItem = menu.findItem(R.id.spinner_account)
        val accountSpinner = accountItem.actionView as Spinner
        accountSpinner.dropDownWidth = compactDropDownWidth
        accountSpinner.dropDownHorizontalOffset = 0

        val accounts = viewModel.allAccounts.value
        val accountItems = listOf("All Accounts") + accounts.map { it.name }
        val accountAdapter = ArrayAdapter(
            applicationContext(),
            R.layout.item_filter_spinner_compact,
            accountItems
        )
        accountAdapter.setDropDownViewResource(R.layout.item_filter_dropdown)
        accountSpinner.adapter = accountAdapter

        val currentAccountId = viewModel.accountFilter.value
        val accountIndex = if (currentAccountId == -1) 0 else {
            val idx = accounts.indexOfFirst { it.id == currentAccountId }
            if (idx >= 0) idx + 1 else 0
        }
        accountSpinner.setSelection(accountIndex)

        accountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val accountId = if (position == 0) -1 else accounts[position - 1].id
                viewModel.setAccountFilter(accountId)
                (view as? TextView)?.setTextColor(resources.getColor(R.color.black))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                viewModel.setAccountFilter(-1)
            }
        }

        // Set the item state
        lifecycleScope.launchWhenStarted {
            val isChecked = viewModel.getUIMode.first()
            val uiMode = menu.findItem(R.id.action_night_mode)
            uiMode.isChecked = isChecked
            setUIMode(uiMode, isChecked)
        }

        updateAuthMenuItem(menu)
    }

    private fun updateAuthMenuItem(menu: Menu) {
        val authItem = menu.findItem(R.id.action_login_signup)
        authItem.title = if (AuthSessionManager.isLoggedIn(requireContext())) {
            getString(R.string.text_logout)
        } else {
            getString(R.string.text_login_signup)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here.
        return when (item.itemId) {
            R.id.action_night_mode -> {
                item.isChecked = !item.isChecked
                setUIMode(item, item.isChecked)
                true
            }
            R.id.action_about -> {
                findNavController().navigate(R.id.action_dashboardFragment_to_aboutFragment)
                true
            }
            R.id.action_login_signup -> {
                if (AuthSessionManager.isLoggedIn(requireContext())) {
                    AuthSessionManager.setLoggedIn(requireContext(), false)
                    binding.root.snack(string = R.string.text_logged_out)
                    findNavController().navigate(
                        R.id.authWelcomeFragment,
                        null,
                        NavOptions.Builder()
                            .setPopUpTo(R.id.dashboardFragment, true)
                            .build()
                    )
                } else {
                    findNavController().navigate(R.id.action_dashboardFragment_to_authWelcomeFragment)
                }
                true
            }
            R.id.action_export -> {
                val csvFileName = "expenso_${System.currentTimeMillis()}"
                csvCreateRequestLauncher.launch(csvFileName)
                return true
            }
            R.id.action_settings -> {
                findNavController().navigate(R.id.action_dashboardFragment_to_settingsFragment)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportCSV(csvFileUri: Uri) {
        viewModel.exportTransactionsToCsv(csvFileUri)
        lifecycleScope.launchWhenCreated {
            viewModel.exportCsvState.collect { state ->
                when (state) {
                    ExportState.Empty -> {
                        /*do nothing*/
                    }
                    is ExportState.Error -> {
                        binding.root.snack(
                            string = R.string.failed_transaction_export
                        )
                    }
                    ExportState.Loading -> {
                        /*do nothing*/
                    }
                    is ExportState.Success -> {
                        binding.root.snack(string = R.string.success_transaction_export) {
                            action(text = R.string.text_open) {
                                previewCsvRequestLauncher.launch(state.fileUri)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isStoragePermissionGranted(): Boolean =
        isStorageReadPermissionGranted() && isStorageWritePermissionGranted()

    private fun isStorageWritePermissionGranted(): Boolean = ContextCompat
        .checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun isStorageReadPermissionGranted(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun setUIMode(item: MenuItem, isChecked: Boolean) {
        if (isChecked) {
            viewModel.setDarkMode(true)
            item.setIcon(R.drawable.ic_night)
            item.title = "Light Mode"
        } else {
            viewModel.setDarkMode(false)
            item.setIcon(R.drawable.ic_day)
            item.title = "Dark Mode"
        }
    }
}
