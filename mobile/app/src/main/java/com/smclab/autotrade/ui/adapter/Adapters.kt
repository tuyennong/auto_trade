package com.smclab.autotrade.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smclab.autotrade.R
import com.smclab.autotrade.data.AppRepository
import com.smclab.autotrade.databinding.ItemBacktestTradeBinding
import com.smclab.autotrade.databinding.ItemHistoryBinding
import com.smclab.autotrade.databinding.ItemMarketQuoteBinding
import com.smclab.autotrade.databinding.ItemPositionBinding
import com.smclab.autotrade.databinding.ItemStrategyBinding
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.model.BacktestTrade
import com.smclab.autotrade.model.Position
import com.smclab.autotrade.model.Quote
import com.smclab.autotrade.model.StrategyConfig
import com.smclab.autotrade.model.TradeHistory
import com.smclab.autotrade.ui.Ui
import java.util.Locale

class MarketAdapter(
    private val onClick: (String) -> Unit,
) : ListAdapter<Quote, MarketAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Quote>() {
            override fun areItemsTheSame(oldItem: Quote, newItem: Quote) = oldItem.symbol == newItem.symbol
            override fun areContentsTheSame(oldItem: Quote, newItem: Quote) = oldItem == newItem
        }
    }

    inner class VH(val binding: ItemMarketQuoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMarketQuoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val quote = getItem(position)
        val b = holder.binding
        val spec = MarketData.spec(quote.symbol)
        b.tvSymbol.text = quote.symbol
        b.tvDesc.text = spec.description
        b.tvBid.text = spec.formatPrice(quote.bid)
        b.tvAsk.text = spec.formatPrice(quote.ask)
        val up = quote.changePct >= 0
        val color = ContextCompat.getColor(
            holder.itemView.context,
            if (up) R.color.buy else R.color.sell,
        )
        b.tvChange.text = Ui.signedPct(quote.changePct)
        b.tvChange.setTextColor(color)
        b.tvChangeArrow.text = if (up) "▲" else "▼"
        b.tvChangeArrow.setTextColor(color)
        b.sparkline.setValues(quote.sparkline, up)
        b.root.setOnClickListener { onClick(quote.symbol) }
    }
}

class StrategyAdapter(
    private val onToggle: (String, Boolean) -> Unit,
    private val onConfigure: (String) -> Unit,
) : ListAdapter<StrategyConfig, StrategyAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StrategyConfig>() {
            override fun areItemsTheSame(oldItem: StrategyConfig, newItem: StrategyConfig) =
                oldItem.strategyId == newItem.strategyId

            override fun areContentsTheSame(oldItem: StrategyConfig, newItem: StrategyConfig) =
                oldItem == newItem
        }
    }

    inner class VH(val binding: ItemStrategyBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStrategyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val config = getItem(position)
        val def = StrategyCatalog.byId(config.strategyId) ?: return
        val b = holder.binding
        b.tvName.text = def.name
        b.tvCategory.text = def.category
        b.tvTagline.text = def.tagline
        b.tvDesc.text = def.description
        b.tvConfluence.text = "≥ ${def.minConfluence} yếu tố"
        b.tvRr.text = "R:R 1:${trim(config.riskReward)}"
        b.tvRisk.text = "Risk ${trim(config.riskPercent)}%"
        b.tvHtf.text = if (config.useHtf) "HTF ✓" else "HTF –"
        b.tvHtf.alpha = if (config.useHtf) 1f else 0.55f
        b.swEnabled.setOnCheckedChangeListener(null)
        b.swEnabled.isChecked = config.enabled
        b.swEnabled.setOnCheckedChangeListener { _, checked -> onToggle(config.strategyId, checked) }
        b.tvConfigure.setOnClickListener { onConfigure(config.strategyId) }
        b.root.setOnClickListener { onConfigure(config.strategyId) }
        b.tvName.alpha = if (config.enabled) 1f else 0.65f
    }

    private fun trim(value: Double): String =
        if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
}

