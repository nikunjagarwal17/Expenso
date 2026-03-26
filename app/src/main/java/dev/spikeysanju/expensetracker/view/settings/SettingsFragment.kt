package dev.spikeysanju.expensetracker.view.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentSettingsBinding

import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dev.spikeysanju.expensetracker.view.main.viewmodel.TransactionViewModel
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
        // ActivityResultContracts for import/export
        private val exportLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val accounts = viewModel.allAccounts.value
                        val transactions = when (val state = viewModel.uiState.value) {
                            is dev.spikeysanju.expensetracker.utils.viewState.ViewState.Success -> state.transaction
                            else -> emptyList()
                        }
                        val csv = buildCsv(accounts, transactions)
                        val resolver = requireContext().contentResolver
                        resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Export Data")
                            .setMessage("Export successful!\nSaved to: $uri")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (e: Exception) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Export Data")
                            .setMessage("Export failed: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }

        private val importLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val csv = readCsvFromUri(uri)
                        val (accounts, transactions) = parseCsv(csv)
                        // Replace all data (delete old, insert new, no double counting)
                        viewModel.replaceAllData(accounts, transactions)
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Import Data")
                            .setMessage("Import successful!\nAccounts: ${accounts.size}, Transactions: ${transactions.size}")
                            .setPositiveButton("OK", null)
                            .show()
                    } catch (e: Exception) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Import Data")
                            .setMessage("Import failed: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransactionViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.currencyLayout.setOnClickListener {
            showCurrencyDialog()
        }
        binding.importExportLayout.setOnClickListener {
            showImportExportDialog()
        }
        // Add quick tiles management if the UI element exists
        try {
            binding.quickTilesLayout.setOnClickListener {
                showQuickTilesDialog()
            }
        } catch (e: Exception) {
            // UI element doesn't exist yet, that's fine
        }
    }

    private fun showCurrencyDialog() {
        val currencies = arrayOf("₹ Rupee", "$ Dollar", "€ Euro", "¥ Yen", "£ Pound")
        val symbols = arrayOf("₹", "$", "€", "¥", "£")
        val currencyNames = arrayOf("Rupee", "Dollar", "Euro", "Yen", "Pound")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_currency)
            .setItems(currencies) { _, which ->
                viewModel.setCurrencySymbol(symbols[which])
                // Show notification
                Snackbar.make(
                    binding.root,
                    "Currency symbol changed to ${currencyNames[which]} (${symbols[which]})",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun showQuickTilesDialog() {
        val currentTiles = viewModel.getQuickTiles().toMutableList()
        val adapter = TilesListAdapter(currentTiles) {}

        val recyclerView = androidx.recyclerview.widget.RecyclerView(requireContext()).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        val addButton = android.widget.Button(requireContext()).apply {
            text = "+ Add Tile"
            setOnClickListener {
                showAddTileDialog(currentTiles, adapter)
            }
        }

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Add RecyclerView with fixed height
            val recyclerParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                500
            )
            addView(recyclerView, recyclerParams)

            // Add button
            val buttonParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            buttonParams.setMargins(16, 16, 16, 16)
            addView(addButton, buttonParams)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Manage Quick Tiles")
            .setMessage("Tap tiles to delete them")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                if (currentTiles.isNotEmpty()) {
                    viewModel.setQuickTiles(currentTiles)
                    Snackbar.make(
                        binding.root,
                        "Quick tiles updated successfully",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    Snackbar.make(
                        binding.root,
                        "Please add at least one quick tile",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTileDialog(
        tilesList: MutableList<String>,
        adapter: TilesListAdapter
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Enter tile name"
            setSingleLine()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add New Tile")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val tileName = input.text.toString().trim()
                if (tileName.isNotEmpty()) {
                    tilesList.add(tileName)
                    adapter.notifyItemInserted(tilesList.size - 1)
                    Snackbar.make(
                        binding.root,
                        "Tile added: $tileName",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    Snackbar.make(
                        binding.root,
                        "Please enter a tile name",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportExportDialog() {
        val options = arrayOf("Import Data", "Export Data")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_export)
            .setItems(options) { _, which ->
                if (which == 0) {
                    // Accept all files to ensure user can select exported CSV regardless of MIME type
                    importLauncher.launch(arrayOf("*/*"))
                } else {
                    val filename = "expenso_export_${System.currentTimeMillis()}.csv"
                    exportLauncher.launch(filename)
                }
            }
            .show()
    }



    // CSV helpers
    private fun buildCsv(accounts: List<dev.spikeysanju.expensetracker.model.Account>, transactions: List<dev.spikeysanju.expensetracker.model.Transaction>): String {
        val sb = StringBuilder()
        sb.append("# Accounts\n")
        sb.append("id,name,balance\n")
        accounts.forEach { sb.append("${it.id},${it.name},${it.balance}\n") }
        sb.append("# Transactions\n")
        sb.append("id,title,amount,transactionType,tag,date,note,createdAt,accountId,isTransfer\n")
        transactions.forEach {
            sb.append("${it.id},${it.title},${it.amount},${it.transactionType},${it.tag},${it.date},${it.note},${it.createdAt},${it.accountId},${it.isTransfer}\n")
        }
        return sb.toString()
    }

    private fun saveCsvToFile(csv: String, filename: String): String {
        val resolver = requireContext().contentResolver
        val uri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary:Download/$filename")
        val out = resolver.openOutputStream(uri)
        out?.use { it.write(csv.toByteArray()) }
        return uri.toString()
    }

    private fun readCsvFromUri(uri: android.net.Uri): String {
        val resolver = requireContext().contentResolver
        val input = resolver.openInputStream(uri)
        return input?.bufferedReader()?.readText() ?: ""
    }

    private fun parseCsv(csv: String): Pair<List<dev.spikeysanju.expensetracker.model.Account>, List<dev.spikeysanju.expensetracker.model.Transaction>> {
        val lines = csv.lines()
        val accounts = mutableListOf<dev.spikeysanju.expensetracker.model.Account>()
        val transactions = mutableListOf<dev.spikeysanju.expensetracker.model.Transaction>()
        var mode = ""
        for (line in lines) {
            if (line.startsWith("# Accounts")) mode = "accounts"
            else if (line.startsWith("# Transactions")) mode = "transactions"
            else if (line.isBlank() || line.startsWith("id,")) continue
            else if (mode == "accounts") {
                val parts = line.split(",")
                if (parts.size >= 3) accounts.add(dev.spikeysanju.expensetracker.model.Account(parts[0].toInt(), parts[1], parts[2].toDouble()))
            } else if (mode == "transactions") {
                val parts = line.split(",")
                if (parts.size >= 10) transactions.add(
                    dev.spikeysanju.expensetracker.model.Transaction(
                        title = parts[1],
                        amount = parts[2].toDouble(),
                        transactionType = parts[3],
                        tag = parts[4],
                        date = parts[5],
                        note = parts[6],
                        createdAt = parts[7].toLong(),
                        id = parts[0].toInt(),
                        accountId = parts[8].toInt(),
                        isTransfer = parts[9].toBoolean()
                    )
                )
            }
        }
        return Pair(accounts, transactions)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
