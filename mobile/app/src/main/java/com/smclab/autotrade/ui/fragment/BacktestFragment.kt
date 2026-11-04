package com.smclab.autotrade.ui.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.FragmentBacktestBinding
import com.smclab.autotrade.engine.BacktestEngine
import com.smclab.autotrade.engine.BacktestRequest
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.model.BacktestMetrics
import com.smclab.autotrade.model.BacktestResult
import com.smclab.autotrade.model.StrategyDef
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.ui.Ui
import com.smclab.autotrade.ui.adapter.BacktestTradeAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class BacktestFragment : Fragment() {

    private var _binding: FragmentBacktestBinding? = null
    private val binding get() = _binding!!
    private val tradeAdapter = BacktestTradeAdapter()

    private val symbols = MarketData.symbols
    private val strategies: List<StrategyDef> = StrategyCatalog.all
    private var symbol: String = "XAUUSD"
    private var timeframe: Timeframe = Timeframe.H1
    private var htf: Timeframe? = null
    private var strategy: StrategyDef = strategies.first()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBacktestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        symbol = AppRepository.selectedSymbol.value ?: "XAUUSD"
        timeframe = AppRepository.selectedTimeframe.value ?: Timeframe.H1
        strategy = strategies.firstOrNull { AppRepository.configFor(it.id).enabled } ?: strategies.first()

        setupDropdowns()
        binding.rvBacktestTrades.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBacktestTrades.adapter = tradeAdapter
        binding.rvBacktestTrades.itemAnimator = null
        binding.btnRun.setOnClickListener { runBacktest() }
    }

    private fun setupDropdowns() {
        binding.ddSymbol.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                symbols.map { "${it.symbol} · ${it.description}" },
            ),
        )
        binding.ddSymbol.setText(
            symbols.firstOrNull { it.symbol == symbol }?.let { "${it.symbol} · ${it.description}" }
                ?: symbols.first().symbol,
            false,
        )
        binding.ddSymbol.setOnItemClickListener { _, _, position, _ ->
            symbols.getOrNull(position)?.let { symbol = it.symbol }
        }

        binding.ddTf.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, Timeframe.entries.map { it.label }),
        )
        binding.ddTf.setText(timeframe.label, false)
        binding.ddTf.setOnItemClickListener { _, _, position, _ ->
            Timeframe.entries.getOrNull(position)?.let {
                timeframe = it
                rebuildHtfDropdown()
            }
        }

        binding.ddStrategy.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, strategies.map { it.name }),
        )
        binding.ddStrategy.setText(strategy.name, false)
        binding.ddStrategy.setOnItemClickListener { _, _, position, _ ->
            strategies.getOrNull(position)?.let { strategy = it }
        }

        rebuildHtfDropdown()
    }

    private fun htfOptions(): List<Timeframe?> =
        listOf(null) + Timeframe.entries.filter { it.minutes > timeframe.minutes }

    private fun rebuildHtfDropdown() {
        val options = htfOptions()
        val preferred = timeframe.higher()
        htf = preferred?.takeIf { options.contains(it) }
        val labels = options.map { it?.label ?: getString(R.string.htf_off_option) }
        binding.ddHtf.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels),
        )
        val index = options.indexOf(htf).takeIf { it >= 0 } ?: 0
        htf = options[index]
        binding.ddHtf.setText(labels[index], false)
        binding.ddHtf.setOnItemClickListener { _, _, position, _ ->
            htf = options.getOrNull(position)
        }
    }

    private fun runBacktest() {
        if (_binding == null) return
        val bars = binding.etBars.text?.toString()?.toIntOrNull() ?: 800
        val balance = binding.etBalance.text?.toString()?.toDoubleOrNull() ?: 10_000.0
        val risk = binding.etRisk.text?.toString()?.toDoubleOrNull() ?: 1.0
        val rr = binding.etRr.text?.toString()?.toDoubleOrNull() ?: 2.0
        if (bars !in 120..1_800) {
            binding.etBars.error = "120 – 1800"
            return
        }
        if (balance < 100.0) {
            binding.etBalance.error = "Tối thiểu 100 USD"
            return
        }
        if (risk !in 0.1..5.0) {
            binding.etRisk.error = "0.1 – 5.0%"
            return
        }
        if (rr !in 0.5..10.0) {
            binding.etRr.error = "0.5 – 10.0"
            return
        }

        val settings = AppRepository.settings.value
        val request = BacktestRequest(
            strategy = strategy,
            config = AppRepository.configFor(strategy.id),
            symbol = symbol,
            timeframe = timeframe,
            bars = bars,
            initialBalance = balance,
            riskPercent = risk,
            riskReward = rr,
            commissionPerLot = settings?.commissionPerLot ?: 7.0,
            htf = htf,
        )

        binding.backtestProgress.visibility = View.VISIBLE
        binding.btnRun.isEnabled = false
        binding.tvBacktestStatus.text = getString(R.string.backtest_running)

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching { BacktestEngine.run(request) }
            }
            if (_binding == null) return@launch
            binding.backtestProgress.visibility = View.GONE
            binding.btnRun.isEnabled = true
            outcome.onSuccess { result ->
                binding.tvBacktestStatus.text = getString(R.string.backtest_saved)
                renderResult(result)
                AppRepository.saveBacktest(result)
            }.onFailure { error ->
                binding.tvBacktestStatus.text = error.message ?: getString(R.string.error_generic)
            }
        }
    }

    private fun renderResult(result: BacktestResult) {
        binding.emptyState.visibility = View.GONE
        binding.resultsContainer.visibility = View.VISIBLE
        binding.tvResultName.text = result.strategyName
        val htfNote = result.htfTimeframe?.let { " · HTF ${it.label}" } ?: " · không lọc HTF"
        binding.tvResultCaption.text =
            "${result.symbol} · ${result.timeframe.label}$htfNote · ${result.bars} nến · Risk ${trim(result.riskPercent)}%"

        renderMetrics(result.metrics)
        binding.equityCurve.setData(result.equityCurve, result.metrics.initialBalance)

        tradeAdapter.symbol = result.symbol
        val shown = result.trades.takeLast(80).reversed()
        tradeAdapter.submitList(shown)
        binding.tvTradeCount.text = "${result.metrics.trades} lệnh"
        binding.tvNoTrades.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.tvBacktestNote.text =
            "${result.dataNote} · ${if (result.htfTimeframe != null) "có xác nhận HTF" else "không xác nhận HTF"}"
    }

    private fun renderMetrics(metrics: BacktestMetrics) {
        binding.metricsContainer.removeAllViews()
        val netColor = Ui.profitColor(requireContext(), metrics.netProfit)
        addMetric(getString(R.string.net_profit), Ui.signedMoney(metrics.netProfit), netColor)
        addMetric(getString(R.string.return_pct), Ui.signedPct(metrics.returnPct), netColor)
        addMetric(getString(R.string.win_rate), String.format(Locale.US, "%.1f%%", metrics.winRate), null)
        addMetric(getString(R.string.profit_factor), String.format(Locale.US, "%.2f", metrics.profitFactor), null)
        addMetric(
            getString(R.string.max_drawdown),
            String.format(Locale.US, "%.2f%% · %s", metrics.maxDrawdownPct, Ui.money(metrics.maxDrawdownAbs)),
            R.color.sell,
        )
        addMetric(getString(R.string.total_trades), "${metrics.trades}  (L${metrics.longTrades}/S${metrics.shortTrades})", null)
        addMetric(getString(R.string.expectancy), Ui.signedMoney(metrics.expectancy), null)
        addMetric(getString(R.string.avg_r), String.format(Locale.US, "%+.2fR", metrics.avgR), null)
        addMetric("Sharpe", String.format(Locale.US, "%.2f", metrics.sharpe), null)
        addMetric("Chuỗi thua tối đa", metrics.maxConsecutiveLosses.toString(), null)
    }

    private fun addMetric(label: String, value: String, colorRes: Int?) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_stat)
            setPadding(
                Ui.dp(requireContext(), 10f),
                Ui.dp(requireContext(), 8f),
                Ui.dp(requireContext(), 10f),
                Ui.dp(requireContext(), 8f),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(requireContext(), 6f) }
        }
        val labelView = TextView(requireContext()).apply {
            text = label
            textSize = 10.5f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 12f
            gravity = Gravity.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    colorRes ?: R.color.text,
                ),
            )
        }
        row.addView(labelView)
        row.addView(valueView)
        binding.metricsContainer.addView(row)
    }

    private fun trim(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else String.format(Locale.US, "%.1f", value)

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvBacktestTrades.adapter = null
        _binding = null
    }
}
