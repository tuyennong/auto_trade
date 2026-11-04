package com.smclab.autotrade.engine

import com.smclab.autotrade.model.Candle
import com.smclab.autotrade.model.Quote
import com.smclab.autotrade.model.SymbolSpec
import com.smclab.autotrade.model.Timeframe
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline market data source.
 *
 * Prices are deterministic synthetic OHLC series generated from the same
 * symbol/timeframe seed. They are not broker quotes and must never be treated
 * as real market data. The generator only provides a realistic, reproducible
 * surface for the SMC detectors and the backtest engine.
 */
object MarketData {

    val symbols: List<SymbolSpec> = listOf(
        SymbolSpec("XAUUSD", "Vàng / USD", "XAU", "USD", 2348.50, 2, 0.10, 100.0, 2.5, 0.00110, 0.12),
        SymbolSpec("EURUSD", "Euro / USD", "EUR", "USD", 1.08740, 5, 0.0001, 100_000.0, 1.2, 0.00042, 0.02),
        SymbolSpec("GBPUSD", "Bảng Anh / USD", "GBP", "USD", 1.27310, 5, 0.0001, 100_000.0, 1.4, 0.00050, 0.04),
        SymbolSpec("USDJPY", "USD / Yên Nhật", "USD", "JPY", 157.320, 3, 0.01, 100_000.0, 1.0, 0.00048, 0.06),
        SymbolSpec("BTCUSD", "Bitcoin / USD", "BTC", "USD", 67_450.0, 1, 1.0, 1.0, 25.0, 0.00320, 0.18),
        SymbolSpec("US30", "Dow Jones 30", "US30", "USD", 39_480.0, 1, 1.0, 1.0, 3.0, 0.00115, 0.10),
        SymbolSpec("NAS100", "Nasdaq 100", "NAS", "USD", 18_450.0, 1, 1.0, 1.0, 2.0, 0.00155, 0.14),
        SymbolSpec("EURJPY", "Euro / Yên Nhật", "EUR", "JPY", 170.850, 3, 0.01, 100_000.0, 1.8, 0.00052, 0.03),
        SymbolSpec("GBPJPY", "Bảng Anh / Yên", "GBP", "JPY", 200.450, 3, 0.01, 100_000.0, 2.2, 0.00062, 0.05),
        SymbolSpec("AUDUSD", "AUD / USD", "AUD", "USD", 0.66420, 5, 0.0001, 100_000.0, 1.6, 0.00048, 0.01),
    )

    private val bySymbol: Map<String, SymbolSpec> = symbols.associateBy { it.symbol }

    fun spec(symbol: String): SymbolSpec = bySymbol[symbol] ?: symbols.first()
    fun contains(symbol: String): Boolean = bySymbol.containsKey(symbol)

    private val cache = HashMap<String, MutableList<Candle>>()
    private val livePrice = HashMap<String, Double>()
    private val prevDayClose = HashMap<String, Double>()
    private var random = Random(20260209L)

    /**
     * Guards the shared candle cache. The UI ticker runs on the main thread
     * while backtests run on a worker dispatcher; both read/write the cache.
     */
    private val lock = Any()

    private fun key(symbol: String, tf: Timeframe) = "$symbol:${tf.name}"

    /** Returns chronological candles. The final candle is the forming candle. */
    fun candles(symbol: String, tf: Timeframe, limit: Int = 900): List<Candle> = synchronized(lock) {
        val cacheKey = key(symbol, tf)
        val maxNeeded = max(limit, 2_200)
        val list = cache.getOrPut(cacheKey) {
            generate(spec(symbol), tf, maxNeeded, seedOf(symbol, tf), System.currentTimeMillis())
        }
        if (list.size < limit) {
            val extended = generate(spec(symbol), tf, maxNeeded, seedOf(symbol, tf), System.currentTimeMillis())
            cache[cacheKey] = extended
            return@synchronized extended.takeLast(limit)
        }
        return@synchronized list.takeLast(limit)
    }

    /** Closed candles only. Safe for signal evaluation and backtests. */
    fun closedCandles(symbol: String, tf: Timeframe, limit: Int = 600): List<Candle> = synchronized(lock) {
        candles(symbol, tf, limit + 1).dropLast(1).takeLast(limit)
    }

    fun quote(symbol: String): Quote = synchronized(lock) {
        val s = spec(symbol)
        val mid = livePrice.getOrPut(symbol) { candles(symbol, Timeframe.H1, 80).let { it[it.size - 2].close } }
        val half = s.spreadPrice / 2.0
        val spark = candles(symbol, Timeframe.H1, 32).dropLast(1).map { it.close }
        val prev = prevDayClose.getOrPut(symbol) {
            val h1 = candles(symbol, Timeframe.H1, 40)
            if (h1.size > 24) h1[h1.size - 25].close else s.startPrice
        }
        return@synchronized Quote(symbol, mid - half, mid + half, prev, spark)
    }

    fun allQuotes(): List<Quote> = symbols.map { quote(it.symbol) }

