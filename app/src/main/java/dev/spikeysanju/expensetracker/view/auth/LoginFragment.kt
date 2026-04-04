package dev.spikeysanju.expensetracker.view.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentLoginBinding
import dev.spikeysanju.expensetracker.data.remote.client.ApiResult
import dev.spikeysanju.expensetracker.repo.AuthRepo
import dev.spikeysanju.expensetracker.utils.SyncLogFile
import dev.spikeysanju.expensetracker.view.main.viewmodel.TransactionViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {
    @Inject
    lateinit var authRepo: AuthRepo
    private val transactionViewModel: TransactionViewModel by activityViewModels()

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()

            binding.tilEmail.error = null
            binding.tilPassword.error = null

            when {
                email.isEmpty() -> binding.tilEmail.error = getString(R.string.text_email_required)
                password.isEmpty() -> binding.tilPassword.error = getString(R.string.text_password_required)
                else -> {
                    binding.btnLogin.isEnabled = false
                    lifecycleScope.launch {
                        when (val result = authRepo.login(requireContext(), email, password)) {
                            is ApiResult.Success -> {
                                SyncLogFile.append(
                                    requireContext(),
                                    "login.success; trigger_sync; log_file=${SyncLogFile.path(requireContext())}"
                                )
                                handlePostLoginDataChoice()
                            }

                            is ApiResult.Error -> {
                                SyncLogFile.append(requireContext(), "login.failed: ${result.message}")
                                binding.tilPassword.error = result.message
                            }
                        }
                        binding.btnLogin.isEnabled = true
                    }
                }
            }
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvSignup.setOnClickListener {
            findNavController().navigate(R.id.action_loginFragment_to_signupFragment)
        }

        binding.tvForgotPassword.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            if (email.isEmpty()) {
                binding.tilEmail.error = getString(R.string.text_email_required_for_recovery)
                return@setOnClickListener
            }
            binding.tilEmail.error = null
            lifecycleScope.launch {
                when (val result = transactionViewModel.forgotPassword(email)) {
                    is ApiResult.Success -> {
                        Toast.makeText(requireContext(), result.data, Toast.LENGTH_LONG).show()
                    }

                    is ApiResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.tvResendVerification.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            if (email.isEmpty()) {
                binding.tilEmail.error = getString(R.string.text_email_required_for_resend)
                return@setOnClickListener
            }
            binding.tilEmail.error = null
            lifecycleScope.launch {
                when (val result = transactionViewModel.resendVerification(email)) {
                    is ApiResult.Success -> {
                        Toast.makeText(requireContext(), result.data, Toast.LENGTH_LONG).show()
                    }

                    is ApiResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private suspend fun handlePostLoginDataChoice() {
        when (val snapshotResult = transactionViewModel.getSyncConflictSnapshot(requireContext())) {
            is ApiResult.Error -> {
                SyncLogFile.append(requireContext(), "login.post_sync_snapshot_failed: ${snapshotResult.message}")
                transactionViewModel.syncFromRemoteIfLoggedIn(requireContext())
                navigateToDashboardWithToast()
            }

            is ApiResult.Success -> {
                val snapshot = snapshotResult.data
                if (snapshot.hasLocalData && snapshot.hasRemoteData) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Choose Data to Keep")
                        .setMessage(
                            "Local data: ${snapshot.localAccounts} accounts, ${snapshot.localTransactions} transactions\n" +
                                "Server data: ${snapshot.remoteAccounts} accounts, ${snapshot.remoteTransactions} transactions\n\n" +
                                "Keep Local: server data will be deleted and replaced by local data.\n" +
                                "Keep Server: local data will be overwritten by server data."
                        )
                        .setCancelable(false)
                        .setPositiveButton("Keep Local") { _, _ ->
                            lifecycleScope.launch {
                                val result = transactionViewModel.resolveInitialSyncChoice(
                                    context = requireContext(),
                                    keepLocalData = true
                                )
                                if (result is ApiResult.Error) {
                                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                                }
                                navigateToDashboardWithToast()
                            }
                        }
                        .setNegativeButton("Keep Server") { _, _ ->
                            lifecycleScope.launch {
                                val result = transactionViewModel.resolveInitialSyncChoice(
                                    context = requireContext(),
                                    keepLocalData = false
                                )
                                if (result is ApiResult.Error) {
                                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                                }
                                navigateToDashboardWithToast()
                            }
                        }
                        .show()
                } else {
                    // Default behavior: local-first sync logic.
                    transactionViewModel.syncFromRemoteIfLoggedIn(requireContext())
                    navigateToDashboardWithToast()
                }
            }
        }
    }

    private fun navigateToDashboardWithToast() {
        Toast.makeText(
            requireContext(),
            getString(R.string.text_login_success),
            Toast.LENGTH_SHORT
        ).show()
        findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
    }
}
