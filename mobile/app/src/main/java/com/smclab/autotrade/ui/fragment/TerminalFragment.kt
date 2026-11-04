package com.smclab.autotrade.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.FragmentTerminalBinding
import com.smclab.autotrade.engine.AnalysisService
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.model.Account
import com.smclab.autotrade.model.Position
import com.smclab.autotrade.model.Side
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.ui.Ui
import com.smclab.autotrade.ui.adapter.HistoryAdapter
import com.smclab.autotrade.ui.adapter.PositionAdapter
import com.smclab.autotrade.ui.dialog.ModifyPositionDialog
import com.smclab.autotrade.ui.dialog.OrderTicketDialog
import java.util.Locale

class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private val positionAdapter = PositionAdapter(
        onModify = { position -> showModify(position) },
        onClose = { position -> confirmClose(position) },
    )
    private val historyAdapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvPositions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPositions.adapter = positionAdapter
        binding.rvPositions.itemAnimator = null
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.itemAnimator = null

        binding.btnNewOrder.setOnClickListener { openNewOrder() }

        AppRepository.account.observe(viewLifecycleOwner) { renderAccount(it) }
        AppRepository.positions.observe(viewLifecycleOwner) { positions ->
            positionAdapter.submitList(positions)
            binding.tvPositionCount.text = "${positions.size} mở"
            binding.tvNoPositions.visibility = if (positions.isEmpty()) View.VISIBLE else View.GONE
        }
        AppRepository.history.observe(viewLifecycleOwner) { history ->
            historyAdapter.submitList(history.take(60))
            binding.tvHistoryCount.text = "${history.size} lệnh"
            binding.tvNoHistory.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun renderAccount(account: Account) {
        binding.tvTerminalBalance.text = Ui.money(account.balance)
        binding.tvTerminalFloating.text = Ui.signedMoney(account.floatingPl)
        binding.tvTerminalFloating.setTextColor(Ui.profitColor(requireContext(), account.floatingPl))
        binding.tvTerminalEquity.text = Ui.money(account.equity)
        binding.tvTerminalMargin.text = Ui.money(account.margin)
        binding.tvTerminalLevel.text = if (account.margin > 0.0) {
            String.format(Locale.US, "%.1f%%", account.marginLevel)
        } else {
            "—"
        }
        binding.tvTerminalLevel.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (account.margin > 0.0 && account.marginLevel < 100.0) R.color.warning else R.color.text_dim,
            ),
        )
    }

    private fun openNewOrder() {
        val symbol = AppRepository.selectedSymbol.value ?: "XAUUSD"
        val tf = AppRepository.selectedTimeframe.value ?: Timeframe.H1
        val analysis = AnalysisService.analyze(
            symbol = symbol,
            timeframe = tf,
            limit = 360,
            configs = AppRepository.configs.value.orEmpty(),
            htfFilter = AppRepository.settings.value?.autoHtfFilter ?: true,
        )
        val side = if (analysis?.displaySignalSide == Side.SELL) Side.SELL else Side.BUY
        val signal = analysis?.signal?.takeIf { it.side == side }
        OrderTicketDialog.newInstance(
            symbol = symbol,
            side = side,
            strategyId = analysis?.signalStrategy?.id?.takeIf { signal != null },
            stopLoss = signal?.stopLoss ?: 0.0,
            takeProfit = signal?.takeProfit ?: 0.0,
        ).show(parentFragmentManager, "order_ticket")
    }

    private fun showModify(position: Position) {
        ModifyPositionDialog.newInstance(
            positionId = position.id,
            symbol = position.symbol,
            side = position.side,
            currentPrice = position.currentPrice,
            stopLoss = position.stopLoss,
            takeProfit = position.takeProfit,
        ).show(parentFragmentManager, "modify_position")
    }

    private fun confirmClose(position: Position) {
        val spec = MarketData.spec(position.symbol)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.close_position))
            .setMessage(
                "${position.side.label} ${position.symbol} · ${String.format(Locale.US, "%.2f", position.lots)} lot\n" +
                    "${spec.formatPrice(position.entryPrice)} → ${spec.formatPrice(position.currentPrice)} · " +
                    Ui.signedMoney(position.profit),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.close_position) { _, _ ->
                AppRepository.closePosition(position.id)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvPositions.adapter = null
        binding.rvHistory.adapter = null
        _binding = null
    }
}
