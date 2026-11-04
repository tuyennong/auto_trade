package com.smclab.autotrade

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.ActivityMainBinding
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

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId != currentTab) openTab(item.itemId)
            true
        }

        AppRepository.message.observe(this) { message ->
            if (message != null) {
                Snackbar.make(binding.root, message.text, Snackbar.LENGTH_SHORT).show()
            }
        }

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
