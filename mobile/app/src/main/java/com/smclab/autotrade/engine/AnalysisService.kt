package com.smclab.autotrade.engine

import com.smclab.autotrade.model.Candle
import com.smclab.autotrade.model.ConfluenceResult
import com.smclab.autotrade.model.SmcSnapshot
import com.smclab.autotrade.model.StrategyConfig
import com.smclab.autotrade.model.StrategyDef
import com.smclab.autotrade.model.StrategySignal
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.model.Trend

/** Higher-timeframe context used for multi-timeframe confirmation. */
data class HtfContext(
    val timeframe: Timeframe,
    val series: SmcSeries,
    val snapshot: SmcSnapshot,
    val trend: Trend,
) {
    val isDirectional: Boolean get() = trend != Trend.RANGING
}

/** Bundle of everything the chart screen needs for one symbol/timeframe. */
data class ChartAnalysis(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    val series: SmcSeries,
    val snapshot: SmcSnapshot,
    val confluence: ConfluenceResult,
    val signal: StrategySignal?,
    val signalStrategy: StrategyDef?,
    val htf: HtfContext? = null,
    val selectedStrategyId: String? = null,
) {
    val displaySignalSide get() = signal?.side ?: confluence.side
    val displayScore get() = signal?.score ?: confluence.score
    val displayFactors get() = signal?.factors ?: confluence.factors.map { it.first }
    val htfTimeframe: Timeframe? get() = htf?.timeframe
    val htfTrend: Trend? get() = htf?.trend
}

/**
 * Builds the higher-timeframe series for a chart timeframe:
 * M5→M15, M15→H1, H1→H4, H4→D1. D1 has no higher timeframe in this app.
 */
object MultiTimeframe {

    const val AUTO = "AUTO"

    fun context(
        symbol: String,
        ltf: Timeframe,
        limit: Int = 360,
        nowMillis: Long = System.currentTimeMillis(),
    ): HtfContext? {
        val htf = ltf.higher() ?: return null
        val closed = MarketData.closedCandles(symbol, htf, limit)
        if (closed.size < 80) return null
        val series = SmcSeries(closed, MarketData.spec(symbol), htf)
        if (!series.isReady) return null
        val snapshot = series.snapshotAt(series.size - 1, nowMillis)
        return HtfContext(htf, series, snapshot, snapshot.trend)
    }
}

object AnalysisService {

    fun analyze(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 420,
        configs: List<StrategyConfig> = emptyList(),
        selectedStrategyId: String? = null,
        htfFilter: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChartAnalysis? {
        val spec = MarketData.spec(symbol)
        val closed = MarketData.closedCandles(symbol, timeframe, limit)
        if (closed.size < 80) return null
        val display = MarketData.candles(symbol, timeframe, limit)
        val series = SmcSeries(closed, spec, timeframe)
        val index = series.size - 1
        val snapshot = series.snapshotAt(index, nowMillis)
        val htf = MultiTimeframe.context(symbol, timeframe, limit = 360, nowMillis = nowMillis)
        val htfTrend = if (htfFilter) htf?.trend else null
        val confluence = StrategyEvaluator.confluence(series, snapshot, htfTrend, htfFilter)

        val selected = selectedStrategyId?.takeIf { it.isNotBlank() && it != MultiTimeframe.AUTO }
        val candidates: List<StrategyConfig> = if (selected != null) {
            listOfNotNull(
                configs.firstOrNull { it.strategyId == selected }
                    ?: StrategyCatalog.byId(selected)?.let { StrategyCatalog.defaultConfig(it) },
            )
        } else {
            configs.filter { it.enabled }
        }

        var bestSignal: StrategySignal? = null
        var bestDef: StrategyDef? = null
        for (config in candidates) {
            val def = StrategyCatalog.byId(config.strategyId) ?: continue
            val signal = StrategyEvaluator.evaluate(
                def = def,
                series = series,
                snapshot = snapshot,
                config = config,
                htfTrend = htfTrend,
            ) ?: continue
            if (bestSignal == null || signal.score > bestSignal.score) {
                bestSignal = signal
                bestDef = def
            }
        }

        return ChartAnalysis(
            symbol = symbol,
            timeframe = timeframe,
            candles = display,
            series = series,
            snapshot = snapshot,
            confluence = confluence,
            signal = bestSignal,
            signalStrategy = bestDef,
            htf = htf,
            selectedStrategyId = selected,
        )
    }
}
