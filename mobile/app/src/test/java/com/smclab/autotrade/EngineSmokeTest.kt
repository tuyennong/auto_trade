package com.smclab.autotrade

import com.smclab.autotrade.engine.AnalysisService
import com.smclab.autotrade.engine.BacktestEngine
import com.smclab.autotrade.engine.BacktestRequest
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.engine.MultiTimeframe
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSmokeTest {

    @Test
    fun `synthetic candles are deterministic and closed properly`() {
        val first = MarketData.closedCandles("XAUUSD", Timeframe.H1, 300)
        val second = MarketData.closedCandles("XAUUSD", Timeframe.H1, 300)
        assertEquals(300, first.size)
        assertEquals(first, second)
        assertTrue(first.all { it.high >= maxOf(it.open, it.close) })
        assertTrue(first.all { it.low <= minOf(it.open, it.close) })
    }

    @Test
    fun `higher timeframe ladder matches the documented mapping`() {
        assertEquals(Timeframe.M15, Timeframe.M5.higher())
        assertEquals(Timeframe.H1, Timeframe.M15.higher())
        assertEquals(Timeframe.H4, Timeframe.H1.higher())
        assertEquals(Timeframe.D1, Timeframe.H4.higher())
        assertNull(Timeframe.D1.higher())
    }

    @Test
    fun `chart analysis exposes htf context for every timeframe except d1`() {
        for (tf in Timeframe.entries) {
            val analysis = AnalysisService.analyze(
                symbol = "XAUUSD",
                timeframe = tf,
                limit = 420,
                configs = StrategyCatalog.all.map { StrategyCatalog.defaultConfig(it) },
                htfFilter = true,
            )
            assertNotNull("analysis for $tf", analysis)
            if (tf == Timeframe.D1) {
                assertNull("D1 must not have HTF", analysis!!.htf)
            } else {
                assertNotNull("HTF for $tf", analysis!!.htf)
                assertEquals(tf.higher(), analysis.htfTimeframe)
            }
        }
    }

    @Test
    fun `analysis with auto and explicit strategy selection stays consistent`() {
        val configs = StrategyCatalog.all.map { StrategyCatalog.defaultConfig(it) }
        val auto = AnalysisService.analyze(
            "EURUSD", Timeframe.M15, 420, configs, MultiTimeframe.AUTO, htfFilter = true,
        )
        assertNotNull(auto)
        assertNull(auto!!.selectedStrategyId)

        for (def in StrategyCatalog.all) {
            val analysis = AnalysisService.analyze(
                "EURUSD", Timeframe.M15, 420, configs, def.id, htfFilter = true,
            )
            assertNotNull("analysis for ${def.id}", analysis)
            assertEquals(def.id, analysis!!.selectedStrategyId)
            val signal = analysis.signal
            if (signal != null) {
                assertTrue(signal.score > 0.0)
                assertTrue(signal.stopLoss > 0.0)
                assertTrue(signal.takeProfit > 0.0)
            }
        }
    }

    @Test
    fun `every symbol analyzes and backtests without exceptions`() {
        val def = StrategyCatalog.all.first()
        for (spec in MarketData.symbols) {
            val analysis = AnalysisService.analyze(
                symbol = spec.symbol,
                timeframe = Timeframe.H1,
                limit = 420,
                configs = StrategyCatalog.all.map { StrategyCatalog.defaultConfig(it) },
                htfFilter = true,
            )
            assertNotNull("analysis for ${spec.symbol}", analysis)
            val result = BacktestEngine.run(
                BacktestRequest(
                    strategy = def,
                    config = StrategyCatalog.defaultConfig(def),
                    symbol = spec.symbol,
                    timeframe = Timeframe.H1,
                    bars = 320,
                    initialBalance = 10_000.0,
                    riskPercent = 1.0,
                    riskReward = 2.0,
                    commissionPerLot = 7.0,
                    htf = Timeframe.H4,
                ),
            )
            assertEquals(spec.symbol, result.symbol)
            assertTrue("equity for ${spec.symbol}", result.equityCurve.all { it.isFinite() })
        }
    }

    @Test
    fun `every timeframe and strategy combination completes with finite metrics`() {
        for (tf in Timeframe.entries) {
            for (def in StrategyCatalog.all) {
                val result = BacktestEngine.run(
                    BacktestRequest(
                        strategy = def,
                        config = StrategyCatalog.defaultConfig(def),
                        symbol = "XAUUSD",
                        timeframe = tf,
                        bars = 800,
                        initialBalance = 10_000.0,
                        riskPercent = 1.0,
                        riskReward = 2.0,
                        commissionPerLot = 7.0,
                        htf = null,
                    ),
                )
                val context = "${tf.label}/${def.id}"
                assertEquals(context, def.id, result.strategyId)
                assertEquals(context, tf, result.timeframe)
                assertTrue("$context metrics", result.metrics.trades >= 0)
                assertTrue("$context equity", result.equityCurve.all { it.isFinite() })
            }
        }
    }

    @Test
    fun `backtest runs with and without htf confirmation`() {
        for (def in StrategyCatalog.all) {
            val base = BacktestRequest(
                strategy = def,
                config = StrategyCatalog.defaultConfig(def),
                symbol = "XAUUSD",
                timeframe = Timeframe.H1,
                bars = 400,
                initialBalance = 10_000.0,
                riskPercent = 1.0,
                riskReward = 2.0,
                commissionPerLot = 7.0,
            )
            for (htf in listOf(null, Timeframe.H4)) {
                val result = BacktestEngine.run(base.copy(htf = htf))
                assertEquals(def.id, result.strategyId)
                assertEquals(htf, result.htfTimeframe)
                assertTrue(result.metrics.trades >= 0)
                assertTrue(result.metrics.winRate in 0.0..100.0)
                assertTrue(result.metrics.maxDrawdownPct >= 0.0)
                assertTrue(result.equityCurve.isNotEmpty())
            }
        }
    }
}
