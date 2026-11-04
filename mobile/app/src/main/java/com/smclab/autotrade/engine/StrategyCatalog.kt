package com.smclab.autotrade.engine

import com.smclab.autotrade.model.ConfluenceResult
import com.smclab.autotrade.model.ParamSpec
import com.smclab.autotrade.model.Side
import com.smclab.autotrade.model.SmcSnapshot
import com.smclab.autotrade.model.StrategyConfig
import com.smclab.autotrade.model.StrategyDef
import com.smclab.autotrade.model.StrategySignal
import com.smclab.autotrade.model.Trend
import com.smclab.autotrade.model.Zone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Strategy catalogue.
 *
 * Every strategy is a rule set over the same SMC detectors. The confluence
 * factor weights match Include/SMC/ConfluenceDetector.mqh:
 *   structure .25, order block .20, FVG .15, liquidity .10,
 *   OTE .10, kill zone .10, breaker .10.
 */
object StrategyCatalog {

    private fun sharedParams(
        lookback: Double = 12.0,
        proximity: Double = 0.60,
        atrMult: Double = 1.40,
        extra: List<ParamSpec> = emptyList(),
    ): List<ParamSpec> = listOf(
        ParamSpec("lookback", "Số nến xác nhận cấu trúc", lookback, 4.0, 40.0, 1.0, "nến", 0),
        ParamSpec("proximity", "Khoảng cách tới vùng (ATR)", proximity, 0.2, 1.5, 0.1, "ATR", 1),
        ParamSpec("atrMult", "Bội số ATR cho SL", atrMult, 0.6, 3.5, 0.1, "ATR", 1),
        ParamSpec("freshness", "Tuổi vùng tối đa", 48.0, 10.0, 200.0, 5.0, "nến", 0),
        ParamSpec("minScore", "Điểm confluence tối thiểu", 0.5, 0.3, 0.9, 0.05, "", 2),
        ParamSpec("sweepBars", "Cửa sổ liquidity sweep", 10.0, 3.0, 40.0, 1.0, "nến", 0),
    ) + extra

