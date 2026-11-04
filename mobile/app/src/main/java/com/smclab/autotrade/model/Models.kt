package com.smclab.autotrade.model

import java.util.Locale

enum class Timeframe(val minutes: Int, val label: String) {
    M5(5, "M5"),
    M15(15, "M15"),
    H1(60, "H1"),
    H4(240, "H4"),
    D1(1440, "D1");

    /** Next higher timeframe used for multi-timeframe confirmation. Null on D1. */
    fun higher(): Timeframe? = when (this) {
        M5 -> M15
        M15 -> H1
        H1 -> H4
        H4 -> D1
        D1 -> null
    }

    companion object {
        fun fromLabel(label: String): Timeframe = entries.firstOrNull { it.label == label } ?: H1
    }
}

data class SymbolSpec(
    val symbol: String,
    val description: String,
    val base: String,
    val quote: String,
    val startPrice: Double,
    val digits: Int,
    val pipSize: Double,
    val contractSize: Double,
    val spreadPips: Double,
    val volatility: Double,
    val trendBias: Double,
) {
    val spreadPrice: Double get() = spreadPips * pipSize

    fun formatPrice(value: Double): String =
        String.format(Locale.US, "%,.${digits}f", value)

    fun formatPips(priceDistance: Double): String =
        String.format(Locale.US, "%.1f", priceDistance / pipSize)

    val tickSize: Double get() = if (digits >= 4) Math.pow(10.0, -digits.toDouble()) else Math.pow(10.0, -digits.toDouble())
}

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
) {
    val bullish: Boolean get() = close >= open
    val body: Double get() = kotlin.math.abs(close - open)
    val range: Double get() = (high - low).coerceAtLeast(1e-9)
}

data class Quote(
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val prevClose: Double,
    val sparkline: List<Double> = emptyList(),
) {
    val mid: Double get() = (bid + ask) / 2.0
    val changePct: Double get() = if (prevClose > 0) (mid - prevClose) / prevClose * 100.0 else 0.0
    val spread: Double get() = ask - bid
}

enum class Side { BUY, SELL;
    val sign: Int get() = if (this == BUY) 1 else -1
    val label: String get() = if (this == BUY) "BUY" else "SELL"
}

data class Account(
    var balance: Double = 10_000.0,
    var equity: Double = 10_000.0,
    var margin: Double = 0.0,
    var freeMargin: Double = 10_000.0,
    var marginLevel: Double = 0.0,
    var leverage: Int = 200,
    var currency: String = "USD",
    var floatingPl: Double = 0.0,
    var closedPlToday: Double = 0.0,
    /** MT5-style account identity. Empty for the local demo session. */
    var login: String = "",
    var server: String = "",
    var broker: String = "",
    var connected: Boolean = false,
) {
    val displayName: String get() = when {
        login.isNotBlank() && server.isNotBlank() -> "#$login · $server"
        login.isNotBlank() -> "#$login"
        else -> "Tài khoản demo cục bộ"
    }
    val isMt5Session: Boolean get() = connected && login.isNotBlank()
}

data class Position(
    val id: Long,
    val symbol: String,
    val side: Side,
    val lots: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val openTime: Long,
    val strategyId: String? = null,
    val comment: String = "",
    var currentPrice: Double = entryPrice,
    var profit: Double = 0.0,
) {
    val key: String get() = "$symbol-$id"
}

enum class CloseReason { STOP_LOSS, TAKE_PROFIT, MANUAL, STRATEGY, STOP_OUT, END_OF_TEST }

data class TradeHistory(
    val id: Long,
    val symbol: String,
    val side: Side,
    val lots: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val openTime: Long,
    val closeTime: Long,
    val profit: Double,
    val commission: Double,
    val reason: CloseReason,
    val strategyId: String? = null,
    val comment: String = "",
)

enum class Trend { BULLISH, BEARISH, RANGING;
    val label: String get() = when (this) {
        BULLISH -> "Tăng"
        BEARISH -> "Giảm"
        RANGING -> "Đi ngang"
    }
}

