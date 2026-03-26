package dev.spikeysanju.expensetracker.view.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentAccountBinding
import dev.spikeysanju.expensetracker.model.Account
import dev.spikeysanju.expensetracker.view.adapter.AccountAdapter
import dev.spikeysanju.expensetracker.view.base.BaseFragment
import dev.spikeysanju.expensetracker.view.main.viewmodel.TransactionViewModel
import indianRupee
import kotlinx.coroutines.flow.collect
import parseDouble

@AndroidEntryPoint
class AccountFragment : BaseFragment<FragmentAccountBinding, TransactionViewModel>() {
    override val viewModel: TransactionViewModel by activityViewModels()
    private lateinit var accountAdapter: AccountAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRV()
        initViews()
        observeAccounts()
    }

    private fun setupRV() {
        accountAdapter = AccountAdapter()
        binding.accountsRv.apply {
            adapter = accountAdapter
            layoutManager = LinearLayoutManager(activity)
        }
    }

    private fun initViews() {
        binding.btnAddAccount.setOnClickListener {
            showAddAccountDialog()
        }

        binding.btnTransfer.setOnClickListener {
            showTransferDialog()
        }

        accountAdapter.setOnDeleteClickListener { account ->
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.text_delete))
                .setMessage("Delete account \"${account.name}\"?")
                .setPositiveButton("Yes") { _, _ ->
                    viewModel.deleteAccount(account)
                    Toast.makeText(requireContext(), getString(R.string.text_account_deleted), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun observeAccounts() {
        lifecycleScope.launchWhenStarted {
            viewModel.allAccounts.collect { accounts ->
                accountAdapter.differ.submitList(accounts)
                val total = accounts.sumOf { it.balance }
                binding.totalAcrossAccounts.text = indianRupee(total)
            }
        }
    }

    private fun showAddAccountDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_account, null)

        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.dialogAccountName)
        val balanceInput = dialogView.findViewById<TextInputEditText>(R.id.dialogAccountBalance)
        balanceInput.setText("0")

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.text_add_account))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.text_save_transaction)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val balance = parseDouble(balanceInput.text.toString())

                if (name.isNotEmpty() && !balance.isNaN()) {
                    viewModel.insertAccount(Account(name = name, balance = balance))
                } else {
                    Toast.makeText(requireContext(), "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTransferDialog() {
        val accounts = viewModel.allAccounts.value
        if (accounts.size < 2) {
            Toast.makeText(requireContext(), "Need at least 2 accounts for transfer", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_transfer, null)

        val spinnerFrom = dialogView.findViewById<Spinner>(R.id.spinnerFromAccount)
        val spinnerTo = dialogView.findViewById<Spinner>(R.id.spinnerToAccount)
        val amountInput = dialogView.findViewById<TextInputEditText>(R.id.dialogTransferAmount)
        val taxInput = dialogView.findViewById<TextInputEditText>(R.id.dialogTaxAmount)

        val accountNames = accounts.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, accountNames)
        spinnerFrom.adapter = adapter
        spinnerTo.adapter = adapter

        if (accounts.size > 1) {
            spinnerTo.setSelection(1)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.text_transfer))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.text_transfer)) { _, _ ->
                val fromIndex = spinnerFrom.selectedItemPosition
                val toIndex = spinnerTo.selectedItemPosition
                val amount = parseDouble(amountInput.text.toString())
                val tax = parseDouble(taxInput.text.toString()).let { if (it.isNaN()) 0.0 else it }

                if (fromIndex == toIndex) {
                    Toast.makeText(requireContext(), "Cannot transfer to same account", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (amount.isNaN() || amount <= 0) {
                    Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewModel.performTransfer(
                    fromAccountId = accounts[fromIndex].id,
                    toAccountId = accounts[toIndex].id,
                    amount = amount,
                    taxAmount = tax
                )
                Toast.makeText(requireContext(), getString(R.string.text_transfer_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentAccountBinding.inflate(inflater, container, false)
}
