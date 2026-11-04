package com.smclab.autotrade.engine

import com.smclab.autotrade.model.Candle
import com.smclab.autotrade.model.LiquidityLevel
import com.smclab.autotrade.model.OteInfo
import com.smclab.autotrade.model.SessionState
import com.smclab.autotrade.model.SmcSnapshot
import com.smclab.autotrade.model.StructureBreak
import com.smclab.autotrade.model.SwingPoint
import com.smclab.autotrade.model.SymbolSpec
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.model.Trend
import com.smclab.autotrade.model.VixLevel
import com.smclab.autotrade.model.Zone
import com.smclab.autotrade.model.ZoneKind
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kotlin port of the SMC/ICT detectors that live in Include/SMC of the
 * AutoTrade repository.
 *
 * The port keeps the same concepts and default thresholds:
 *  - fractal swing points confirmed `strength` bars later
 *  - BOS / CHoCH classified against the running structure trend
 *  - order blocks formed by displacement legs, with fresh/tested/mitigated/broken life cycle
 *  - fair value gaps from the three-candle imbalance
 *  - equal highs / lows clustered within a pip tolerance and sweeps
 *  - premium / discount dealing range and 0.618-0.786 OTE
 *  - London / New York kill zones
 *  - annualised historical volatility as a VIX equivalent
 *
 * Everything is computed from the supplied candles only. No broker data is used.
 */