enum class ZoneState { FRESH, TESTED, MITIGATED, BROKEN }

enum class VixLevel { LOW, NORMAL, HIGH, EXTREME;
    val label: String get() = when (this) {
        LOW -> "Thấp"
        NORMAL -> "Bình thường"
        HIGH -> "Cao"
        EXTREME -> "Cực đoan"
    }
    val entryAllowed: Boolean get() = this != EXTREME
    val lotMultiplier: Double get() = when (this) {
        LOW -> 1.2; NORMAL -> 1.0; HIGH -> 0.7; EXTREME -> 0.3
    }
    val slMultiplier: Double get() = when (this) {
        LOW -> 0.8; NORMAL -> 1.0; HIGH -> 1.5; EXTREME -> 2.0
    }
}

data class SwingPoint(
    val index: Int,
    val confirmedIndex: Int,
    val price: Double,
    val isHigh: Boolean,
    val strength: Int,
    val broken: Boolean = false,
)

data class Zone(
    val id: Long,
    val kind: ZoneKind,
    val isBullish: Boolean,
    val top: Double,
    val bottom: Double,
    val formationIndex: Int,
    val confirmedIndex: Int,
    val brokenIndex: Int = -1,
    val testedIndex: Int = -1,
    val mitigatedIndex: Int = -1,
    val expiryIndex: Int = -1,
    val highProbability: Boolean = false,
) {
    val mid: Double get() = (top + bottom) / 2.0
    fun knownAt(index: Int): Boolean = index >= confirmedIndex
    fun activeAt(index: Int): Boolean =
        index >= confirmedIndex &&
            (brokenIndex < 0 || index < brokenIndex) &&
            (expiryIndex < 0 || index < expiryIndex)

    fun stateAt(index: Int): ZoneState = when {
        brokenIndex in 0..index -> ZoneState.BROKEN
        mitigatedIndex in 0..index -> ZoneState.MITIGATED
        testedIndex in 0..index -> ZoneState.TESTED
        else -> ZoneState.FRESH
    }

    fun isFreshAt(index: Int): Boolean = activeAt(index) && stateAt(index) == ZoneState.FRESH && index > confirmedIndex
}

enum class ZoneKind { OB, FVG, BREAKER }

data class StructureBreak(
    val index: Int,
    val isBullish: Boolean,
    val isBos: Boolean,
    val closePrice: Double,
    val swingPrice: Double,
) {
    val label: String get() = if (isBos) "BOS" else "CHoCH"
}

data class LiquidityLevel(
    val price: Double,
    val isHigh: Boolean,
    val touches: Int,
    val confirmedIndex: Int,
    val sweepIndex: Int = -1,
    val lastTouchIndex: Int = confirmedIndex,
) {
    val isSweptAt: (Int) -> Boolean = { index -> sweepIndex >= 0 && sweepIndex <= index }
    val isPool: Boolean get() = touches >= 3
}

data class OteInfo(
    val isBullish: Boolean,
    val swingHigh: Double,
    val swingLow: Double,
    val fib618: Double,
    val fib705: Double,
    val fib786: Double,
    val top: Double,
    val bottom: Double,
) {
    fun contains(price: Double): Boolean = price in bottom..top
}

data class SessionState(
    val name: String,
    val active: Boolean,
    val inKillZone: Boolean,
)