    val all: List<StrategyDef> = listOf(
        StrategyDef(
            id = "ob_continuation",
            name = "Order Block Continuation",
            category = "Continuation",
            tagline = "BOS + OB tươi trong discount/premium",
            description = "Vào lệnh theo xu hướng khi giá hồi về order block còn tươi sau một BOS. " +
                "Chỉ mua trong discount và chỉ bán trong premium, ưu tiên OB trùng FVG.",
            minConfluence = 3,
            defaults = mapOf(
                "lookback" to 12.0, "proximity" to 0.55, "atrMult" to 1.3,
                "freshness" to 60.0, "minScore" to 0.5, "sweepBars" to 10.0,
                "requireKillZone" to 0.0,
            ),
            params = sharedParams(12.0, 0.55, 1.3) + ParamSpec(
                "requireKillZone", "Bắt buộc kill zone", 0.0, 0.0, 1.0, 1.0, "", 0,
            ),
            defaultRisk = 1.0,
            defaultRr = 2.5,
            minBars = 320,
        ),
        StrategyDef(
            id = "fvg_retrace",
            name = "FVG Retracement",
            category = "Continuation",
            tagline = "Giá hồi vào FVG trong xu hướng",
            description = "Chờ giá quay lại lấp một phần fair value gap theo hướng xu hướng. " +
                "Điểm vào tốt hơn khi FVG nằm trong vùng OTE 0.618–0.786.",
            minConfluence = 3,
            defaults = mapOf(
                "lookback" to 14.0, "proximity" to 0.45, "atrMult" to 1.2,
                "freshness" to 70.0, "minScore" to 0.5, "sweepBars" to 10.0,
                "requireOte" to 0.0,
            ),
            params = sharedParams(14.0, 0.45, 1.2) + ParamSpec(
                "requireOte", "Bắt buộc OTE", 0.0, 0.0, 1.0, 1.0, "", 0,
            ),
            defaultRisk = 1.0,
            defaultRr = 2.0,
        ),
        StrategyDef(
            id = "choch_reversal",
            name = "CHoCH Reversal",
            category = "Reversal",
            tagline = "Đổi hướng sau CHoCH + vùng đối ứng",
            description = "Bắt điểm đảo chiều khi cấu trúc đổi hướng (CHoCH) và giá phản ứng tại " +
                "order block/FVG. Yêu cầu có liquidity sweep để tránh tín hiệu giả.",
            minConfluence = 4,
            defaults = mapOf(
                "lookback" to 8.0, "proximity" to 0.70, "atrMult" to 1.6,
                "freshness" to 40.0, "minScore" to 0.55, "sweepBars" to 14.0,
                "requireSweep" to 0.0,
            ),
            params = sharedParams(8.0, 0.70, 1.6) + ParamSpec(
                "requireSweep", "Bắt buộc sweep", 0.0, 0.0, 1.0, 1.0, "", 0,
            ),
            defaultRisk = 0.75,
            defaultRr = 3.0,
        ),
        StrategyDef(
            id = "liquidity_sweep",
            name = "Liquidity Sweep Reversal",
            category = "Reversal",
            tagline = "Quét thanh khoản rồi quay đầu",
            description = "Tìm nến quét qua đỉnh/đáy bằng nhau nhưng đóng cửa trở lại trong range, " +
                "sau đó xác nhận bằng cấu trúc. Mua sau khi quét đáy, bán sau khi quét đỉnh.",
            minConfluence = 3,
            defaults = mapOf(
                "lookback" to 10.0, "proximity" to 0.70, "atrMult" to 1.5,
                "freshness" to 50.0, "minScore" to 0.5, "sweepBars" to 8.0,
            ),
            params = sharedParams(10.0, 0.70, 1.5),
            defaultRisk = 0.75,
            defaultRr = 2.5,
        ),
        StrategyDef(
            id = "bos_momentum",
            name = "BOS Momentum",
            category = "Continuation",
            tagline = "Theo nhịp BOS trong kill zone",
            description = "Vào lệnh thuận xu hướng ngay sau khi BOS được xác nhận bởi nến đóng cửa, " +
                "tập trung vào London/NY kill zone và nến displacement mạnh.",
            minConfluence = 3,
            defaults = mapOf(
                "lookback" to 5.0, "proximity" to 0.80, "atrMult" to 1.8,
                "freshness" to 30.0, "minScore" to 0.55, "sweepBars" to 8.0,
            ),
            params = sharedParams(5.0, 0.80, 1.8),
            defaultRisk = 1.0,
            defaultRr = 2.0,
        ),
        StrategyDef(
            id = "ote_precision",
            name = "OTE Precision",
            category = "Zone",
            tagline = "0.618–0.786 + vùng SMC",
            description = "Chỉ vào lệnh khi giá nằm trong vùng optimal trade entry của sóng gần nhất " +
                "và có order block hoặc FVG trùng khớp.",
            minConfluence = 3,
            defaults = mapOf(
                "lookback" to 12.0, "proximity" to 0.35, "atrMult" to 1.2,
                "freshness" to 80.0, "minScore" to 0.5, "sweepBars" to 10.0,
            ),
            params = sharedParams(12.0, 0.35, 1.2),
            defaultRisk = 0.75,
            defaultRr = 3.0,
        ),
        StrategyDef(
            id = "silver_bullet",
            name = "ICT Silver Bullet",
            category = "Session",
            tagline = "Khung 10–11h và 14–15h UTC",
            description = "Mô phỏng ICT Silver Bullet: chỉ giao dịch trong cửa sổ kill zone, " +
                "chờ BOS rồi vào tại FVG hình thành sau cú displacement.",
            minConfluence = 4,
            defaults = mapOf(
                "lookback" to 6.0, "proximity" to 0.50, "atrMult" to 1.4,
                "freshness" to 24.0, "minScore" to 0.6, "sweepBars" to 8.0,
            ),
            params = sharedParams(6.0, 0.50, 1.4),
            defaultRisk = 0.75,
            defaultRr = 2.0,
        ),
        StrategyDef(
            id = "judas_swing",
            name = "Judas Swing (London)",
            category = "Session",
            tagline = "Quét range Asia mở cửa London",
            description = "Tìm cú quét giả qua biên range phiên Asia khi London mở cửa, sau đó " +
                "đảo chiều khi giá đóng cửa trở lại range và cấu trúc xác nhận.",
            minConfluence = 3,
            defaults = mapOf(
                "lookback" to 8.0, "proximity" to 0.65, "atrMult" to 1.5,
                "freshness" to 40.0, "minScore" to 0.5, "sweepBars" to 6.0,
            ),
            params = sharedParams(8.0, 0.65, 1.5),
            defaultRisk = 0.75,
            defaultRr = 2.5,
        ),
        StrategyDef(
            id = "breaker_block",
            name = "Breaker Block",
            category = "Reversal",
            tagline = "OB thất bại trở thành hỗ trợ/kháng cự",
            description = "Khi một order block bị phá và giá quay lại kiểm định, vùng đó trở thành " +
                "breaker. Vào lệnh khi breaker còn tươi và cấu trúc đồng thuận.",
            minConfluence = 3,
            defaults = mapOf(
                "lookback" to 8.0, "proximity" to 0.55, "atrMult" to 1.4,
                "freshness" to 50.0, "minScore" to 0.5, "sweepBars" to 10.0,
            ),
            params = sharedParams(8.0, 0.55, 1.4),
            defaultRisk = 0.75,
            defaultRr = 2.5,
        ),
        StrategyDef(
            id = "premium_discount",
            name = "Premium / Discount Reversal",
            category = "Zone",
            tagline = "Mua discount – bán premium",
            description = "Chờ giá về vùng extreme của dealing range (dưới 30% hoặc trên 70%) " +
                "kết hợp CHoCH và vùng SMC để vào lệnh ngược biên.",
            minConfluence = 4,
            defaults = mapOf(
                "lookback" to 10.0, "proximity" to 0.80, "atrMult" to 1.7,
                "freshness" to 60.0, "minScore" to 0.55, "sweepBars" to 12.0,
                "extreme" to 30.0,
            ),
            params = sharedParams(10.0, 0.80, 1.7) + ParamSpec(
                "extreme", "Ngưỡng extreme (%)", 30.0, 15.0, 40.0, 1.0, "%", 0,
            ),
            defaultRisk = 0.5,
            defaultRr = 3.0,
        ),
    )

