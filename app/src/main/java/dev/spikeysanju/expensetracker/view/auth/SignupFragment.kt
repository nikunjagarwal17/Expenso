package dev.spikeysanju.expensetracker.view.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentSignupBinding
import dev.spikeysanju.expensetracker.utils.AuthSessionManager

class SignupFragment : Fragment() {
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
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()

            binding.tilEmail.error = null
            binding.tilPassword.error = null

            when {
                email.isEmpty() -> binding.tilEmail.error = getString(R.string.text_email_required)
                password.isEmpty() -> binding.tilPassword.error = getString(R.string.text_password_required)
                else -> {
                    // TODO: Replace with real signup logic.
                    AuthSessionManager.setLoggedIn(requireContext(), true)
                    findNavController().navigate(R.id.action_signupFragment_to_dashboardFragment)
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
