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
import dev.spikeysanju.expensetracker.databinding.FragmentSignupBinding
import dev.spikeysanju.expensetracker.data.remote.client.ApiResult
import dev.spikeysanju.expensetracker.repo.AuthRepo
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SignupFragment : Fragment() {
    @Inject
    lateinit var authRepo: AuthRepo

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSignup.setOnClickListener {
            val fullName = binding.etFullName.text?.toString()?.trim().orEmpty()
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()

            binding.tilFullName.error = null
            binding.tilEmail.error = null
            binding.tilPassword.error = null

            when {
                fullName.isEmpty() -> binding.tilFullName.error = getString(R.string.text_full_name_required)
                email.isEmpty() -> binding.tilEmail.error = getString(R.string.text_email_required)
                password.isEmpty() -> binding.tilPassword.error = getString(R.string.text_password_required)
                else -> {
                    binding.btnSignup.isEnabled = false
                    lifecycleScope.launch {
                        when (val result = authRepo.signup(email, password, fullName.ifBlank { null })) {
                            is ApiResult.Success -> {
                                Toast.makeText(
                                    requireContext(),
                                    result.data,
                                    Toast.LENGTH_LONG
                                ).show()
                                findNavController().navigate(R.id.action_signupFragment_to_loginFragment)
                            }

                            is ApiResult.Error -> {
                                binding.tilPassword.error = result.message
                            }
                        }
                        binding.btnSignup.isEnabled = true
                    }
                }
            }
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvLogin.setOnClickListener {
            findNavController().navigate(R.id.action_signupFragment_to_loginFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
