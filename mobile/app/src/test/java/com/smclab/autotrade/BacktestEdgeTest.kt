package com.smclab.autotrade

import com.smclab.autotrade.engine.BacktestEngine
import com.smclab.autotrade.engine.BacktestRequest
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.model.StrategyDef
import com.smclab.autotrade.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the values the Backtest screen can actually submit:
 * short histories, the maximum history, result metrics and the equity curve.
 */
class BacktestEdgeTest {

    private fun request(
        def: StrategyDef,
        symbol: String = "XAUUSD",
        tf: Timeframe = Timeframe.H1,
        bars: Int = 800,
        balance: Double = 10_000.0,
        risk: Double = 1.0,
        rr: Double = 2.0,
        leverage: Int = 200,
        htf: Timeframe? = null,
    ) = BacktestRequest(
        strategy = def,
        config = StrategyCatalog.defaultConfig(def),
        symbol = symbol,
        timeframe = tf,
        bars = bars,
        initialBalance = balance,
        riskPercent = risk,
        riskReward = rr,
        commissionPerLot = 7.0,
        leverage = leverage,
        htf = htf,
    )

    @Test
    fun `edge parameter combinations stay finite`() {
        for (bars in listOf(120, 121, 800, 1_800)) {
            for (tf in Timeframe.entries) {
                for (def in StrategyCatalog.all) {
                    val result = BacktestEngine.run(
                        request(def, tf = tf, bars = bars, htf = tf.higher()),
                    )
                    val metrics = result.metrics
                    val values = listOf(
                        metrics.netProfit,
                        metrics.returnPct,
                        metrics.winRate,
                        metrics.profitFactor,
                        metrics.maxDrawdownPct,
                        metrics.maxDrawdownAbs,
                        metrics.expectancy,
                        metrics.avgR,
                        metrics.sharpe,
                        metrics.finalBalance,
                    )
                    val context = "$bars bars / ${tf.label} / ${def.id}"
                    assertTrue("$context has non-finite metrics: $values", values.all { it.isFinite() })
                    assertTrue("$context has no equity curve", result.equityCurve.size >= 2)
                    assertTrue(
                        "$context has non-finite equity",
                        result.equityCurve.all { it.isFinite() },
                    )
                    assertEquals(
                        "$context final balance",
                        metrics.finalBalance,
                        result.equityCurve.last(),
                        1e-6,
                    )
                }
            }
        }
    }

    @Test
    fun `every symbol completes at the minimum history`() {
        val def = StrategyCatalog.all.first()
        for (spec in MarketData.symbols) {
            val result = BacktestEngine.run(
                request(def, symbol = spec.symbol, bars = 120, htf = Timeframe.H4),
            )
            assertTrue("${spec.symbol} equity", result.equityCurve.size >= 2)
            assertTrue("${spec.symbol} finite", result.equityCurve.all { it.isFinite() })
        }
    }

    @Test
    fun `default screen request produces a complete result`() {
        val def = StrategyCatalog.all.first()
        val result = BacktestEngine.run(request(def, htf = Timeframe.H4))
        assertEquals(def.id, result.strategyId)
        assertTrue("default request had no trades", result.metrics.trades > 0)
        assertTrue("equity curve must cover the run", result.equityCurve.size >= 2)
        assertEquals(result.metrics.finalBalance, result.equityCurve.last(), 1e-6)
    }

    @Test
    fun `leverage changes the margin cap without breaking metrics`() {
        val def = StrategyCatalog.all.first()
        for (leverage in listOf(1, 50, 200, 2_000)) {
            val result = BacktestEngine.run(request(def, leverage = leverage))
            assertTrue("leverage $leverage", result.metrics.finalBalance.isFinite())
            assertTrue("leverage $leverage equity", result.equityCurve.all { it.isFinite() })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive balance is rejected`() {
        BacktestEngine.run(request(StrategyCatalog.all.first(), balance = 0.0))
    }
}
