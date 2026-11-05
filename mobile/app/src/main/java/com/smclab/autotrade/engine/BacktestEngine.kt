package com.smclab.autotrade.engine

import com.smclab.autotrade.model.BacktestMetrics
import com.smclab.autotrade.model.BacktestResult
import com.smclab.autotrade.model.BacktestTrade
import com.smclab.autotrade.model.Candle
import com.smclab.autotrade.model.CloseReason
import com.smclab.autotrade.model.Side
import com.smclab.autotrade.model.StrategyConfig
import com.smclab.autotrade.model.StrategyDef
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.model.Trend
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BacktestRequest(
    val strategy: StrategyDef,
    val config: StrategyConfig,
    val symbol: String,
    val timeframe: Timeframe,
    val bars: Int,
    val initialBalance: Double,
    val riskPercent: Double,
    val riskReward: Double,
    val commissionPerLot: Double,
    /** Account leverage used for the margin cap; mirrors the demo account setting. */
    val leverage: Int = 200,
    /** Higher timeframe used to confirm entries, or null to disable the filter. */
    val htf: Timeframe? = null,
)

/**
 * Deterministic bar-by-bar backtest.
 *
 * Rules:
 *  - signals are evaluated on closed bars only and filled at the next bar open
 *  - one position at a time, SL/TP are checked intrabar (SL first when both hit)
 *  - position size is derived from the configured risk and the SL distance
 *  - commission is charged once per round turn
 *  - open trades are marked to market for the equity curve
 */
object BacktestEngine {