    private val index: Map<String, StrategyDef> = all.associateBy { it.id }

    fun byId(id: String): StrategyDef? = index[id]

    fun require(id: String): StrategyDef = index[id] ?: all.first()

    fun defaultConfig(def: StrategyDef): StrategyConfig = StrategyConfig(
        strategyId = def.id,
        enabled = def.id == "ob_continuation" || def.id == "fvg_retrace",
        params = def.defaults.toMutableMap(),
        riskPercent = def.defaultRisk,
        riskReward = def.defaultRr,
    )
}

/**
 * Evaluates one strategy on one closed-bar snapshot. Returns null when the
 * setup is not valid, so the same function can drive both the live signal card
 * and the backtest loop.
 */
object StrategyEvaluator {

    private const val W_STRUCTURE = 0.25
    private const val W_OB = 0.20
    private const val W_FVG = 0.15
    private const val W_LIQUIDITY = 0.10
    private const val W_OTE = 0.10
    private const val W_KILLZONE = 0.10
    private const val W_BREAKER = 0.10

    fun evaluate(
        def: StrategyDef,
        series: SmcSeries,
        snapshot: SmcSnapshot,
        config: StrategyConfig,
        riskRewardOverride: Double? = null,
        htfTrend: Trend? = null,
    ): StrategySignal? {
        if (!series.isReady) return null
        val index = snapshot.index
        val configRr = riskRewardOverride ?: config.riskReward
        val atr = series.atrAt(index)
        if (atr <= 0.0) return null
        val lookback = config.param("lookback", 12.0).toInt().coerceAtLeast(3)
        val proximity = atr * config.param("proximity", 0.6)
        val freshness = config.param("freshness", 60.0).toInt()
        val sweepBars = config.param("sweepBars", 10.0).toInt()
        val minScore = config.param("minScore", 0.5)

        val recentBreaks = snapshot.breaks.filter { index - it.index <= lookback }
        val recentChochBuy = recentBreaks.lastOrNull { !it.isBos && it.isBullish }
        val recentChochSell = recentBreaks.lastOrNull { !it.isBos && !it.isBullish }
        val recentBosBuy = recentBreaks.lastOrNull { it.isBos && it.isBullish }
        val recentBosSell = recentBreaks.lastOrNull { it.isBos && !it.isBullish }

        var side: Side? = when (def.id) {
            "choch_reversal" -> when {
                recentChochBuy != null -> Side.BUY
                recentChochSell != null -> Side.SELL
                else -> null
            }
            "liquidity_sweep" -> when {
                snapshot.recentSweep(false, sweepBars) != null -> Side.BUY
                snapshot.recentSweep(true, sweepBars) != null -> Side.SELL
                else -> null
            }
            "judas_swing" -> when {
                snapshot.recentSweep(false, sweepBars) != null -> Side.BUY
                snapshot.recentSweep(true, sweepBars) != null -> Side.SELL
                else -> null
            }
            "premium_discount" -> when {
                snapshot.zonePercent <= 35.0 && recentChochBuy != null -> Side.BUY
                snapshot.zonePercent >= 65.0 && recentChochSell != null -> Side.SELL
                else -> null
            }
            "breaker_block" -> when {
                series.breakersAt(index).any { it.isBullish && near(it, snapshot.price, proximity) } -> Side.BUY
                series.breakersAt(index).any { !it.isBullish && near(it, snapshot.price, proximity) } -> Side.SELL
                else -> null
            }
            else -> when (snapshot.trend) {
                Trend.BULLISH -> Side.BUY
                Trend.BEARISH -> Side.SELL
                Trend.RANGING -> null
            }
        }
        side ?: return null
        val buy = side == Side.BUY

        // Higher-timeframe confirmation. RANGING/no HTF means "no objection".
        val htfBias = htfTrend?.takeIf { it != Trend.RANGING }
        if (config.useHtf && htfBias != null) {
            val agrees = when (htfBias) {
                Trend.BULLISH -> buy
                Trend.BEARISH -> !buy
                Trend.RANGING -> true
            }
            if (!agrees) return null
        }
        val htfFactor = if (config.useHtf) {
            htfBias?.let { "HTF ${it.label}" }
        } else null

        // Premium/discount filter for trend continuation.
        if (def.id != "choch_reversal" && def.id != "premium_discount" && def.id != "liquidity_sweep") {
            if (buy && snapshot.zonePercent > 62.0) return null
            if (!buy && snapshot.zonePercent < 38.0) return null
        }

        val factors = ArrayList<Pair<String, Double>>()
        val labels = ArrayList<String>()

        val structure = if (buy) {
            recentBreaks.lastOrNull { it.isBullish && (it.isBos || def.id == "choch_reversal") }
        } else {
            recentBreaks.lastOrNull { !it.isBullish && (it.isBos || def.id == "choch_reversal") }
        }
        if (structure != null) {
            factors.add((if (structure.isBos) "BOS" else "CHoCH") + (if (buy) " tăng" else " giảm") to W_STRUCTURE)
            labels.add(if (structure.isBos) "BOS" else "CHoCH")
        }

        val obs = if (buy) snapshot.bullishObs else snapshot.bearishObs
        val nearOb = obs
            .filter { index - it.confirmedIndex <= freshness }
            .filter { near(it, snapshot.price, proximity) }
            .maxByOrNull { it.highProbability }
        if (nearOb != null) {
            factors.add("Order block" to W_OB)
            labels.add("OB" + if (nearOb.highProbability) " ★" else "")
        }

        val fvgs = if (buy) snapshot.bullishFvgs else snapshot.bearishFvgs
        val nearFvg = fvgs
            .filter { index - it.confirmedIndex <= freshness }
            .filter { near(it, snapshot.price, proximity) }
            .maxByOrNull { it.highProbability }
        if (nearFvg != null) {
            factors.add("Fair value gap" to W_FVG)
            labels.add("FVG")
        }

        val sweep = snapshot.recentSweep(!buy, sweepBars)
        if (sweep != null) {
            factors.add("Liquidity sweep" to W_LIQUIDITY)
            labels.add("Sweep")
        }

        val ote = snapshot.ote
        val inOte = ote != null && ote.isBullish == buy && ote.contains(snapshot.price)
        if (inOte) {
            factors.add("OTE 0.618–0.786" to W_OTE)
            labels.add("OTE")
        }

        if (snapshot.inKillZone) {
            factors.add("Kill zone" to W_KILLZONE)
            labels.add("Kill zone")
        }

        val breaker = series.breakersAt(index)
            .filter { it.isBullish == buy }
            .filter { near(it, snapshot.price, proximity) }
            .maxByOrNull { it.confirmedIndex }
        if (breaker != null) {
            factors.add("Breaker" to W_BREAKER)
            labels.add("Breaker")
        }

        // Hard requirements per strategy.
        val requireKillZone = config.param("requireKillZone", 0.0) > 0.5
        if (requireKillZone && !snapshot.inKillZone) return null
        when (def.id) {
            "ob_continuation" -> if (nearOb == null) return null
            "fvg_retrace" -> {
                if (nearFvg == null) return null
                if (config.param("requireOte", 0.0) > 0.5 && !inOte) return null
            }
            "choch_reversal" -> {
                val choch = if (buy) recentChochBuy else recentChochSell
                if (choch == null) return null
                if (nearOb == null && nearFvg == null) return null
                if (config.param("requireSweep", 0.0) > 0.5 && sweep == null) return null
            }
            "liquidity_sweep" -> if (sweep == null) return null
            "bos_momentum" -> {
                val bos = if (buy) recentBosBuy else recentBosSell
                if (bos == null) return null
            }
            "ote_precision" -> if (!inOte) return null
            "silver_bullet" -> {
                if (!snapshot.inKillZone) return null
                if (nearFvg == null) return null
                val bos = if (buy) recentBosBuy else recentBosSell
                if (bos == null) return null
            }
            "judas_swing" -> {
                if (sweep == null) return null
                if (!snapshot.inKillZone) return null
            }
            "breaker_block" -> if (breaker == null) return null
            "premium_discount" -> {
                val extreme = config.param("extreme", 30.0)
                if (buy && snapshot.zonePercent > extreme) return null
                if (!buy && snapshot.zonePercent < 100.0 - extreme) return null
            }
        }

        val score = factors.sumOf { it.second }
        if (factors.size < def.minConfluence) return null
        if (score < minScore) return null
        if (!snapshot.vixLevel.entryAllowed) return null

        val slDistance = stopDistance(buy, snapshot, nearOb, nearFvg, atr, config)
        if (slDistance <= atr * 0.25) return null
        val entry = snapshot.price
        val sl = if (buy) entry - slDistance else entry + slDistance
        val tp = if (buy) entry + slDistance * configRr else entry - slDistance * configRr
        val displayFactors = factors.map { it.first }.toMutableList()
        if (htfFactor != null) {
            labels.add(htfFactor)
            displayFactors.add(htfFactor)
        }
        val reason = labels.joinToString(" · ")

        return StrategySignal(
            side = side,
            stopLoss = sl,
            takeProfit = tp,
            reason = reason.ifBlank { "Confluence SMC" },
            score = score,
            factors = displayFactors,
        )
    }

