package dev.spikeysanju.expensetracker.view.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentLoginBinding
import dev.spikeysanju.expensetracker.data.remote.client.ApiResult
import dev.spikeysanju.expensetracker.repo.AuthRepo
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {
    @Inject
    lateinit var authRepo: AuthRepo

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
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.text_login_success),
                                    Toast.LENGTH_SHORT
                                ).show()
                                findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
                            }

                            is ApiResult.Error -> {
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