    /**
     * Advances the simulated market by one tick. Called once per second by the
     * repository. Updates the forming candle so charts and floating P/L move.
     */
    fun tick(): List<Quote> = synchronized(lock) {
        val now = System.currentTimeMillis()
        symbols.forEach { s ->
            val current = livePrice.getOrPut(s.symbol) {
                candles(s.symbol, Timeframe.H1, 2).first().close
            }
            val session = sessionMultiplier(now)
            val move = gaussian(random) * s.volatility * 0.11 * s.startPrice * session
            val next = (current + move).coerceAtLeast(s.startPrice * 0.2)
            livePrice[s.symbol] = next
            updateFormingCandle(s, next)
        }
        return@synchronized allQuotes()
    }

    private fun updateFormingCandle(spec: SymbolSpec, price: Double) {
        val now = System.currentTimeMillis()
        Timeframe.entries.forEach { tf ->
            val list = cache[key(spec.symbol, tf)] ?: return@forEach
            if (list.isEmpty()) return@forEach
            val step = tf.minutes * 60_000L
            val last = list.last()
            if (now >= last.time + step) {
                val open = last.close
                val fresh = Candle(last.time + step, open, max(open, price), min(open, price), price, 120.0)
                list.add(fresh)
                if (list.size > 2_600) list.removeAt(0)
            } else {
                list[list.size - 1] = last.copy(
                    high = max(last.high, price),
                    low = min(last.low, price),
                    close = price,
                    volume = last.volume + 12.0,
                )
            }
        }
    }

    fun reset() = synchronized(lock) {
        cache.clear()
        livePrice.clear()
        prevDayClose.clear()
        random = Random(20260209L)
    }

    private fun seedOf(symbol: String, tf: Timeframe): Long =
        20260209L * 31 + symbol.hashCode().toLong() * 1_000L + tf.ordinal * 7919L

    private fun generate(
        spec: SymbolSpec,
        tf: Timeframe,
        count: Int,
        seed: Long,
        endTime: Long,
    ): MutableList<Candle> {
        val rnd = Random(seed)
        val step = tf.minutes * 60_000L
        val skipWeekend = !spec.symbol.startsWith("BTC")
        val alignedEnd = endTime - (endTime % step)
        // Generate well past the requested bar count so weekend gaps and short
        // month boundaries never leave flat padding candles at the left edge.
        val spanMultiplier = if (skipWeekend) 30L else 12L
        var time = alignedEnd - (count.toLong() * step * spanMultiplier) / 10L

        val out = ArrayList<Candle>(count + 32)
        var price = spec.startPrice * (1.0 + (rnd.nextDouble() - 0.5) * 0.012)
        var drift = 0.0
        var regimeLeft = 0
        var volState = 1.0
        var guard = 0

        while (time <= alignedEnd && guard < count * 3) {
            guard++
            time += step
            val zdt = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC)
            if (skipWeekend && (zdt.dayOfWeek.value == 6 || zdt.dayOfWeek.value == 7)) continue

            if (regimeLeft <= 0) {
                regimeLeft = 70 + rnd.nextInt(180)
                drift = (rnd.nextDouble() - 0.5) * 0.55 + spec.trendBias * 0.35
                volState = 0.72 + rnd.nextDouble() * 0.72
            }
            regimeLeft--

            val session = sessionMultiplier(time) * when (tf) {
                Timeframe.D1 -> 1.05
                Timeframe.H4 -> 1.0
                else -> 0.95 + sessionMultiplier(time) * 0.12
            }
            val scale = spec.volatility * volState * session
            val open = price
            var ret = drift * spec.volatility * 0.42 + gaussian(rnd) * scale
            // Occasional expansion candle, similar to a displacement leg.
            if (rnd.nextDouble() < 0.022) ret *= 2.4
            var close = open * (1.0 + ret)
            if (close <= 0.0) close = open * 0.98
            val wickScale = open * scale * (0.35 + rnd.nextDouble() * 0.55)
            val high = max(open, close) + abs(gaussian(rnd)) * wickScale
            val low = (min(open, close) - abs(gaussian(rnd)) * wickScale).coerceAtLeast(close * 0.9)
            val volume = (450 + rnd.nextInt(4_200)) * session
            out.add(Candle(time, open, high, low, close, volume))
            price = close
        }

        if (out.size > count) out.subList(0, out.size - count).clear()
        while (out.size < count) {
            val last = out.lastOrNull() ?: break
            val t = last.time + step
            val c = last.close
            out.add(Candle(t, c, c, c, c, 100.0))
        }
        return out
    }

    private fun sessionMultiplier(millis: Long): Double {
        val hour = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).hour
        return when (hour) {
            in 0..6 -> 0.55
            7, 8, 9 -> 1.25
            10, 11 -> 0.95
            in 12..15 -> 1.48
            in 16..18 -> 1.05
            in 19..21 -> 0.80
            else -> 0.62
        }
    }

    private fun gaussian(rnd: Random): Double {
        var u = 0.0
        var v = 0.0
        while (u <= 1e-9) u = rnd.nextDouble()
        while (v <= 1e-9) v = rnd.nextDouble()
        return sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
    }

    fun pipValue(spec: SymbolSpec, lots: Double, price: Double): Double {
        // Simplified account-currency pip value. For JPY quotes, convert the
        // JPY pip value into USD using the current USD/JPY price.
        val units = lots * spec.contractSize
        val raw = spec.pipSize * units
        return if (spec.quote == "JPY") raw / price.coerceAtLeast(1.0) else raw
    }
}