    private fun stopDistance(
        buy: Boolean,
        snapshot: SmcSnapshot,
        ob: Zone?,
        fvg: Zone?,
        atr: Double,
        config: StrategyConfig,
    ): Double {
        val base = atr * config.param("atrMult", 1.4) * snapshot.vixLevel.slMultiplier
        val zones = listOfNotNull(ob, fvg)
        val entry = snapshot.price
        val zoneStop = if (buy) {
            zones.filter { it.bottom < entry }.minOfOrNull { it.bottom }
        } else {
            zones.filter { it.top > entry }.maxOfOrNull { it.top }
        }
        return if (zoneStop != null) {
            max(base, abs(entry - zoneStop) + atr * 0.15)
        } else {
            base
        }
    }

    private fun near(zone: Zone, price: Double, tolerance: Double): Boolean =
        price >= zone.bottom - tolerance && price <= zone.top + tolerance

    /** Best generic confluence for the chart signal card. */
    fun confluence(
        series: SmcSeries,
        snapshot: SmcSnapshot,
        htfTrend: Trend? = null,
        htfFilter: Boolean = false,
    ): ConfluenceResult {
        if (!series.isReady) return ConfluenceResult(null, 0.0, 0, emptyList())
        val index = snapshot.index
        val atr = series.atrAt(index)
        val proximity = atr * 0.6
        val scores = ArrayList<ConfluenceResult>()
        val htfBias = htfTrend?.takeIf { it != Trend.RANGING }

        for (buy in listOf(true, false)) {
            if (htfFilter && htfBias != null) {
                val agrees = if (buy) htfBias == Trend.BULLISH else htfBias == Trend.BEARISH
                if (!agrees) continue
            }
            val factors = ArrayList<Pair<String, Double>>()
            val trendOk = if (buy) snapshot.trend == Trend.BULLISH else snapshot.trend == Trend.BEARISH
            if (trendOk) factors.add("Structure" to W_STRUCTURE)
            val obs = if (buy) snapshot.bullishObs else snapshot.bearishObs
            if (obs.any { near(it, snapshot.price, proximity) && index - it.confirmedIndex <= 60 }) {
                factors.add("Order block" to W_OB)
            }
            val fvgs = if (buy) snapshot.bullishFvgs else snapshot.bearishFvgs
            if (fvgs.any { near(it, snapshot.price, proximity) && index - it.confirmedIndex <= 60 }) {
                factors.add("FVG" to W_FVG)
            }
            if (snapshot.recentSweep(!buy, 10) != null) factors.add("Liquidity sweep" to W_LIQUIDITY)
            val ote = snapshot.ote
            if (ote != null && ote.isBullish == buy && ote.contains(snapshot.price)) {
                factors.add("OTE" to W_OTE)
            }
            if (snapshot.inKillZone) factors.add("Kill zone" to W_KILLZONE)
            if (series.breakersAt(index).any { it.isBullish == buy && near(it, snapshot.price, proximity) }) {
                factors.add("Breaker" to W_BREAKER)
            }
            val score = factors.sumOf { it.second }
            if (factors.isNotEmpty()) {
                scores.add(
                    ConfluenceResult(
                        side = if (buy) Side.BUY else Side.SELL,
                        score = score,
                        factorCount = factors.size,
                        factors = factors,
                    )
                )
            }
        }
        return scores.maxByOrNull { it.score } ?: ConfluenceResult(null, 0.0, 0, emptyList())
    }

