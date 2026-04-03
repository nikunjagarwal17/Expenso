package dev.spikeysanju.expensetracker.view.main

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.spikeysanju.expensetracker.R
import dev.spikeysanju.expensetracker.data.local.datastore.UIModeImpl
import dev.spikeysanju.expensetracker.databinding.ActivityMainBinding
import dev.spikeysanju.expensetracker.repo.TransactionRepo
import dev.spikeysanju.expensetracker.services.exportcsv.ExportCsvService
import dev.spikeysanju.expensetracker.view.main.viewmodel.TransactionViewModel
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var navHostFragment: NavHostFragment
    private lateinit var appBarConfiguration: AppBarConfiguration

    @Inject
    lateinit var repo: TransactionRepo
    @Inject
    lateinit var exportCsvService: ExportCsvService
    @Inject
    lateinit var themeManager: UIModeImpl
    private val viewModel: TransactionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /**
         * Just so the viewModel doesn't get removed by the compiler, as it isn't used
         * anywhere here for now
         */
        viewModel

        initViews(binding)
        observeThemeMode()
        observeNavElements(binding, navHostFragment.navController)
    }

    private fun observeThemeMode() {
        lifecycleScope.launchWhenStarted {
            viewModel.getUIMode.collect {
                val mode = when (it) {
                    true -> AppCompatDelegate.MODE_NIGHT_YES
                    false -> AppCompatDelegate.MODE_NIGHT_NO
                }
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    private fun observeNavElements(
        binding: ActivityMainBinding,
        navController: NavController
    ) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val shouldHideAppBar = destination.id == R.id.authWelcomeFragment
            binding.appbar.visibility = if (shouldHideAppBar) View.GONE else View.VISIBLE

            val navHostLayoutParams = binding.navHostFragment.layoutParams as ViewGroup.MarginLayoutParams
            navHostLayoutParams.topMargin = if (shouldHideAppBar) {
                0
            } else {
                getActionBarHeight()
            }
            binding.navHostFragment.layoutParams = navHostLayoutParams

            when (destination.id) {
                R.id.authWelcomeFragment -> {
                    supportActionBar!!.setDisplayShowTitleEnabled(false)
                }

                R.id.dashboardFragment -> {
                    supportActionBar!!.setDisplayShowTitleEnabled(false)
                }
                R.id.addTransactionFragment -> {
                    supportActionBar!!.setDisplayShowTitleEnabled(true)
                    binding.toolbar.title = getString(R.string.text_add_transaction)
                }
                R.id.loginFragment -> {
                    supportActionBar!!.setDisplayShowTitleEnabled(true)
                    binding.toolbar.title = getString(R.string.text_login)
                }
                R.id.signupFragment -> {
                    supportActionBar!!.setDisplayShowTitleEnabled(true)
                    binding.toolbar.title = getString(R.string.text_signup)
                }
                else -> {
                    supportActionBar!!.setDisplayShowTitleEnabled(true)
                }
            }
        }
    }

    private fun getActionBarHeight(): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
            resources.getDimensionPixelSize(typedValue.resourceId)
        } else {
            0
        }
    }

    private fun initViews(binding: ActivityMainBinding) {
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.setDisplayShowTitleEnabled(false)

        navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment?
            ?: return

        with(navHostFragment.navController) {
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.authWelcomeFragment,
                    R.id.dashboardFragment
                )
            )
            setupActionBarWithNavController(this, appBarConfiguration)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        navHostFragment.navController.navigateUp()
        return super.onSupportNavigateUp()
    }
}
