package dev.spikeysanju.expensetracker.view.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentEditTransactionBinding
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
class EditTransactionFragment : BaseFragment<FragmentEditTransactionBinding, TransactionViewModel>() {
    private val args: EditTransactionFragmentArgs by navArgs()
    override val viewModel: TransactionViewModel by activityViewModels()

    private var selectedAccountId: String = ""
    private var selectedAccountName: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // receiving bundles here
        val transaction = args.transaction
        selectedAccountId = transaction.accountId
        initViews()
        loadData(transaction)
        observeAccounts()
        // Observe currency symbol and update amount prefix
        lifecycleScope.launchWhenStarted {
            viewModel.currencySymbol.collect { symbol ->
                binding.addTransactionLayout.etAmountView.prefixText = symbol
            }
        }
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
                    // Pre-select the current account
                    val currentAccount = accounts.find { it.id == args.transaction.accountId }
                    if (currentAccount != null) {
                        binding.addTransactionLayout.etAccount.setText(currentAccount.name, false)
                        selectedAccountName = currentAccount.name
                    } else if (accounts.isNotEmpty()) {
                        binding.addTransactionLayout.etAccount.setText(accounts[0].name, false)
                        selectedAccountId = accounts[0].id
                        selectedAccountName = accounts[0].name
                    }
                }
            }
        }
    }

    private fun loadData(transaction: Transaction) = with(binding) {
        addTransactionLayout.etTitle.setText(transaction.title)
        addTransactionLayout.etAmount.setText(transaction.amount.toString())
        addTransactionLayout.etTransactionType.setText(transaction.transactionType, false)
        addTransactionLayout.etWhen.setText(transaction.date)
        addTransactionLayout.etNote.setText(transaction.note)
    }

    private fun initViews() = with(binding) {
        val transactionTypeAdapter =
            ArrayAdapter(
                requireContext(),
                R.layout.item_autocomplete_layout,
                Constants.transactionType
            )

        // Set list to TextInputEditText adapter
        addTransactionLayout.etTransactionType.setAdapter(transactionTypeAdapter)

        // Load and setup dynamic quick title chips
        // Load and setup dynamic quick title chips
        val quickTiles = viewModel.getQuickTiles()
        setupDynamicQuickTiles(addTransactionLayout.quickTitleChipGroup, quickTiles)

        // Transform TextInputEditText to DatePicker using Ext function
        addTransactionLayout.etWhen.transformIntoDatePicker(
            requireContext(),
            "dd/MM/yyyy",
            Date()
        )

        // Quick date selection chips
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        addTransactionLayout.chipToday.setOnClickListener {
            addTransactionLayout.etWhen.setText(dateFormat.format(Date()))
        }
        addTransactionLayout.chipYesterday.setOnClickListener {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            addTransactionLayout.etWhen.setText(dateFormat.format(cal.time))
        }

        btnSaveTransaction.setOnClickListener {
            binding.addTransactionLayout.apply {
                val (title, amount, transactionType, tag, date, note) =
                    getTransactionContent()
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
                    else -> {
                        viewModel.updateTransaction(getTransactionContent(), requireContext()).also {

                            binding.root.snack(
                                string = R.string.success_expense_saved
                            ).run {
                                findNavController().popBackStack()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getTransactionContent(): Transaction = binding.addTransactionLayout.let {

        val id = args.transaction.id
        val title = it.etTitle.text.toString()
        val amount = parseDouble(it.etAmount.text.toString())
        val transactionType = it.etTransactionType.text.toString()
        val tag = "None"
        val date = it.etWhen.text.toString()
        val note = it.etNote.text.toString()

        return Transaction(
            title = title,
            amount = amount,
            transactionType = transactionType,
            tag = tag,
            date = date,
            note = note,
            createdAt = System.currentTimeMillis(),
            id = id,
            accountId = selectedAccountId
        )
    }

    private fun setupDynamicQuickTiles(
        chipGroup: com.google.android.material.chip.ChipGroup,
        quickTiles: List<String>
    ) {
        // Clear existing chips
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
    ) = FragmentEditTransactionBinding.inflate(inflater, container, false)
}
