package com.smclab.autotrade

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.ActivityMainBinding
import com.smclab.autotrade.model.Account
import com.smclab.autotrade.ui.Ui
import com.smclab.autotrade.ui.dialog.Mt5LoginDialog
import com.smclab.autotrade.ui.dialog.SettingsDialog
import com.smclab.autotrade.ui.fragment.BacktestFragment
import com.smclab.autotrade.ui.fragment.ChartFragment
import com.smclab.autotrade.ui.fragment.MarketsFragment
import com.smclab.autotrade.ui.fragment.StrategiesFragment
import com.smclab.autotrade.ui.fragment.TerminalFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var currentTab = R.id.nav_markets

    private val ticker = object : Runnable {
        override fun run() {
            AppRepository.onTick()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRepository.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("settings") == null) {
                SettingsDialog().show(supportFragmentManager, "settings")
            }
        }
        binding.btnAccount.setOnClickListener { openAccountDialog() }
        binding.accountStrip.setOnClickListener { openAccountDialog() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId != currentTab) openTab(item.itemId)
            true
        }

        AppRepository.message.observe(this) { message ->
            if (message != null) {
                Snackbar.make(binding.root, message.text, Snackbar.LENGTH_SHORT).show()
            }
        }

        AppRepository.account.observe(this) { renderAccountStrip(it) }

        val restored = savedInstanceState?.getInt(KEY_TAB, R.id.nav_markets) ?: R.id.nav_markets
        currentTab = restored
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            showFragment(restored)
        }
        binding.bottomNav.selectedItemId = restored

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bottomNav.selectedItemId != R.id.nav_markets) {
                    binding.bottomNav.selectedItemId = R.id.nav_markets
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun openAccountDialog() {
        if (supportFragmentManager.findFragmentByTag("mt5_login") == null) {
            Mt5LoginDialog().show(supportFragmentManager, "mt5_login")
        }
    }

    private fun renderAccountStrip(account: Account) {
        val connected = account.isMt5Session
        binding.tvStripLogin.text = if (connected) {
            getString(R.string.account_strip_mt5, account.login, account.server)
        } else {
            getString(R.string.account_not_logged)
        }
        binding.tvStripLogin.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.accent else R.color.text_dim),
        )
        binding.tvStripStatus.text = if (connected) {
            getString(R.string.account_realtime)
        } else {
            getString(R.string.account_local_demo)
        }
        binding.tvStripBalance.text = Ui.money(account.balance)
        binding.tvStripEquity.text = "Eq ${Ui.money(account.equity)}"
        binding.tvStripFloating.text = Ui.signedMoney(account.floatingPl)
        binding.tvStripFloating.setTextColor(Ui.profitColor(this, account.floatingPl))
    }

    fun openTab(itemId: Int) {
        if (itemId != currentTab || supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            showFragment(itemId)
        }
        if (binding.bottomNav.selectedItemId != itemId) {
            binding.bottomNav.selectedItemId = itemId
        }
    }

    private fun showFragment(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_chart -> ChartFragment()
            R.id.nav_strategies -> StrategiesFragment()
            R.id.nav_backtest -> BacktestFragment()
            R.id.nav_terminal -> TerminalFragment()
            else -> MarketsFragment()
        }
        currentTab = itemId
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    companion object {
        private const val KEY_TAB = "tab"
    }
}