    fun run(request: BacktestRequest): BacktestResult {
        require(request.initialBalance.isFinite() && request.initialBalance > 0.0) {
            "Số dư ban đầu không hợp lệ."
        }
        val spec = MarketData.spec(request.symbol)
        val requested = request.bars.coerceIn(120, 1_800)
        val candles = MarketData.closedCandles(request.symbol, request.timeframe, requested)
        require(candles.size >= 100) { "Không đủ nến để chạy backtest." }

        val series = SmcSeries(candles, spec, request.timeframe)
        val startIndex = max(60, request.strategy.minBars.coerceAtMost(candles.size - 40))
        // The backtest HTF dropdown is authoritative: choosing an HTF enables
        // confirmation for this run, choosing "Không lọc HTF" disables it.
        val config = request.config.copy(
            riskReward = request.riskReward,
            useHtf = request.htf != null,
        )
        val riskPercent = request.riskPercent.coerceIn(0.1, 5.0)
        val leverage = request.leverage.coerceAtLeast(1)

        // Higher-timeframe series aligned to the same synthetic clock. The trend
        // is cached per HTF bar because evaluating a snapshot is not free.
        val htfSeries = request.htf?.let { htf ->
            val ratio = (request.timeframe.minutes.toDouble() / htf.minutes.toDouble())
                .coerceAtLeast(1.0 / 12.0)
            val htfBars = ((candles.size + 60) * ratio).toInt().coerceIn(120, 1_800)
            val htfCandles = MarketData.closedCandles(request.symbol, htf, htfBars)
            if (htfCandles.size >= 80) SmcSeries(htfCandles, spec, htf) else null
        }
        val htfTrendCache = HashMap<Int, Trend>()

        fun htfTrendAt(ltfTime: Long): Trend? {
            val htf = htfSeries ?: return null
            val htfCandles = htf.candles
            var lo = 0
            var hi = htfCandles.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (htfCandles[mid].time <= ltfTime) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (found < 0) return null
            return htfTrendCache.getOrPut(found) { htf.snapshotAt(found).trend }
        }

        var balance = request.initialBalance
        var peak = balance
        var maxDdAbs = 0.0
        var maxDdPct = 0.0
        var cooldownUntil = startIndex

        val trades = ArrayList<BacktestTrade>()
        val equity = ArrayList<Double>(candles.size - startIndex)
        equity.add(balance)
        var curveIndex = startIndex

        fun appendEquity(value: Double) {
            equity.add(value)
            if (value > peak) peak = value
            val dd = peak - value
            if (dd > maxDdAbs) maxDdAbs = dd
            if (peak > 0.0) maxDdPct = max(maxDdPct, dd / peak * 100.0)
        }

        var i = startIndex
        while (i < candles.size - 1) {
            if (i < cooldownUntil) { i++; continue }
            val snapshot = series.snapshotAt(i)
            val signal = StrategyEvaluator.evaluate(
                request.strategy,
                series,
                snapshot,
                config,
                request.riskReward,
                htfTrendAt(candles[i].time),
            )
            if (signal == null) { i++; continue }

            val fillBar = candles[i + 1]
            val entry = if (signal.side == Side.BUY) {
                fillBar.open + spec.spreadPrice / 2.0
            } else {
                fillBar.open - spec.spreadPrice / 2.0
            }
            if (entry <= 0.0) { i++; continue }

            val stop = signal.stopLoss
            val target = signal.takeProfit
            val stopDistance = abs(entry - stop)
            if (stopDistance <= spec.pipSize * 0.5) { i++; continue }

            val riskAmount = balance * riskPercent / 100.0
            var lots = StrategyEvaluator.suggestedLots(
                balance = balance,
                riskPercent = riskPercent,
                entry = entry,
                stopLoss = stop,
                spec = spec,
                leverage = leverage,
            )
            val marginPerLot = spec.contractSize * entry / leverage.toDouble()
            if (marginPerLot > 0) {
                lots = min(lots, balance * 0.8 / marginPerLot)
            }
            lots = kotlin.math.floor(lots * 100.0) / 100.0
            if (lots < 0.01) { i++; continue }

            var exitIndex = -1
            var exitPrice = 0.0
            var reason = CloseReason.END_OF_TEST
            var j = i + 1
            while (j < candles.size) {
                val bar = candles[j]
                if (signal.side == Side.BUY) {
                    if (bar.low <= stop) {
                        exitIndex = j
                        exitPrice = min(stop, bar.open)
                        reason = CloseReason.STOP_LOSS
                        break
                    }
                    if (bar.high >= target) {
                        exitIndex = j
                        // A gap through the target fills at the open, not at the target.
                        exitPrice = max(target, bar.open)
                        reason = CloseReason.TAKE_PROFIT
                        break
                    }
                } else {
                    if (bar.high >= stop) {
                        exitIndex = j
                        exitPrice = max(stop, bar.open)
                        reason = CloseReason.STOP_LOSS
                        break
                    }
                    if (bar.low <= target) {
                        exitIndex = j
                        // A gap through the target fills at the open, not at the target.
                        exitPrice = min(target, bar.open)
                        reason = CloseReason.TAKE_PROFIT
                        break
                    }
                }
                j++
            }
            if (exitIndex < 0) {
                exitIndex = candles.size - 1
                exitPrice = candles[exitIndex].close
                reason = CloseReason.END_OF_TEST
            }

            val profit = grossProfit(signal.side, entry, exitPrice, lots, spec)
            val commission = lots * request.commissionPerLot
            val net = profit - commission
            trades.add(
                BacktestTrade(
                    index = i,
                    side = signal.side,
                    entryTime = fillBar.time,
                    exitTime = candles[exitIndex].time,
                    entryPrice = entry,
                    exitPrice = exitPrice,
                    lots = lots,
                    stopLoss = stop,
                    takeProfit = target,
                    profit = profit,
                    commission = commission,
                    rMultiple = if (riskAmount > 0) net / riskAmount else 0.0,
                    reason = reason,
                )
            )

            // Mark the position to market on every bar it is open so the
            // equity curve and drawdown include floating P/L, not only the
            // realised result at the exit bar.
            val exitBalance = balance + net
            while (curveIndex < exitIndex) {
                curveIndex++
                val mark = when {
                    curveIndex < i + 1 -> balance
                    curveIndex < exitIndex ->
                        balance + grossProfit(signal.side, entry, candles[curveIndex].close, lots, spec)
                    else -> exitBalance
                }
                appendEquity(mark)
            }
            balance = exitBalance
            cooldownUntil = exitIndex + 3
            i = exitIndex + 2
        }

        // Complete the curve on every remaining bar so each point maps to a
        // candle and the last value always equals the final realised balance.
        val lastIndex = candles.size - 1
        while (curveIndex < lastIndex) {
            curveIndex++
            appendEquity(balance)
        }

        val metrics = metricsOf(
            initialBalance = request.initialBalance,
            finalBalance = balance,
            trades = trades,
            maxDdAbs = maxDdAbs,
            maxDdPct = maxDdPct,
        )

        return BacktestResult(
            id = "bt-" + System.currentTimeMillis().toString(36),
            createdAt = System.currentTimeMillis(),
            strategyId = request.strategy.id,
            strategyName = request.strategy.name,
            symbol = request.symbol,
            timeframe = request.timeframe,
            bars = candles.size,
            riskPercent = riskPercent,
            riskReward = request.riskReward,
            metrics = metrics,
            equityCurve = equity,
            trades = trades,
            params = config.params.toMap(),
            htfTimeframe = request.htf,
        )
    }

