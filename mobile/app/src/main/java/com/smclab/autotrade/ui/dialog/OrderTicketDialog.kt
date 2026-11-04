package com.smclab.autotrade.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.DialogOrderTicketBinding
import com.smclab.autotrade.engine.AnalysisService
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.engine.StrategyEvaluator
import com.smclab.autotrade.model.Side
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.ui.Ui
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OrderTicketDialog : BottomSheetDialogFragment() {

    private var _binding: DialogOrderTicketBinding? = null
    private val binding get() = _binding!!

    private var symbol: String = "XAUUSD"
    private var side: Side = Side.BUY
    private var strategyId: String? = null
    private var presetSl: Double = 0.0
    private var presetTp: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        symbol = arguments?.getString(ARG_SYMBOL) ?: AppRepository.selectedSymbol.value ?: "XAUUSD"
        side = if (arguments?.getString(ARG_SIDE) == Side.SELL.name) Side.SELL else Side.BUY
        strategyId = arguments?.getString(ARG_STRATEGY)
        presetSl = arguments?.getDouble(ARG_SL, 0.0) ?: 0.0
        presetTp = arguments?.getDouble(ARG_TP, 0.0) ?: 0.0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogOrderTicketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val spec = MarketData.spec(symbol)
        val tf = AppRepository.selectedTimeframe.value ?: Timeframe.H1
        val analysis = AnalysisService.analyze(
            symbol,
            tf,
            360,
            AppRepository.configs.value.orEmpty(),
        )
        val quote = MarketData.quote(symbol)
        val entry = if (side == Side.BUY) quote.ask else quote.bid

        var slPrice = presetSl
        var tpPrice = presetTp
        if (slPrice <= 0.0 || tpPrice <= 0.0) {
            val levels = analysis?.let {
                StrategyEvaluator.manualLevels(side, it.series, it.snapshot, rr = 2.0)
            }
            if (levels != null) {
                slPrice = levels.first
                tpPrice = levels.second
            } else {
                val dist = spec.pipSize * 120
                slPrice = if (side == Side.BUY) entry - dist else entry + dist
                tpPrice = if (side == Side.BUY) entry + dist * 2 else entry - dist * 2
            }
        }

        binding.tvTicketSymbol.text = "$symbol · ${spec.description} · ${tf.label}"
        binding.tvTicketPrice.text = spec.formatPrice(entry)
        binding.etSlPips.setText(fmtPips(abs(entry - slPrice), spec.pipSize))
        binding.etTpPips.setText(fmtPips(abs(tpPrice - entry), spec.pipSize))

        val risk = AppRepository.settings.value?.defaultRisk ?: 1.0
        val suggested = StrategyEvaluator.suggestedLots(
            AppRepository.account.value?.balance ?: 10_000.0,
            risk,
            entry,
            slPrice,
            spec,
            AppRepository.account.value?.leverage ?: 200,
        )
        binding.etLots.setText(String.format(java.util.Locale.US, "%.2f", max(0.01, suggested)))

        setupQuickLots()
        applySide()
        binding.btnTicketBuy.setOnClickListener { side = Side.BUY; applySide() }
        binding.btnTicketSell.setOnClickListener { side = Side.SELL; applySide() }
        binding.btnTicketClose.setOnClickListener { dismiss() }

        val watcher = binding.etLots.doAfterTextChanged { recalc(analysis?.snapshot?.price ?: entry) }
        binding.etLots.addTextChangedListener(watcher)
        binding.etSlPips.addTextChangedListener(watcher)
        binding.etTpPips.addTextChangedListener(watcher)

        binding.btnPlaceOrder.setOnClickListener { placeOrder() }
        recalc(analysis?.snapshot?.price ?: entry)
    }

    private fun setupQuickLots() {
        listOf("0.01", "0.05", "0.10", "0.25", "0.50", "1.00").forEach { lot ->
            val chip = Ui.quickLot(binding.quickLots, lot)
            chip.setOnClickListener { binding.etLots.setText(lot) }
            binding.quickLots.addView(chip)
        }
    }

    private fun applySide() {
        val buy = side == Side.BUY
        binding.btnTicketBuy.alpha = if (buy) 1f else 0.38f
        binding.btnTicketSell.alpha = if (buy) 0.38f else 1f
        binding.btnPlaceOrder.backgroundTintList = android.content.res.ColorStateList.valueOf(
            requireContext().getColor(if (buy) R.color.buy else R.color.sell),
        )
        binding.btnPlaceOrder.text = if (buy) {
            getString(R.string.place_order) + " · " + getString(R.string.buy)
        } else {
            getString(R.string.place_order) + " · " + getString(R.string.sell)
        }
        recalc(MarketData.quote(symbol).let { if (buy) it.ask else it.bid })
    }

    private fun recalc(fallbackPrice: Double) {
        val spec = MarketData.spec(symbol)
        val quote = MarketData.quote(symbol)
        val entry = if (side == Side.BUY) quote.ask else quote.bid
        val slPips = binding.etSlPips.text?.toString()?.toDoubleOrNull() ?: 0.0
        val tpPips = binding.etTpPips.text?.toString()?.toDoubleOrNull() ?: 0.0
        val lots = binding.etLots.text?.toString()?.toDoubleOrNull() ?: 0.0
        val sl = if (side == Side.BUY) entry - slPips * spec.pipSize else entry + slPips * spec.pipSize
        val tp = if (side == Side.BUY) entry + tpPips * spec.pipSize else entry - tpPips * spec.pipSize

        binding.tilSl.helperText = if (slPips > 0) spec.formatPrice(sl) else null
        binding.tilTp.helperText = if (tpPips > 0) spec.formatPrice(tp) else null

        val pipValue = MarketData.pipValue(spec, max(lots, 0.0), entry)
        val margin = spec.contractSize * entry * max(lots, 0.0) /
            (AppRepository.account.value?.leverage ?: 200).coerceAtLeast(1)
        val riskAmount = slPips * pipValue
        val potential = tpPips * pipValue
        val rr = if (slPips > 0) tpPips / slPips else 0.0

        binding.tvMargin.text = Ui.money(margin)
        binding.tvPipValue.text = Ui.money(pipValue)
        binding.tvRiskAmount.text = Ui.money(riskAmount)
        binding.tvPotential.text = Ui.money(potential)
        binding.tvTicketRr.text = String.format(java.util.Locale.US, "1 : %.2f", rr)
        binding.tvTicketPrice.text = spec.formatPrice(if (quote.mid > 0) quote.mid else fallbackPrice)
        binding.tvTicketError.visibility = View.GONE
    }

    private fun placeOrder() {
        val spec = MarketData.spec(symbol)
        val lots = binding.etLots.text?.toString()?.toDoubleOrNull() ?: 0.0
        val slPips = binding.etSlPips.text?.toString()?.toDoubleOrNull() ?: 0.0
        val tpPips = binding.etTpPips.text?.toString()?.toDoubleOrNull() ?: 0.0
        if (lots < 0.01) {
            showError(getString(R.string.invalid_volume))
            return
        }
        if (slPips <= 0 || tpPips <= 0) {
            showError("SL/TP phải lớn hơn 0")
            return
        }
        val quote = MarketData.quote(symbol)
        val entry = if (side == Side.BUY) quote.ask else quote.bid
        val sl = if (side == Side.BUY) entry - slPips * spec.pipSize else entry + slPips * spec.pipSize
        val tp = if (side == Side.BUY) entry + tpPips * spec.pipSize else entry - tpPips * spec.pipSize
        val roundedLots = kotlin.math.floor(lots * 100.0) / 100.0
        val error = AppRepository.openPosition(
            symbol = symbol,
            side = side,
            lots = roundedLots,
            stopLoss = sl,
            takeProfit = tp,
            strategyId = strategyId,
            comment = "Manual",
        )
        if (error != null) {
            showError(error)
        } else {
            AppRepository.notify("Đã mở ${side.label} $symbol · ${String.format(java.util.Locale.US, "%.2f", roundedLots)} lot")
            dismiss()
        }
    }

    private fun showError(text: String) {
        binding.tvTicketError.text = text
        binding.tvTicketError.visibility = View.VISIBLE
    }

    private fun fmtPips(distance: Double, pip: Double): String =
        String.format(java.util.Locale.US, "%.1f", distance / pip)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SYMBOL = "symbol"
        private const val ARG_SIDE = "side"
        private const val ARG_STRATEGY = "strategy"
        private const val ARG_SL = "sl"
        private const val ARG_TP = "tp"

        fun newInstance(
            symbol: String,
            side: Side,
            strategyId: String? = null,
            stopLoss: Double = 0.0,
            takeProfit: Double = 0.0,
        ): OrderTicketDialog = OrderTicketDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SYMBOL, symbol)
                putString(ARG_SIDE, side.name)
                putString(ARG_STRATEGY, strategyId)
                putDouble(ARG_SL, stopLoss)
                putDouble(ARG_TP, takeProfit)
            }
        }
    }
}