class SmcSeries(
    val candles: List<Candle>,
    val spec: SymbolSpec,
    val timeframe: Timeframe,
    private val swingStrength: Int = 3,
    private val rangeLookback: Int = 96,
) {
    val size: Int = candles.size
    val atr = DoubleArray(size)
    val avgBody = DoubleArray(size)
    private val ema20 = DoubleArray(size)
    private val ema50 = DoubleArray(size)
    private val trendAt = arrayOfNulls<Trend>(size)

    val swingsHigh = ArrayList<SwingPoint>()
    val swingsLow = ArrayList<SwingPoint>()
    val breaks = ArrayList<StructureBreak>()
    val bullishObs = ArrayList<Zone>()
    val bearishObs = ArrayList<Zone>()
    val bullishFvgs = ArrayList<Zone>()
    val bearishFvgs = ArrayList<Zone>()
    val breakers = ArrayList<Zone>()
    val liquidity = ArrayList<LiquidityLevel>()

    private var zoneId = 0L

    init {
        if (size >= 12) {
            computeAverages()
            detectSwings()
            replayStructure()
            detectFairValueGaps()
            detectOrderBlocks()
            detectBreakers()
            detectLiquidity()
        }
    }

    val isReady: Boolean get() = size >= 40

    fun atrAt(index: Int): Double = atr[index.coerceIn(0, (size - 1).coerceAtLeast(0))]

    // ------------------------------------------------------------------
    // Averages
    // ------------------------------------------------------------------
    private fun computeAverages() {
        var sumBody = 0.0
        var prevClose = candles[0].close
        var atrValue = 0.0
        var emaFast = candles[0].close
        var emaSlow = candles[0].close
        val kFast = 2.0 / 21.0
        val kSlow = 2.0 / 51.0

        for (i in 0 until size) {
            val c = candles[i]
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            atrValue = if (i == 0) tr else (atrValue * 13.0 + tr) / 14.0
            atr[i] = atrValue
            prevClose = c.close

            sumBody += c.body
            if (i >= 20) sumBody -= candles[i - 20].body
            avgBody[i] = sumBody / min(i + 1, 20).toDouble()

            emaFast = if (i == 0) c.close else c.close * kFast + emaFast * (1.0 - kFast)
            emaSlow = if (i == 0) c.close else c.close * kSlow + emaSlow * (1.0 - kSlow)
            ema20[i] = emaFast
            ema50[i] = emaSlow
        }
    }

    // ------------------------------------------------------------------
    // Swings
    // ------------------------------------------------------------------
    private fun detectSwings() {
        val s = swingStrength
        if (size <= s * 2) return
        for (i in s until size - s) {
            val c = candles[i]
            var high = true
            var low = true
            for (k in 1..s) {
                if (candles[i - k].high >= c.high || candles[i + k].high >= c.high) high = false
                if (candles[i - k].low <= c.low || candles[i + k].low <= c.low) low = false
            }
            val confirmed = min(i + s, size - 1)
            if (high) swingsHigh.add(SwingPoint(i, confirmed, c.high, true, s))
            if (low) swingsLow.add(SwingPoint(i, confirmed, c.low, false, s))
        }
    }

    // ------------------------------------------------------------------
    // BOS / CHoCH
    // ------------------------------------------------------------------
    private fun replayStructure() {
        if (size < 30) return
        val highs = swingsHigh.map { it.copy() }.toMutableList()
        val lows = swingsLow.map { it.copy() }.toMutableList()
        var trend = Trend.RANGING

        for (i in 0 until size) {
            val lastHigh = highs.lastOrNull { !it.broken && it.confirmedIndex <= i }
            val lastLow = lows.lastOrNull { !it.broken && it.confirmedIndex <= i }
            val close = candles[i].close

            if (lastHigh != null && close > lastHigh.price) {
                val isBos = trend == Trend.BULLISH
                breaks.add(StructureBreak(i, true, isBos, close, lastHigh.price))
                markBroken(highs, lastHigh)
                trend = Trend.BULLISH
            }
            if (lastLow != null && close < lastLow.price) {
                val isBos = trend == Trend.BEARISH
                breaks.add(StructureBreak(i, false, isBos, close, lastLow.price))
                markBroken(lows, lastLow)
                trend = Trend.BEARISH
            }

            trendAt[i] = if (breaks.any { it.index == i }) {
                trend
            } else {
                fallbackTrend(i)
            }
        }
    }

    private fun markBroken(list: MutableList<SwingPoint>, point: SwingPoint) {
        val idx = list.indexOfFirst { it.index == point.index && it.isHigh == point.isHigh }
        if (idx >= 0) list[idx] = list[idx].copy(broken = true)
    }

    private fun fallbackTrend(index: Int): Trend {
        if (index < 12) return Trend.RANGING
        val change = candles[index].close - candles[index - 10].close
        val threshold = max(atr[index] * 0.45, spec.pipSize * 4)
        return when {
            change > threshold -> Trend.BULLISH
            change < -threshold -> Trend.BEARISH
            else -> Trend.RANGING
        }
    }

    // ------------------------------------------------------------------
    // Fair value gaps
    // ------------------------------------------------------------------
    private fun detectFairValueGaps() {
        if (size < 5) return
        val minGap = spec.pipSize * 2.0
        for (i in 2 until size) {
            val a = candles[i - 2]
            val c = candles[i]
            if (c.low - a.high >= minGap) {
                addZone(
                    kind = ZoneKind.FVG,
                    bullish = true,
                    top = c.low,
                    bottom = a.high,
                    formation = i - 1,
                    confirmed = i,
                    highProbability = abs(c.close - c.open) > avgBody[i] * 1.2,
                    target = bullishFvgs,
                )
            }
            if (a.low - c.high >= minGap) {
                addZone(
                    kind = ZoneKind.FVG,
                    bullish = false,
                    top = a.low,
                    bottom = c.high,
                    formation = i - 1,
                    confirmed = i,
                    highProbability = abs(c.close - c.open) > avgBody[i] * 1.2,
                    target = bearishFvgs,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Order blocks
    // ------------------------------------------------------------------
    private fun detectOrderBlocks() {
        if (size < 30) return
        val impulseWindow = 4
        for (i in 20 until size) {
            val c = candles[i]
            if (c.body < avgBody[i] * 1.5) continue
            val net = c.close - candles[i - impulseWindow].close
            if (abs(net) < avgBody[i] * 2.0) continue
            val bullish = net > 0 && c.close > c.open
            val bearish = net < 0 && c.close < c.open
            if (!bullish && !bearish) continue

            var block: Candle? = null
            var formation = -1
            for (j in i - 1 downTo max(0, i - impulseWindow)) {
                val p = candles[j]
                if (bullish && p.close < p.open) { block = p; formation = j; break }
                if (bearish && p.close > p.open) { block = p; formation = j; break }
            }
            val ob = block ?: continue
            if (formation < 0) continue
            val duplicate = (if (bullish) bullishObs else bearishObs)
                .any { abs(it.formationIndex - formation) <= 1 && abs(it.top - ob.high) < spec.pipSize }
            if (duplicate) continue

            val gapOverlap = (if (bullish) bullishFvgs else bearishFvgs)
                .any { it.confirmedIndex >= i - 2 && it.bottom <= ob.high && it.top >= ob.low }
            addZone(
                kind = ZoneKind.OB,
                bullish = bullish,
                top = ob.high,
                bottom = ob.low,
                formation = formation,
                confirmed = i,
                highProbability = gapOverlap || c.body > avgBody[i] * 2.1,
                target = if (bullish) bullishObs else bearishObs,
            )
        }
        trim(bullishObs)
        trim(bearishObs)
    }

    private fun trim(zones: MutableList<Zone>) {
        if (zones.size <= 36) return
        zones.sortBy { it.confirmedIndex }
        while (zones.size > 36) zones.removeAt(0)
    }

    // ------------------------------------------------------------------
    // Zones + life cycle
    // ------------------------------------------------------------------
    private fun addZone(
        kind: ZoneKind,
        bullish: Boolean,
        top: Double,
        bottom: Double,
        formation: Int,
        confirmed: Int,
        highProbability: Boolean,
        target: MutableList<Zone>,
    ) {
        if (top <= bottom || confirmed >= size) return
        var tested = -1
        var mitigated = -1
        var broken = -1
        val mid = (top + bottom) / 2.0
        var j = confirmed + 1
        while (j < size) {
            val c = candles[j]
            if (bullish) {
                if (c.close < bottom) { broken = j; break }
                if (c.low <= top) {
                    if (tested < 0) tested = j
                    if (c.low <= mid && mitigated < 0) mitigated = j
                }
            } else {
                if (c.close > top) { broken = j; break }
                if (c.high >= bottom) {
                    if (tested < 0) tested = j
                    if (c.high >= mid && mitigated < 0) mitigated = j
                }
            }
            j++
        }
        val zone = Zone(
            id = zoneId++,
            kind = kind,
            isBullish = bullish,
            top = top,
            bottom = bottom,
            formationIndex = formation,
            confirmedIndex = confirmed,
            brokenIndex = broken,
            testedIndex = tested,
            mitigatedIndex = mitigated,
            highProbability = highProbability,
        )
        target.add(zone)

        if (kind == ZoneKind.OB && broken > 0) {
            val life = lifecycleOf(top, bottom, !bullish, broken)
            val breaker = Zone(
                id = zoneId++,
                kind = ZoneKind.BREAKER,
                isBullish = !bullish,
                top = top,
                bottom = bottom,
                formationIndex = formation,
                confirmedIndex = broken,
                brokenIndex = life.first,
                testedIndex = life.second,
                mitigatedIndex = life.third,
                highProbability = highProbability,
            )
            breakers.add(breaker)
        }
    }

    private fun lifecycleOf(
        top: Double,
        bottom: Double,
        bullish: Boolean,
        confirmed: Int,
    ): Triple<Int, Int, Int> {
        var tested = -1
        var mitigated = -1
        var broken = -1
        val mid = (top + bottom) / 2.0
        var j = confirmed + 1
        while (j < size) {
            val c = candles[j]
            if (bullish) {
                if (c.close < bottom) { broken = j; break }
                if (c.low <= top) {
                    if (tested < 0) tested = j
                    if (c.low <= mid && mitigated < 0) mitigated = j
                }
            } else {
                if (c.close > top) { broken = j; break }
                if (c.high >= bottom) {
                    if (tested < 0) tested = j
                    if (c.high >= mid && mitigated < 0) mitigated = j
                }
            }
            j++
        }
        return Triple(broken, tested, mitigated)
    }

    private fun detectBreakers() {
        // Life cycles of breaker zones are evaluated at snapshot time.
        if (breakers.size > 20) {
            breakers.sortBy { it.confirmedIndex }
            while (breakers.size > 20) breakers.removeAt(0)
        }
    }

    // ------------------------------------------------------------------
    // Liquidity
    // ------------------------------------------------------------------
    private fun detectLiquidity() {
        val tolerance = spec.pipSize * 3.0
        buildLiquidity(swingsHigh, true, tolerance)
        buildLiquidity(swingsLow, false, tolerance)
        liquidity.sortBy { it.confirmedIndex }
    }

    private fun buildLiquidity(swings: List<SwingPoint>, isHigh: Boolean, tolerance: Double) {
        val clusters = ArrayList<MutableList<SwingPoint>>()
        for (s in swings) {
            val hit = clusters.firstOrNull { cluster ->
                abs(cluster.first().price - s.price) <= tolerance
            }
            if (hit != null) hit.add(s) else clusters.add(mutableListOf(s))
        }
        for (cluster in clusters) {
            if (cluster.size < 2) continue
            val price = cluster.map { it.price }.average()
            val confirm = cluster.maxOf { it.confirmedIndex }
            val lastTouch = cluster.maxOf { it.index }
            var sweep = -1
            for (j in confirm + 1 until size) {
                val c = candles[j]
                if (isHigh) {
                    if (c.high > price && c.close < price) { sweep = j; break }
                } else {
                    if (c.low < price && c.close > price) { sweep = j; break }
                }
            }
            liquidity.add(LiquidityLevel(price, isHigh, cluster.size, confirm, sweep, lastTouch))
        }
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------
    fun snapshotAt(index: Int, sessionMillis: Long = -1L): SmcSnapshot {
        val i = index.coerceIn(0, size - 1)
        val price = candles[i].close
        val knownHighs = swingsHigh.filter { it.confirmedIndex <= i }
        val knownLows = swingsLow.filter { it.confirmedIndex <= i }
        val breaksTo = breaks.filter { it.index <= i }
        val trend = trendAt[i] ?: Trend.RANGING

        val activeObsBull = bullishObs.filter { it.waitingAt(i) }
        val activeObsBear = bearishObs.filter { it.waitingAt(i) }
        val activeFvgBull = bullishFvgs.filter { it.waitingAt(i) }
        val activeFvgBear = bearishFvgs.filter { it.waitingAt(i) }

        val from = max(0, i - rangeLookback + 1)
        var rangeHigh = candles[from].high
        var rangeLow = candles[from].low
        for (k in from..i) {
            rangeHigh = max(rangeHigh, candles[k].high)
            rangeLow = min(rangeLow, candles[k].low)
        }
        if (rangeHigh <= rangeLow) {
            rangeHigh = price + spec.pipSize * 10
            rangeLow = price - spec.pipSize * 10
        }
        val equilibrium = (rangeHigh + rangeLow) / 2.0
        val zonePercent = ((price - rangeLow) / (rangeHigh - rangeLow) * 100.0).coerceIn(0.0, 100.0)

        val lastHigh = knownHighs.lastOrNull()
        val lastLow = knownLows.lastOrNull()
        val ote = buildOte(lastHigh, lastLow)

        val sessionTime = if (sessionMillis > 0) sessionMillis else candles[i].time
        val sessions = sessionsAt(sessionTime)
        val inKillZone = sessions.any { it.inKillZone }

        val vix = vixAt(i)
        return SmcSnapshot(
            index = i,
            price = price,
            trend = trend,
            swingsHigh = knownHighs.takeLast(24),
            swingsLow = knownLows.takeLast(24),
            breaks = breaksTo.takeLast(40),
            lastBos = breaksTo.lastOrNull { it.isBos },
            lastChoch = breaksTo.lastOrNull { !it.isBos },
            bullishObs = activeObsBull,
            bearishObs = activeObsBear,
            bullishFvgs = activeFvgBull,
            bearishFvgs = activeFvgBear,
            liquidity = liquidity.filter { it.confirmedIndex <= i },
            premiumDiscountHigh = rangeHigh,
            premiumDiscountLow = rangeLow,
            equilibrium = equilibrium,
            zonePercent = zonePercent,
            ote = ote,
            sessions = sessions,
            inKillZone = inKillZone,
            vix = vix,
            vixLevel = classifyVix(vix),
        )
    }

    fun breakersAt(index: Int): List<Zone> = breakers.filter { it.waitingAt(index) }

    private fun Zone.waitingAt(index: Int): Boolean = activeAt(index)

    private fun buildOte(high: SwingPoint?, low: SwingPoint?): OteInfo? {
        if (high == null || low == null) return null
        if (high.index == low.index) return null
        val range = high.price - low.price
        if (range <= spec.pipSize * 4) return null
        val bullish = high.index > low.index
        val f618: Double
        val f705: Double
        val f786: Double
        if (bullish) {
            f618 = high.price - range * 0.618
            f705 = high.price - range * 0.705
            f786 = high.price - range * 0.786
        } else {
            f618 = low.price + range * 0.618
            f705 = low.price + range * 0.705
            f786 = low.price + range * 0.786
        }
        return OteInfo(
            isBullish = bullish,
            swingHigh = high.price,
            swingLow = low.price,
            fib618 = f618,
            fib705 = f705,
            fib786 = f786,
            top = max(f618, f786),
            bottom = min(f618, f786),
        )
    }

    // ------------------------------------------------------------------
    // Sessions / kill zones
    // ------------------------------------------------------------------
    fun sessionsAt(millis: Long): List<SessionState> {
        val hour = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).hour
        val asian = hour in 0..7
        val london = hour in 7..15
        val ny = hour in 12..20
        val overlap = hour in 12..15
        val killZone = hour in 7..9 || hour in 12..14
        return listOf(
            SessionState("Asia", asian, killZone && asian),
            SessionState("London", london, killZone && hour in 7..9),
            SessionState("New York", ny, killZone && hour in 12..14),
            SessionState("LDN-NY", overlap, overlap),
        )
    }

    // ------------------------------------------------------------------
    // VIX equivalent (annualised historical volatility, asset-calibrated)
    // ------------------------------------------------------------------
    fun vixAt(index: Int): Double {
        val period = 20
        if (index < period + 1) return 15.0
        val returns = DoubleArray(period)
        var mean = 0.0
        for (k in 0 until period) {
            val a = candles[index - k - 1].close
            val b = candles[index - k].close
            if (a <= 0 || b <= 0) return 15.0
            val r = ln(b / a)
            returns[k] = r
            mean += r
        }
        mean /= period
        var variance = 0.0
        for (r in returns) variance += (r - mean) * (r - mean)
        variance /= (period - 1)
        val barsPerYear = 252.0 * (1440.0 / timeframe.minutes.toDouble())
        val annualised = sqrt(variance) * sqrt(barsPerYear) * 100.0
        val scale = when (spec.symbol) {
            "XAUUSD" -> 2.0
            "BTCUSD" -> 0.55
            "US30", "NAS100" -> 1.3
            else -> if (spec.quote == "JPY") 4.0 else 5.0
        }
        return (annualised * scale).coerceIn(2.0, 75.0)
    }

    private fun classifyVix(vix: Double): VixLevel = when {
        vix < 15.0 -> VixLevel.LOW
        vix < 25.0 -> VixLevel.NORMAL
        vix < 35.0 -> VixLevel.HIGH
        else -> VixLevel.EXTREME
    }

    // Convenience helpers used by the UI and the strategy engine.
    fun recentHighs(count: Int = 24, index: Int = size - 1): List<SwingPoint> =
        swingsHigh.filter { it.confirmedIndex <= index }.takeLast(count)

    fun recentLows(count: Int = 24, index: Int = size - 1): List<SwingPoint> =
        swingsLow.filter { it.confirmedIndex <= index }.takeLast(count)

    fun activeObs(index: Int, bullish: Boolean): List<Zone> =
        (if (bullish) bullishObs else bearishObs).filter { it.activeAt(index) && index >= it.confirmedIndex }

    fun activeFvgs(index: Int, bullish: Boolean): List<Zone> =
        (if (bullish) bullishFvgs else bearishFvgs).filter { it.activeAt(index) && index >= it.confirmedIndex }
}

object SmcEngine {
    fun analyze(
        candles: List<Candle>,
        spec: SymbolSpec,
        timeframe: Timeframe,
        nowMillis: Long = -1L,
    ): SmcSnapshot {
        val series = SmcSeries(candles, spec, timeframe)
        val index = (candles.size - 1).coerceAtLeast(0)
        return series.snapshotAt(index, nowMillis)
    }
}