    private fun grossProfit(
        side: Side,
        entry: Double,
        exit: Double,
        lots: Double,
        spec: com.smclab.autotrade.model.SymbolSpec,
    ): Double {
        val distance = (exit - entry) * side.sign
        val pips = distance / spec.pipSize
        return pips * MarketData.pipValue(spec, lots, exit)
    }

    private fun metricsOf(
        initialBalance: Double,
        finalBalance: Double,
        trades: List<BacktestTrade>,
        maxDdAbs: Double,
        maxDdPct: Double,
    ): BacktestMetrics {
        val net = trades.map { it.net }
        val wins = net.count { it > 0 }
        val losses = net.count { it <= 0 }
        val grossProfit = net.filter { it > 0 }.sum()
        val grossLoss = abs(net.filter { it < 0 }.sum())
        val netProfit = finalBalance - initialBalance
        val winRate = if (trades.isNotEmpty()) wins.toDouble() / trades.size * 100.0 else 0.0
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) 99.0 else 0.0
        val mean = if (net.isEmpty()) 0.0 else net.average()
        val variance = if (net.size > 1) net.sumOf { (it - mean) * (it - mean) } / (net.size - 1) else 0.0
        val std = sqrt(variance)
        val sharpe = if (std > 1e-9 && net.isNotEmpty()) mean / std * sqrt(net.size.toDouble()) else 0.0

        var consecutive = 0
        var maxConsecutive = 0
        for (value in net) {
            if (value <= 0) {
                consecutive++
                maxConsecutive = max(maxConsecutive, consecutive)
            } else {
                consecutive = 0
            }
        }

        return BacktestMetrics(
            initialBalance = initialBalance,
            finalBalance = finalBalance,
            netProfit = netProfit,
            returnPct = if (initialBalance > 0) netProfit / initialBalance * 100.0 else 0.0,
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            trades = trades.size,
            wins = wins,
            losses = losses,
            winRate = winRate,
            profitFactor = profitFactor,
            maxDrawdownPct = maxDdPct,
            maxDrawdownAbs = maxDdAbs,
            expectancy = if (trades.isNotEmpty()) net.sum() / trades.size else 0.0,
            avgR = if (trades.isNotEmpty()) trades.sumOf { it.rMultiple } / trades.size else 0.0,
            sharpe = sharpe,
            maxConsecutiveLosses = maxConsecutive,
            longTrades = trades.count { it.side == Side.BUY },
            shortTrades = trades.count { it.side == Side.SELL },
        )
    }
}