class PositionAdapter(
    private val onModify: (Position) -> Unit,
    private val onClose: (Position) -> Unit,
) : ListAdapter<Position, PositionAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Position>() {
            override fun areItemsTheSame(oldItem: Position, newItem: Position) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Position, newItem: Position) = oldItem == newItem
        }
    }

    inner class VH(val binding: ItemPositionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPositionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context
        val spec = MarketData.spec(p.symbol)
        Ui.sideBadge(context, b.tvSide, p.side)
        b.tvSymbol.text = p.symbol
        b.tvLots.text = String.format(Locale.US, "%.2f lot", p.lots)
        b.tvProfit.text = Ui.signedMoney(p.profit)
        b.tvProfit.setTextColor(Ui.profitColor(context, p.profit))
        b.tvEntry.text = spec.formatPrice(p.entryPrice)
        b.tvCurrent.text = spec.formatPrice(p.currentPrice)
        b.tvSl.text = if (p.stopLoss > 0) spec.formatPrice(p.stopLoss) else "—"
        b.tvTp.text = if (p.takeProfit > 0) spec.formatPrice(p.takeProfit) else "—"
        val def = p.strategyId?.let { StrategyCatalog.byId(it) }
        b.tvComment.text = def?.name ?: p.comment.ifBlank { "Manual" }
        b.btnModify.setOnClickListener { onModify(p) }
        b.btnClose.setOnClickListener { onClose(p) }
    }
}

class HistoryAdapter : ListAdapter<TradeHistory, HistoryAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TradeHistory>() {
            override fun areItemsTheSame(oldItem: TradeHistory, newItem: TradeHistory) =
                oldItem.id == newItem.id && oldItem.closeTime == newItem.closeTime

            override fun areContentsTheSame(oldItem: TradeHistory, newItem: TradeHistory) =
                oldItem == newItem
        }
    }

    inner class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context
        val spec = MarketData.spec(t.symbol)
        val net = t.profit - t.commission
        Ui.sideBadge(context, b.tvSide, t.side)
        b.tvSymbol.text = t.symbol
        b.tvTime.text = Ui.dateTime(t.closeTime)
        b.tvNet.text = Ui.signedMoney(net)
        b.tvNet.setTextColor(Ui.profitColor(context, net))
        b.tvReason.text = AppRepository.reasonLabel(t.reason)
        b.tvDetail.text = String.format(
            Locale.US,
            "%s → %s · %.2f lot",
            spec.formatPrice(t.entryPrice),
            spec.formatPrice(t.exitPrice),
            t.lots,
        )
    }
}

class BacktestTradeAdapter : ListAdapter<BacktestTrade, BacktestTradeAdapter.VH>(DIFF) {

    var symbol: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BacktestTrade>() {
            override fun areItemsTheSame(oldItem: BacktestTrade, newItem: BacktestTrade) =
                oldItem.index == newItem.index && oldItem.side == newItem.side

            override fun areContentsTheSame(oldItem: BacktestTrade, newItem: BacktestTrade) =
                oldItem == newItem
        }
    }

    inner class VH(val binding: ItemBacktestTradeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBacktestTradeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        val b = holder.binding
        val context = holder.itemView.context
        val spec = MarketData.spec(symbol.ifBlank { "XAUUSD" })
        Ui.sideBadge(context, b.tvSide, t.side)
        b.tvTitle.text = "#${position + 1} · ${t.side.label} · ${AppRepository.reasonLabel(t.reason)}"
        b.tvPrices.text = String.format(
            Locale.US,
            "%s → %s · %.2f lot",
            spec.formatPrice(t.entryPrice),
            spec.formatPrice(t.exitPrice),
            t.lots,
        )
        b.tvNet.text = Ui.signedMoney(t.net)
        b.tvNet.setTextColor(Ui.profitColor(context, t.net))
        b.tvR.text = String.format(Locale.US, "R %+.2f", t.rMultiple)
    }
}
