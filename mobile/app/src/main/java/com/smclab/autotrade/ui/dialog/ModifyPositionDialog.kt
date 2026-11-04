package com.smclab.autotrade.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.DialogModifyPositionBinding
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.model.Side
import java.util.Locale

class ModifyPositionDialog : BottomSheetDialogFragment() {

    private var _binding: DialogModifyPositionBinding? = null
    private val binding get() = _binding!!

    private var positionId: Long = -1L
    private var symbol: String = "XAUUSD"
    private var side: Side = Side.BUY
    private var currentPrice: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        positionId = arguments?.getLong(ARG_ID, -1L) ?: -1L
        symbol = arguments?.getString(ARG_SYMBOL) ?: "XAUUSD"
        side = if (arguments?.getString(ARG_SIDE) == Side.SELL.name) Side.SELL else Side.BUY
        currentPrice = arguments?.getDouble(ARG_PRICE, 0.0) ?: 0.0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogModifyPositionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val spec = MarketData.spec(symbol)
        binding.tvModifySymbol.text = "${side.label} · $symbol · ${spec.description}"
        binding.tvModifyCurrent.text = spec.formatPrice(currentPrice)

        val stopLoss = arguments?.getDouble(ARG_SL, 0.0) ?: 0.0
        val takeProfit = arguments?.getDouble(ARG_TP, 0.0) ?: 0.0
        if (stopLoss > 0) binding.etModifySl.setText(format(spec.digits, stopLoss))
        if (takeProfit > 0) binding.etModifyTp.setText(format(spec.digits, takeProfit))

        binding.btnModifyCancel.setOnClickListener { dismiss() }
        binding.btnModifySave.setOnClickListener { save() }
    }

    private fun save() {
        val spec = MarketData.spec(symbol)
        val slText = binding.etModifySl.text?.toString()?.trim().orEmpty()
        val tpText = binding.etModifyTp.text?.toString()?.trim().orEmpty()
        val stopLoss = if (slText.isEmpty()) 0.0 else slText.toDoubleOrNull()
        val takeProfit = if (tpText.isEmpty()) 0.0 else tpText.toDoubleOrNull()
        if (stopLoss == null || takeProfit == null) {
            showError("Giá SL/TP không hợp lệ")
            return
        }
        val error = AppRepository.modifyPosition(positionId, stopLoss, takeProfit)
        if (error != null) {
            showError(error)
            return
        }
        AppRepository.notify("Đã cập nhật $symbol · ${spec.formatPrice(stopLoss)} / ${spec.formatPrice(takeProfit)}")
        dismiss()
    }

    private fun showError(text: String) {
        binding.tvModifyError.text = text
        binding.tvModifyError.visibility = View.VISIBLE
    }

    private fun format(digits: Int, value: Double): String =
        String.format(Locale.US, "%.${digits}f", value)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_SYMBOL = "symbol"
        private const val ARG_SIDE = "side"
        private const val ARG_PRICE = "price"
        private const val ARG_SL = "sl"
        private const val ARG_TP = "tp"

        fun newInstance(
            positionId: Long,
            symbol: String,
            side: Side,
            currentPrice: Double,
            stopLoss: Double,
            takeProfit: Double,
        ): ModifyPositionDialog = ModifyPositionDialog().apply {
            arguments = Bundle().apply {
                putLong(ARG_ID, positionId)
                putString(ARG_SYMBOL, symbol)
                putString(ARG_SIDE, side.name)
                putDouble(ARG_PRICE, currentPrice)
                putDouble(ARG_SL, stopLoss)
                putDouble(ARG_TP, takeProfit)
            }
        }
    }
}