    /** Suggested SL/TP for a manual trade from the current setup. */
    fun manualLevels(
        side: Side,
        series: SmcSeries,
        snapshot: SmcSnapshot,
        rr: Double = 2.0,
        atrMult: Double = 1.2,
    ): Pair<Double, Double> {
        val atr = series.atrAt(snapshot.index)
        val dist = atr * atrMult * snapshot.vixLevel.slMultiplier
        val entry = snapshot.price
        return if (side == Side.BUY) {
            entry - dist to entry + dist * rr
        } else {
            entry + dist to entry - dist * rr
        }
    }

    fun rrOf(side: Side, entry: Double, sl: Double, tp: Double): Double {
        val risk = abs(entry - sl)
        if (risk <= 1e-9) return 0.0
        val reward = if (side == Side.BUY) tp - entry else entry - tp
        return reward / risk
    }

    fun suggestedLots(
        balance: Double,
        riskPercent: Double,
        entry: Double,
        stopLoss: Double,
        spec: com.smclab.autotrade.model.SymbolSpec,
        leverage: Int,
    ): Double {
        val riskAmount = balance * (riskPercent / 100.0).coerceIn(0.05, 10.0)
        val pipDistance = abs(entry - stopLoss) / spec.pipSize
        if (pipDistance < 1.0) return 0.0
        val pipValue = MarketData.pipValue(spec, 1.0, entry)
        if (pipValue <= 0.0) return 0.0
        val raw = riskAmount / (pipDistance * pipValue)
        val marginPerLot = spec.contractSize * entry / leverage.coerceAtLeast(1)
        val marginCap = if (marginPerLot > 0) balance * 0.8 / marginPerLot else raw
        return min(min(raw, marginCap), 100.0).let { max(0.01, kotlin.math.floor(it * 100.0) / 100.0) }
    }
}
