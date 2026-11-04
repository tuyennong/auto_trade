package com.smclab.autotrade.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.FragmentStrategiesBinding
import com.smclab.autotrade.engine.MultiTimeframe
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.model.AppSettings
import com.smclab.autotrade.model.StrategyConfig
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.ui.adapter.StrategyAdapter
import com.smclab.autotrade.ui.dialog.StrategyConfigDialog

class StrategiesFragment : Fragment() {

    private var _binding: FragmentStrategiesBinding? = null
    private val binding get() = _binding!!
    private val adapter = StrategyAdapter(
        onToggle = { id, enabled -> AppRepository.setStrategyEnabled(id, enabled) },
        onConfigure = { id ->
            StrategyConfigDialog.newInstance(id).show(parentFragmentManager, "strategy_config")
        },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStrategiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvStrategies.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStrategies.adapter = adapter
        binding.rvStrategies.itemAnimator = null

        binding.swAuto.setOnCheckedChangeListener { _, checked ->
            if (AppRepository.autoTrade.value != checked) AppRepository.setAutoTrade(checked)
        }
        binding.swMtf.setOnCheckedChangeListener { _, checked ->
            val settings = AppRepository.settings.value ?: AppSettings()
            if (settings.autoHtfFilter != checked) {
                AppRepository.saveSettings(settings.copy(autoHtfFilter = checked))
            }
        }

        AppRepository.configs.observe(viewLifecycleOwner) { configs ->
            adapter.submitList(configs)
            binding.tvStrategyCount.text = "${configs.count { it.enabled }}/${StrategyCatalog.all.size} bật"
        }
        AppRepository.autoTrade.observe(viewLifecycleOwner) { auto ->
            binding.swAuto.setOnCheckedChangeListener(null)
            binding.swAuto.isChecked = auto
            binding.swAuto.setOnCheckedChangeListener { _, checked ->
                if (AppRepository.autoTrade.value != checked) AppRepository.setAutoTrade(checked)
            }
            renderStatus()
        }
        AppRepository.settings.observe(viewLifecycleOwner) { settings ->
            binding.swMtf.setOnCheckedChangeListener(null)
            binding.swMtf.isChecked = settings.autoHtfFilter
            binding.swMtf.setOnCheckedChangeListener { _, checked ->
                val current = AppRepository.settings.value ?: AppSettings()
                if (current.autoHtfFilter != checked) {
                    AppRepository.saveSettings(current.copy(autoHtfFilter = checked))
                }
            }
            binding.tvMtfStatus.text = getString(
                if (settings.autoHtfFilter) R.string.auto_htf_on else R.string.auto_htf_off,
            )
            renderStatus()
        }
        AppRepository.chartStrategy.observe(viewLifecycleOwner) { renderStatus() }
        AppRepository.selectedSymbol.observe(viewLifecycleOwner) { renderStatus() }
        AppRepository.selectedTimeframe.observe(viewLifecycleOwner) { renderStatus() }
    }

    private fun renderStatus() {
        if (_binding == null) return
        val auto = AppRepository.autoTrade.value == true
        val settings = AppRepository.settings.value ?: AppSettings()
        val symbol = AppRepository.selectedSymbol.value ?: "XAUUSD"
        val tf = AppRepository.selectedTimeframe.value ?: Timeframe.H1
        val htf = if (settings.autoHtfFilter) {
            tf.higher()?.let { "HTF ${it.label}" } ?: "không có HTF"
        } else {
            "không lọc HTF"
        }
        val chartId = AppRepository.chartStrategy.value ?: MultiTimeframe.AUTO
        val strategyNote = if (chartId == MultiTimeframe.AUTO) {
            "chiến lược tốt nhất"
        } else {
            StrategyCatalog.byId(chartId)?.name ?: "chiến lược đã chọn"
        }
        binding.tvAutoStatus.text = if (auto) {
            getString(R.string.auto_status_on, symbol, tf.label, "$htf · $strategyNote")
        } else {
            getString(R.string.auto_trade_off)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvStrategies.adapter = null
        _binding = null
    }
}
