package dev.spikeysanju.expensetracker.view.add

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentAddTransactionBinding
import dev.spikeysanju.expensetracker.model.Transaction
import dev.spikeysanju.expensetracker.utils.Constants
import dev.spikeysanju.expensetracker.view.base.BaseFragment
import dev.spikeysanju.expensetracker.view.main.viewmodel.TransactionViewModel
import kotlinx.coroutines.flow.collect
import parseDouble
import snack
import transformIntoDatePicker
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class AddTransactionFragment :
    BaseFragment<FragmentAddTransactionBinding, TransactionViewModel>() {
    override val viewModel: TransactionViewModel by activityViewModels()

    private var selectedAccountId: String = ""
    private var selectedAccountName: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        observeAccounts()
    }

    private fun observeAccounts() {
        lifecycleScope.launchWhenStarted {
            viewModel.allAccounts.collect { accounts ->
                if (accounts.isNotEmpty()) {
                    val accountNames = accounts.map { it.name }
                    val accountAdapter = ArrayAdapter(
                        requireContext(),
                        R.layout.item_autocomplete_layout,
                        accountNames
                    )
                    binding.addTransactionLayout.etAccount.setAdapter(accountAdapter)
                    binding.addTransactionLayout.etAccount.setOnItemClickListener { _, _, position, _ ->
                        selectedAccountId = accounts[position].id
                        selectedAccountName = accounts[position].name
                    }
                    // Auto-select the first account
                    if (accounts.isNotEmpty()) {
                        binding.addTransactionLayout.etAccount.setText(accounts[0].name, false)
                        selectedAccountId = accounts[0].id
                        selectedAccountName = accounts[0].name
                    }
                }
            }
        }
    }

    private fun initViews() {
        val transactionTypeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_autocomplete_layout,
            Constants.transactionType
        )
        with(binding) {
            // Set list to TextInputEditText adapter
            addTransactionLayout.etTransactionType.setAdapter(transactionTypeAdapter)
            // Set default transaction type to Expense
            addTransactionLayout.etTransactionType.setText("Expense", false)
            
            // Load and setup dynamic quick title chips
            val quickTiles = viewModel.getQuickTiles()
            setupDynamicQuickTiles(addTransactionLayout.quickTitleChipGroup, quickTiles)
            
            // Transform TextInputEditText to DatePicker using Ext function
            addTransactionLayout.etWhen.transformIntoDatePicker(
                requireContext(),
                "dd/MM/yyyy",
                Date()
            )
            // Quick date selection — default to today
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            addTransactionLayout.etWhen.setText(dateFormat.format(Date()))
            addTransactionLayout.chipToday.setOnClickListener {
                addTransactionLayout.etWhen.setText(dateFormat.format(Date()))
            }
            addTransactionLayout.chipYesterday.setOnClickListener {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -1)
                addTransactionLayout.etWhen.setText(dateFormat.format(cal.time))
            }
            // Observe currency symbol and update amount prefix
            lifecycleScope.launchWhenStarted {
                viewModel.currencySymbol.collect { symbol ->
                    addTransactionLayout.etAmountView.prefixText = symbol
                }
            }
            btnSaveTransaction.setOnClickListener {
                binding.addTransactionLayout.apply {
                    val (title, amount, transactionType, tag, date, note) = getTransactionContent()
                    // validate if transaction content is empty or not
                    when {
                        title.isEmpty() -> {
                            this.etTitle.error = "Title must not be empty"
                        }
                        amount.isNaN() -> {
                            this.etAmount.error = "Amount must not be empty"
                        }
                        transactionType.isEmpty() -> {
                            this.etTransactionType.error = "Transaction type must not be empty"
                        }
                        date.isEmpty() -> {
                            this.etWhen.error = "Date must not be empty"
                        }
                        selectedAccountId.isBlank() -> {
                            this.etAccount.error = "Please select an account"
                        }
                        else -> {
                            // Block expense if it would make account balance negative
                            if (transactionType == "Expense") {
                                val account = viewModel.allAccounts.value.find { it.id == selectedAccountId }
                                if (account != null && account.balance < amount) {
                                    com.google.android.material.snackbar.Snackbar.make(
                                        binding.root,
                                        R.string.text_insufficient_balance,
                                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                                    ).show()
                                    return@setOnClickListener
                                }
                            }
                            viewModel.insertTransaction(getTransactionContent(), requireContext()).run {
                                binding.root.snack(
                                    string = R.string.success_expense_saved
                                )
                                findNavController().navigate(
                                    R.id.action_addTransactionFragment_to_dashboardFragment
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getTransactionContent(): Transaction = binding.addTransactionLayout.let {
        val title = it.etTitle.text.toString()
        val amount = parseDouble(it.etAmount.text.toString())
        val transactionType = it.etTransactionType.text.toString()
        val tag = "None"
        val date = it.etWhen.text.toString()
        val note = it.etNote.text.toString()

        return Transaction(title, amount, transactionType, tag, date, note, accountId = selectedAccountId)
    }

    private fun setupDynamicQuickTiles(
        chipGroup: com.google.android.material.chip.ChipGroup,
        quickTiles: List<String>
    ) {
        // Clear existing chips except the hardcoded ones we'll reuse
        chipGroup.removeAllViews()
        
        // Dynamically create chips for each tile
        for (tile in quickTiles) {
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = tile
                isCheckable = true
                setOnClickListener {
                    binding.addTransactionLayout.etTitle.setText(tile)
                }
            }
            chipGroup.addView(chip)
        }
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentAddTransactionBinding.inflate(inflater, container, false)
}
