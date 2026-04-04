package dev.spikeysanju.expensetracker.view.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.spikeysanju.expensetracker.BuildConfig
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentSettingsBinding
import dev.spikeysanju.expensetracker.utils.AuthSessionManager
import dev.spikeysanju.expensetracker.utils.SyncLogFile
import java.io.File

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
        updateAuthActionLabel()
        refreshProfileInfo()

        binding.authActionLayout.setOnClickListener {
            if (AuthSessionManager.isLoggedIn(requireContext())) {
                AuthSessionManager.clearSession(requireContext())
                Snackbar.make(binding.root, getString(R.string.text_logged_out), Snackbar.LENGTH_SHORT)
                    .show()

                findNavController().navigate(
                    R.id.authWelcomeFragment,
                    null,
                    NavOptions.Builder()
                        .setPopUpTo(R.id.dashboardFragment, true)
                        .build()
                )
            } else {
                findNavController().navigate(R.id.authWelcomeFragment)
            }
            updateAuthActionLabel()
        }

        binding.forgotPasswordLayout.setOnClickListener {
            requestRecoveryEmail { email ->
                lifecycleScope.launch {
                    when (val result = viewModel.forgotPassword(email)) {
                        is dev.spikeysanju.expensetracker.data.remote.client.ApiResult.Success -> {
                            Snackbar.make(binding.root, result.data, Snackbar.LENGTH_LONG).show()
                        }

                        is dev.spikeysanju.expensetracker.data.remote.client.ApiResult.Error -> {
                            Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        binding.resendVerificationLayout.setOnClickListener {
            requestRecoveryEmail { email ->
                lifecycleScope.launch {
                    when (val result = viewModel.resendVerification(email)) {
                        is dev.spikeysanju.expensetracker.data.remote.client.ApiResult.Success -> {
                            Snackbar.make(binding.root, result.data, Snackbar.LENGTH_LONG).show()
                        }

                        is dev.spikeysanju.expensetracker.data.remote.client.ApiResult.Error -> {
                            Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        binding.currencyLayout.setOnClickListener {
            showCurrencyDialog()
        }
        binding.importExportLayout.setOnClickListener {
            showImportExportDialog()
        }
        binding.syncDebugLogLayout.visibility =
            if (BuildConfig.ENABLE_SYNC_DEBUG_BUTTON) View.VISIBLE else View.GONE
        binding.tvSyncDebugLog.text = getString(R.string.text_export_sync_debug_log)
        binding.syncDebugLogLayout.setOnClickListener {
            exportSyncDebugLog()
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

    override fun onResume() {
        super.onResume()
        updateAuthActionLabel()
        refreshProfileInfo()
    }

    private fun updateAuthActionLabel() {
        val isLoggedIn = AuthSessionManager.isLoggedIn(requireContext())
        val actionText = if (isLoggedIn) {
            getString(R.string.text_logout)
        } else {
            getString(R.string.text_login_signup)
        }
        binding.tvAuthAction.text = actionText
        binding.forgotPasswordLayout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.resendVerificationLayout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE

        if (!isLoggedIn) {
            binding.tvProfileName.text = getString(R.string.text_profile_guest)
            binding.tvProfileEmail.text = getString(R.string.text_profile_email_placeholder)
        }
    }

    private fun refreshProfileInfo() {
        val context = requireContext()
        val cachedDisplayName = AuthSessionManager.getDisplayName(context)
        val cachedEmail = AuthSessionManager.getUserEmail(context)

        binding.tvProfileName.text = cachedDisplayName ?: getString(R.string.text_profile_guest)
        binding.tvProfileEmail.text = cachedEmail ?: getString(R.string.text_profile_email_placeholder)

        if (!AuthSessionManager.isLoggedIn(context)) {
            return
        }

        lifecycleScope.launch {
            when (val result = viewModel.refreshProfileCache(context)) {
                is dev.spikeysanju.expensetracker.data.remote.client.ApiResult.Success -> {
                    val latestName = AuthSessionManager.getDisplayName(context)
                    val latestEmail = AuthSessionManager.getUserEmail(context)
                    binding.tvProfileName.text = latestName ?: getString(R.string.text_profile_guest)
                    binding.tvProfileEmail.text = latestEmail ?: getString(R.string.text_profile_email_placeholder)
                }

                is dev.spikeysanju.expensetracker.data.remote.client.ApiResult.Error -> {
                    // Keep cached values when network/profile refresh fails.
                }
            }
        }
    }

    private fun requestRecoveryEmail(onEmailReady: (String) -> Unit) {
        val context = requireContext()
        val cachedEmail = AuthSessionManager.getUserEmail(context)
        if (!cachedEmail.isNullOrBlank()) {
            onEmailReady(cachedEmail)
            return
        }

        val input = android.widget.EditText(context).apply {
            hint = getString(R.string.text_email)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.text_enter_email)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val email = input.text?.toString()?.trim().orEmpty()
                if (email.isNotEmpty()) {
                    onEmailReady(email)
                } else {
                    Snackbar.make(binding.root, getString(R.string.text_email_required), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun exportSyncDebugLog() {
        val syncLogFile = File(requireContext().filesDir, "sync.log")
        if (!syncLogFile.exists() || syncLogFile.length() == 0L) {
            Snackbar.make(binding.root, getString(R.string.text_sync_log_not_found), Snackbar.LENGTH_SHORT)
                .show()
            return
        }

        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(requireContext(), authority, syncLogFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Expenso Sync Debug Log")
            putExtra(Intent.EXTRA_TEXT, "Sync log path: ${SyncLogFile.path(requireContext())}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, getString(R.string.text_export_sync_debug_log)))
    }



    // CSV helpers
    private fun buildCsv(accounts: List<dev.spikeysanju.expensetracker.model.Account>, transactions: List<dev.spikeysanju.expensetracker.model.Transaction>): String {
        val sb = StringBuilder()
        sb.append("# Accounts\n")
        sb.append("id,name,balance\n")
        accounts.forEach { sb.append("${it.id},${it.name},${it.balance}\n") }
        sb.append("# Transactions\n")
        sb.append("id,title,amount,transactionType,tag,date,note,createdAt,accountId,isTransfer\n")
        val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        val oldestFirstTransactions = transactions.sortedWith(
            compareBy<dev.spikeysanju.expensetracker.model.Transaction> {
                runCatching { dateFormat.parse(it.date)?.time ?: 0L }.getOrDefault(0L)
            }.thenBy { it.createdAt }
        )
        oldestFirstTransactions.forEach {
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
                if (parts.size >= 3) accounts.add(
                    dev.spikeysanju.expensetracker.model.Account(
                        id = parts[0],
                        name = parts[1],
                        balance = parts[2].toDouble()
                    )
                )
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
                        id = parts[0],
                        accountId = parts[8],
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