data class SmcSnapshot(
    val index: Int,
    val price: Double,
    val trend: Trend,
    val swingsHigh: List<SwingPoint>,
    val swingsLow: List<SwingPoint>,
    val breaks: List<StructureBreak>,
    val lastBos: StructureBreak?,
    val lastChoch: StructureBreak?,
    val bullishObs: List<Zone>,
    val bearishObs: List<Zone>,
    val bullishFvgs: List<Zone>,
    val bearishFvgs: List<Zone>,
    val liquidity: List<LiquidityLevel>,
    val premiumDiscountHigh: Double,
    val premiumDiscountLow: Double,
    val equilibrium: Double,
    val zonePercent: Double,
    val ote: OteInfo?,
    val sessions: List<SessionState>,
    val inKillZone: Boolean,
    val vix: Double,
    val vixLevel: VixLevel,
) {
    val isPremium: Boolean get() = price > equilibrium
    val isDiscount: Boolean get() = price < equilibrium

    fun recentBos(withinBars: Int): StructureBreak? =
        lastBos?.takeIf { index - it.index <= withinBars }

    fun recentChoch(withinBars: Int): StructureBreak? =
        lastChoch?.takeIf { index - it.index <= withinBars }

    fun recentSweep(isHigh: Boolean, withinBars: Int): LiquidityLevel? =
        liquidity.firstOrNull {
            it.isHigh == isHigh && it.sweepIndex >= 0 && index - it.sweepIndex <= withinBars
        }

    fun nearestZone(zones: List<Zone>, price: Double): Zone? =
        zones.filter { it.activeAt(index) }.minByOrNull { kotlin.math.abs(it.mid - price) }

    fun activeZones(zones: List<Zone>): List<Zone> = zones.filter { it.activeAt(index) }
}

data class ParamSpec(
    val key: String,
    val label: String,
    val defaultValue: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String = "",
    val decimals: Int = 1,
)

data class StrategyDef(
    val id: String,
    val name: String,
    val category: String,
    val tagline: String,
    val description: String,
    val minConfluence: Int,
    val defaults: Map<String, Double>,
    val params: List<ParamSpec>,
    val defaultRisk: Double = 1.0,
    val defaultRr: Double = 2.0,
    val minBars: Int = 320,
)

data class StrategyConfig(
    val strategyId: String,
    var enabled: Boolean = true,
    val params: MutableMap<String, Double> = mutableMapOf(),
    var riskPercent: Double = 1.0,
    var riskReward: Double = 2.0,
    /** Require the higher-timeframe trend to agree with the trade direction. */
    var useHtf: Boolean = true,
) {
    fun param(key: String, fallback: Double = 0.0): Double = params[key] ?: fallback
}

data class StrategySignal(
    val side: Side,
    val stopLoss: Double,
    val takeProfit: Double,
    val reason: String,
    val score: Double,
    val factors: List<String> = emptyList(),
)

data class ConfluenceResult(
    val side: Side?,
    val score: Double,
    val factorCount: Int,
    val factors: List<Pair<String, Double>>,
)

enum class BacktestStatus { IDLE, RUNNING, DONE, ERROR }

data class BacktestTrade(
    val index: Int,
    val side: Side,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val lots: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val profit: Double,
    val commission: Double,
    val rMultiple: Double,
    val reason: CloseReason,
) {
    val net: Double get() = profit - commission
}

data class BacktestMetrics(
    val initialBalance: Double,
    val finalBalance: Double,
    val netProfit: Double,
    val returnPct: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val profitFactor: Double,
    val maxDrawdownPct: Double,
    val maxDrawdownAbs: Double,
    val expectancy: Double,
    val avgR: Double,
    val sharpe: Double,
    val maxConsecutiveLosses: Int,
    val longTrades: Int,
    val shortTrades: Int,
)

data class BacktestResult(
    val id: String,
    val createdAt: Long,
    val strategyId: String,
    val strategyName: String,
    val symbol: String,
    val timeframe: Timeframe,
    val bars: Int,
    val riskPercent: Double,
    val riskReward: Double,
    val metrics: BacktestMetrics,
    val equityCurve: List<Double>,
    val trades: List<BacktestTrade>,
    val params: Map<String, Double>,
    val dataNote: String = "Dữ liệu tổng hợp cục bộ",
    /** Higher timeframe used as confirmation, or null when the filter was off. */
    val htfTimeframe: Timeframe? = null,
)

data class AppSettings(
    var initialBalance: Double = 10_000.0,
    var leverage: Int = 200,
    var defaultRisk: Double = 1.0,
    var commissionPerLot: Double = 7.0,
    /** Global switch for higher-timeframe confirmation in chart, auto-trade and backtest. */
    var autoHtfFilter: Boolean = true,
)
