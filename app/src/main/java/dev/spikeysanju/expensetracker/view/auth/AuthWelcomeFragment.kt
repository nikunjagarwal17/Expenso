package dev.spikeysanju.expensetracker.view.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.databinding.FragmentAuthWelcomeBinding
import dev.spikeysanju.expensetracker.utils.AuthSessionManager

class AuthWelcomeFragment : Fragment() {
    private var _binding: FragmentAuthWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLogin.setOnClickListener {
            findNavController().navigate(R.id.action_authWelcomeFragment_to_loginFragment)
        }

        binding.btnSignup.setOnClickListener {
            findNavController().navigate(R.id.action_authWelcomeFragment_to_signupFragment)
        }

        binding.btnContinueWithoutLogin.setOnClickListener {
            AuthSessionManager.setLoggedIn(requireContext(), false)
            findNavController().navigate(R.id.action_authWelcomeFragment_to_dashboardFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
