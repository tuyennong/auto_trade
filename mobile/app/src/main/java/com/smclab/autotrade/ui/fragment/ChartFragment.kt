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
import com.google.android.material.chip.Chip
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.FragmentChartBinding
import com.smclab.autotrade.engine.AnalysisService
import com.smclab.autotrade.engine.ChartAnalysis
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.engine.MultiTimeframe
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.model.AppSettings
import com.smclab.autotrade.model.Side
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.model.Trend
import com.smclab.autotrade.ui.Ui
import com.smclab.autotrade.ui.dialog.OrderTicketDialog
import com.smclab.autotrade.ui.view.ChartOverlays
import com.smclab.autotrade.ui.view.ChartPositionLevel
import com.smclab.autotrade.ui.view.ChartSignal
import java.util.Locale
import kotlin.math.roundToInt

class ChartFragment : Fragment() {

    private var _binding: FragmentChartBinding? = null
    private val binding get() = _binding!!
    private var overlays = ChartOverlays()
    private var analysis: ChartAnalysis? = null
    private var lastAnalyzeAt = 0L
    private var symbolChipsBuilt = false
    private var renderedSymbol: String? = null
    private var renderedTimeframe: Timeframe? = null

    private val strategyIds: List<String> = listOf(MultiTimeframe.AUTO) + StrategyCatalog.all.map { it.id }
    private val strategyLabels: List<String> by lazy {
        listOf(getString(R.string.chart_strategy_auto)) + StrategyCatalog.all.map { it.name }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSymbolChips()
        setupTimeframeButtons()
        setupOverlays()
        setupStrategyDropdown()
        setupHtfSwitch()

        binding.btnBuy.setOnClickListener { openOrder(Side.BUY) }
        binding.btnSell.setOnClickListener { openOrder(Side.SELL) }
        binding.btnChartZoomIn.setOnClickListener { binding.candleChart.zoomIn() }
        binding.btnChartZoomOut.setOnClickListener { binding.candleChart.zoomOut() }
        binding.btnChartReset.setOnClickListener { binding.candleChart.resetView() }
        binding.tvChartHint.setOnClickListener { binding.candleChart.resetView() }

        AppRepository.selectedSymbol.observe(viewLifecycleOwner) { symbol ->
            syncSymbolChips(symbol)
            val spec = MarketData.spec(symbol)
            binding.tvChartSymbol.text = symbol
            binding.tvChartDesc.text = spec.description
            render(force = true)
        }
        AppRepository.selectedTimeframe.observe(viewLifecycleOwner) { tf ->
            renderTimeframeButtons(tf)
            render(force = true)
        }
        AppRepository.chartStrategy.observe(viewLifecycleOwner) { id ->
            syncStrategySelection(id)
            render(force = true)
        }
        AppRepository.configs.observe(viewLifecycleOwner) { render(force = true) }
        AppRepository.settings.observe(viewLifecycleOwner) { settings ->
            syncHtfSwitch(settings)
            render(force = true)
        }
        AppRepository.quotes.observe(viewLifecycleOwner) { quotes ->
            val symbol = AppRepository.selectedSymbol.value ?: "XAUUSD"
            quotes.firstOrNull { it.symbol == symbol }?.let { quote ->
                val spec = MarketData.spec(symbol)
                binding.tvChartPrice.text = spec.formatPrice(quote.mid)
                binding.tvChartChange.text = Ui.signedPct(quote.changePct)
                binding.tvChartChange.setTextColor(Ui.profitColor(requireContext(), quote.changePct))
            }
            render(force = false)
        }
        AppRepository.positions.observe(viewLifecycleOwner) {
            if (_binding == null) return@observe
            val symbol = AppRepository.selectedSymbol.value ?: "XAUUSD"
            binding.candleChart.setPositionLevels(positionLevelsFor(symbol))
        }

        val initial = MarketData.spec(AppRepository.selectedSymbol.value ?: "XAUUSD")
        binding.tvChartSymbol.text = initial.symbol
        binding.tvChartDesc.text = initial.description
        render(force = true)
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------
    private fun setupSymbolChips() {
        binding.symbolChips.removeAllViews()
        for (spec in MarketData.symbols) {
            val chip = Ui.chip(binding.symbolChips, spec.symbol, spec.symbol == AppRepository.selectedSymbol.value)
            chip.setOnClickListener { AppRepository.selectSymbol(spec.symbol) }
            binding.symbolChips.addView(chip)
        }
        symbolChipsBuilt = true
    }

    private fun syncSymbolChips(symbol: String) {
        if (!symbolChipsBuilt) return
        for (i in 0 until binding.symbolChips.childCount) {
            val chip = binding.symbolChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.text.toString() == symbol
        }
    }

    private fun setupTimeframeButtons() {
        renderTimeframeButtons(AppRepository.selectedTimeframe.value ?: Timeframe.H1)
    }

    private fun renderTimeframeButtons(selected: Timeframe) {
        binding.timeframeContainer.removeAllViews()
        for (tf in Timeframe.entries) {
            val active = tf == selected
            val tv = TextView(requireContext()).apply {
                text = tf.label
                textSize = 11f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (active) R.color.primary else R.color.text_dim,
                    ),
                )
                setBackgroundResource(
                    if (active) R.drawable.bg_timeframe_selected else R.drawable.bg_timeframe_unselected,
                )
                setPadding(
                    Ui.dp(requireContext(), 11f),
                    Ui.dp(requireContext(), 6f),
                    Ui.dp(requireContext(), 11f),
                    Ui.dp(requireContext(), 6f),
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(requireContext(), 4f) }
                setOnClickListener { AppRepository.selectTimeframe(tf) }
            }
            binding.timeframeContainer.addView(tv)
        }
    }

    private fun setupOverlays() {
        binding.overlayChips.removeAllViews()
        data class Overlay(val label: String, val checked: Boolean, val apply: (Boolean) -> Unit)

        val items = listOf(
            Overlay("OB", true) { overlays = overlays.copy(ob = it) },
            Overlay("FVG", true) { overlays = overlays.copy(fvg = it) },
            Overlay("BOS/CHoCH", true) { overlays = overlays.copy(structure = it) },
            Overlay("Liquidity", true) { overlays = overlays.copy(liquidity = it) },
            Overlay("OTE", true) { overlays = overlays.copy(ote = it) },
        )
        for (item in items) {
            val chip = Ui.chip(binding.overlayChips, item.label, item.checked)
            chip.setOnCheckedChangeListener { _, checked ->
                item.apply(checked)
                binding.candleChart.setOverlays(overlays)
            }
            binding.overlayChips.addView(chip)
        }
    }

    private fun setupStrategyDropdown() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, strategyLabels)
        binding.ddChartStrategy.setAdapter(adapter)
        binding.ddChartStrategy.setOnItemClickListener { _, _, position, _ ->
            strategyIds.getOrNull(position)?.let { AppRepository.selectChartStrategy(it) }
        }
        syncStrategySelection(AppRepository.chartStrategy.value ?: MultiTimeframe.AUTO)
    }

    private fun syncStrategySelection(id: String) {
        val index = strategyIds.indexOf(id).takeIf { it >= 0 } ?: 0
        if (binding.ddChartStrategy.text?.toString() != strategyLabels[index]) {
            binding.ddChartStrategy.setText(strategyLabels[index], false)
        }
    }

    private fun setupHtfSwitch() {
        binding.swChartHtf.setOnCheckedChangeListener { _, checked ->
            val settings = AppRepository.settings.value ?: AppSettings()
            if (settings.autoHtfFilter == checked) return@setOnCheckedChangeListener
            AppRepository.saveSettings(settings.copy(autoHtfFilter = checked))
        }
    }

    private fun syncHtfSwitch(settings: AppSettings) {
        binding.swChartHtf.setOnCheckedChangeListener(null)
        binding.swChartHtf.isChecked = settings.autoHtfFilter
        setupHtfSwitch()
    }

    // ------------------------------------------------------------------
    // Analysis
    // ------------------------------------------------------------------
    private fun render(force: Boolean) {
        if (_binding == null || !isAdded) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAnalyzeAt < 1_500) return
        lastAnalyzeAt = now

        val symbol = AppRepository.selectedSymbol.value ?: "XAUUSD"
        val tf = AppRepository.selectedTimeframe.value ?: Timeframe.H1
        val settings = AppRepository.settings.value ?: AppSettings()
        val selected = AppRepository.chartStrategy.value ?: MultiTimeframe.AUTO
        val result = AnalysisService.analyze(
            symbol = symbol,
            timeframe = tf,
            limit = 420,
            configs = AppRepository.configs.value.orEmpty(),
            selectedStrategyId = selected,
            htfFilter = settings.autoHtfFilter,
        )
        if (result == null) {
            binding.tvSignalStrategy.text = getString(R.string.no_data)
            binding.tvChartHint.text = getString(R.string.chart_gesture_hint)
            binding.candleChart.setSignal(null)
            return
        }
        analysis = result
        val spec = MarketData.spec(symbol)
        val changedContext = renderedSymbol != symbol || renderedTimeframe != tf
        binding.candleChart.setData(spec, result.candles, result.snapshot, tf, overlays)
        if (changedContext) {
            binding.candleChart.resetView()
            renderedSymbol = symbol
            renderedTimeframe = tf
        }
        binding.candleChart.setSignal(chartSignalOf(result))
        binding.candleChart.setPositionLevels(positionLevelsFor(symbol))
        renderSignal(result, spec)
        renderHtf(result, settings)
    }

    /**
     * The chart overlays the same setup shown in the signal card: entry at the
     * current market price, with the strategy stop and target.
     */
    private fun chartSignalOf(result: ChartAnalysis): ChartSignal? {
        val signal = result.signal ?: return null
        if (result.candles.isEmpty()) return null
        return ChartSignal(
            side = signal.side,
            entry = result.snapshot.price,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit,
            index = result.candles.lastIndex,
        )
    }

    private fun positionLevelsFor(symbol: String): List<ChartPositionLevel> =
        AppRepository.positions.value.orEmpty()
            .filter { it.symbol == symbol }
            .map {
                ChartPositionLevel(
                    side = it.side,
                    lots = it.lots,
                    entry = it.entryPrice,
                    stopLoss = it.stopLoss,
                    takeProfit = it.takeProfit,
                )
            }

    private fun renderSignal(result: ChartAnalysis, spec: com.smclab.autotrade.model.SymbolSpec) {
        val side = result.displaySignalSide
        val badge = when (side) {
            Side.BUY -> Triple(getString(R.string.signal_buy), R.drawable.bg_signal_buy, R.color.buy)
            Side.SELL -> Triple(getString(R.string.signal_sell), R.drawable.bg_signal_sell, R.color.sell)
            else -> Triple(getString(R.string.signal_wait), R.drawable.bg_signal_wait, R.color.text_dim)
        }
        binding.signalBadge.text = badge.first
        binding.signalBadge.setBackgroundResource(badge.second)
        binding.signalBadge.setTextColor(ContextCompat.getColor(requireContext(), badge.third))

        val score = (result.displayScore * 100).roundToInt().coerceIn(0, 100)
        binding.tvSignalScore.text = "$score%"
        binding.signalProgress.progress = score

        val selectedDef = result.selectedStrategyId?.let { StrategyCatalog.byId(it) }
        binding.tvSignalStrategy.text = when {
            selectedDef != null && result.signalStrategy?.id == selectedDef.id ->
                "${selectedDef.name} · đang chọn"
            selectedDef != null -> "${selectedDef.name} · chưa đủ điều kiện"
            result.signalStrategy != null -> "Tự động · ${result.signalStrategy.name}"
            else -> "Confluence tổng hợp"
        }

        val factors = result.displayFactors
        binding.tvFactors.text = if (factors.isEmpty()) {
            "Chưa đủ yếu tố — chờ nến đóng xác nhận."
        } else {
            factors.joinToString("  ·  ")
        }

        val trend = result.snapshot.trend
        binding.tvTrend.text = trend.label
        binding.tvTrend.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (trend) {
                    Trend.BULLISH -> R.color.buy
                    Trend.BEARISH -> R.color.sell
                    Trend.RANGING -> R.color.text_dim
                },
            ),
        )
        binding.tvVix.text = String.format(Locale.US, "%.1f · %s", result.snapshot.vix, result.snapshot.vixLevel.label)
        val session = result.snapshot.sessions.firstOrNull { it.active }?.name ?: "Ngoài phiên"
        binding.tvSession.text = if (result.snapshot.inKillZone) "$session · KZ" else session
        val zone = result.snapshot.zonePercent
        binding.tvZone.text = String.format(
            Locale.US,
            "%.0f%% · %s",
            zone,
            when {
                zone >= 65.0 -> "Premium"
                zone <= 35.0 -> "Discount"
                else -> "Equilibrium"
            },
        )

        val signal = result.signal
        if (signal != null) {
            binding.tvSigEntry.text = spec.formatPrice(result.snapshot.price)
            binding.tvSigSl.text = spec.formatPrice(signal.stopLoss)
            binding.tvSigTp.text = spec.formatPrice(signal.takeProfit)
        } else {
            binding.tvSigEntry.text = "—"
            binding.tvSigSl.text = "—"
            binding.tvSigTp.text = "—"
        }
        binding.tvChartHint.text = if (signal != null) {
            getString(
                R.string.chart_signal_hint,
                signal.side.label,
                spec.formatPrice(result.snapshot.price),
            )
        } else {
            getString(R.string.chart_gesture_hint)
        }
    }

    private fun renderHtf(result: ChartAnalysis, settings: AppSettings) {
        val htf = result.htf
        if (htf == null) {
            binding.tvHtfBias.text = getString(R.string.htf_bias) + " —"
            binding.tvHtfBias.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            binding.tvHtfDesc.text = getString(R.string.htf_none)
            return
        }
        binding.tvHtfBias.text = "${htf.timeframe.label} · ${htf.trend.label}"
        binding.tvHtfBias.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (htf.trend) {
                    Trend.BULLISH -> R.color.buy
                    Trend.BEARISH -> R.color.sell
                    Trend.RANGING -> R.color.text_muted
                },
            ),
        )
        val selected = result.selectedStrategyId?.let { StrategyCatalog.byId(it) }
        val strategyGated = selected?.let { AppRepository.configFor(it.id).useHtf } ?: true
        binding.tvHtfDesc.text = when {
            !settings.autoHtfFilter -> getString(R.string.htf_off)
            !strategyGated -> "Chiến lược này đang tắt xác nhận HTF"
            !htf.isDirectional -> "${htf.timeframe.label} đi ngang — không chặn tín hiệu"
            else -> "Chỉ vào lệnh thuận xu hướng ${htf.timeframe.label}"
        }
    }

    private fun openOrder(side: Side) {
        val symbol = AppRepository.selectedSymbol.value ?: "XAUUSD"
        val signal = analysis?.signal?.takeIf { it.side == side }
        val strategyId = analysis?.signalStrategy?.id?.takeIf { signal != null }
        OrderTicketDialog.newInstance(
            symbol = symbol,
            side = side,
            strategyId = strategyId,
            stopLoss = signal?.stopLoss ?: 0.0,
            takeProfit = signal?.takeProfit ?: 0.0,
        ).show(parentFragmentManager, "order_ticket")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
