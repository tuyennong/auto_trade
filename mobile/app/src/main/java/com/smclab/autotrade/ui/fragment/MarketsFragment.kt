package com.smclab.autotrade.ui.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.smclab.autotrade.MainActivity
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.FragmentMarketsBinding
import com.smclab.autotrade.model.Account
import com.smclab.autotrade.model.SessionState
import com.smclab.autotrade.ui.Ui
import com.smclab.autotrade.ui.adapter.MarketAdapter
import java.time.Instant
import java.time.ZoneOffset

class MarketsFragment : Fragment() {

    private var _binding: FragmentMarketsBinding? = null
    private val binding get() = _binding!!
    private val adapter = MarketAdapter { symbol ->
        AppRepository.selectSymbol(symbol)
        (activity as? MainActivity)?.openTab(R.id.nav_chart)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMarketsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvMarkets.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMarkets.adapter = adapter
        binding.rvMarkets.itemAnimator = null

        AppRepository.quotes.observe(viewLifecycleOwner) { adapter.submitList(it) }
        AppRepository.account.observe(viewLifecycleOwner) { renderAccount(it) }
        AppRepository.settings.observe(viewLifecycleOwner) {
            binding.tvLeverage.text = "1:${it.leverage}"
        }
        renderSessions()
    }

    private fun renderAccount(account: Account) {
        binding.tvBalance.text = Ui.money(account.balance)
        binding.tvEquity.text = Ui.money(account.equity)
        binding.tvFloating.text = Ui.signedMoney(account.floatingPl)
        binding.tvFloating.setTextColor(Ui.profitColor(requireContext(), account.floatingPl))
        binding.tvFreeMargin.text = Ui.money(account.freeMargin)
    }

    private fun renderSessions() {
        binding.sessionsContainer.removeAllViews()
        for (state in sessionStates(System.currentTimeMillis())) {
            binding.sessionsContainer.addView(sessionPill(state))
        }
    }

    private fun sessionPill(state: SessionState): TextView {
        val color = when {
            state.inKillZone -> R.color.accent
            state.active -> R.color.primary
            else -> R.color.text_muted
        }
        return TextView(requireContext()).apply {
            text = state.name.uppercase()
            textSize = 9.5f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(requireContext(), color))
            setBackgroundResource(
                if (state.active || state.inKillZone) R.drawable.bg_chip_active else R.drawable.bg_chip,
            )
            setPadding(Ui.dp(requireContext(), 9f), Ui.dp(requireContext(), 4f), Ui.dp(requireContext(), 9f), Ui.dp(requireContext(), 4f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Ui.dp(requireContext(), 6f) }
        }
    }

    private fun sessionStates(millis: Long): List<SessionState> {
        val hour = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).hour
        val asian = hour in 0..7
        val london = hour in 7..15
        val ny = hour in 12..20
        val overlap = hour in 12..15
        return listOf(
            SessionState("Asia", asian, hour in 7..9),
            SessionState("London", london, hour in 7..9),
            SessionState("New York", ny, hour in 12..14),
            SessionState("LDN–NY", overlap, overlap),
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvMarkets.adapter = null
        _binding = null
    }
}
