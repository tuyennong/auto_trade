package com.smclab.autotrade.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.smclab.autotrade.engine.MarketData
import com.smclab.autotrade.engine.MultiTimeframe
import com.smclab.autotrade.engine.SmcSeries
import com.smclab.autotrade.engine.StrategyCatalog
import com.smclab.autotrade.engine.StrategyEvaluator
import com.smclab.autotrade.model.Account
import com.smclab.autotrade.model.AppSettings
import com.smclab.autotrade.model.BacktestResult
import com.smclab.autotrade.model.CloseReason
import com.smclab.autotrade.model.Position
import com.smclab.autotrade.model.Quote
import com.smclab.autotrade.model.Side
import com.smclab.autotrade.model.StrategyConfig
import com.smclab.autotrade.model.Timeframe
import com.smclab.autotrade.model.TradeHistory
import com.smclab.autotrade.model.Trend
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * Single source of truth for the demo terminal.
 *
 * Owns the synthetic market feed, the persisted demo account, open positions,
 * trade history, strategy settings and the one-second tick loop. Everything is
 * local: there is no broker connection and no order routing.
 */
object AppRepository {

    data class Message(val text: String)

    private const val PREFS_NAME = "smc_trader_demo"

    private const val KEY_SETTINGS = "settings"
    private const val KEY_CONFIGS = "configs"
    private const val KEY_POSITIONS = "positions"
    private const val KEY_HISTORY = "history"
    private const val KEY_BALANCE = "balance"
    private const val KEY_LEVERAGE = "leverage"
    private const val KEY_LOGIN = "login"
    private const val KEY_SERVER = "server"
    private const val KEY_BROKER = "broker"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_AUTO_TRADE = "auto_trade"
    private const val KEY_CHART_STRATEGY = "chart_strategy"
    private const val KEY_SYMBOL = "symbol"
    private const val KEY_TIMEFRAME = "timeframe"

    private lateinit var prefs: SharedPreferences
    private var initialized = false
    private var idCounter = System.currentTimeMillis()

    /** Last closed bar already evaluated for each symbol/timeframe key. */
    private val lastAutoBar = HashMap<String, Long>()

    private val _quotes = MutableLiveData<List<Quote>>(emptyList())
    val quotes: LiveData<List<Quote>> = _quotes

    private val _account = MutableLiveData(Account())
    val account: LiveData<Account> = _account

    private val _positions = MutableLiveData<List<Position>>(emptyList())
    val positions: LiveData<List<Position>> = _positions

    private val _history = MutableLiveData<List<TradeHistory>>(emptyList())
    val history: LiveData<List<TradeHistory>> = _history

    private val _settings = MutableLiveData(AppSettings())
    val settings: LiveData<AppSettings> = _settings

    private val _configs = MutableLiveData<List<StrategyConfig>>(emptyList())
    val configs: LiveData<List<StrategyConfig>> = _configs

    private val _autoTrade = MutableLiveData(false)
    val autoTrade: LiveData<Boolean> = _autoTrade

    private val _chartStrategy = MutableLiveData(MultiTimeframe.AUTO)
    val chartStrategy: LiveData<String> = _chartStrategy

    private val _selectedSymbol = MutableLiveData("XAUUSD")
    val selectedSymbol: LiveData<String> = _selectedSymbol

    private val _selectedTimeframe = MutableLiveData(Timeframe.H1)
    val selectedTimeframe: LiveData<Timeframe> = _selectedTimeframe

    private val _message = MutableLiveData<Message?>(null)
    val message: LiveData<Message?> = _message

    private val _backtests = MutableLiveData<List<BacktestResult>>(emptyList())
    val backtests: LiveData<List<BacktestResult>> = _backtests

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val loadedSettings = loadSettings()
        _settings.value = loadedSettings

        _autoTrade.value = prefs.getBoolean(KEY_AUTO_TRADE, false)
        _chartStrategy.value = prefs.getString(KEY_CHART_STRATEGY, MultiTimeframe.AUTO)
            ?.takeIf { it.isNotBlank() } ?: MultiTimeframe.AUTO
        _selectedSymbol.value = prefs.getString(KEY_SYMBOL, "XAUUSD")
            ?.takeIf { MarketData.contains(it) } ?: "XAUUSD"
        _selectedTimeframe.value = Timeframe.fromLabel(
            prefs.getString(KEY_TIMEFRAME, Timeframe.H1.label) ?: Timeframe.H1.label,
        )

        _configs.value = loadConfigs()
        _account.value = loadAccount(loadedSettings)
        _positions.value = loadPositions()
        _history.value = loadHistory()

        idCounter = max(
            idCounter,
            (_positions.value.orEmpty().maxOfOrNull { it.id } ?: 0L) + 1,
        )

        val quotesNow = MarketData.allQuotes()
        _quotes.value = quotesNow
        syncPositions(quotesNow, allowCloses = false)
        updateAccount()
        initialized = true
    }

    /**
     * One-second tick. Updates the synthetic market, floating P/L, SL/TP
     * executions and, when enabled, evaluates closed bars for auto trading.
     */
    fun onTick() {
        if (!initialized) return
        val quotesNow = MarketData.tick()
        _quotes.value = quotesNow
        syncPositions(quotesNow, allowCloses = true)
        updateAccount()
        if (_autoTrade.value == true) maybeAutoTrade()
    }

    // ------------------------------------------------------------------
    // Account / positions
    // ------------------------------------------------------------------

    fun openPosition(
        symbol: String,
        side: Side,
        lots: Double,
        stopLoss: Double,
        takeProfit: Double,
        strategyId: String? = null,
        comment: String = "",
    ): String? {
        if (!MarketData.contains(symbol)) return "Cặp giao dịch không được hỗ trợ"
        if (!lots.isFinite() || lots < 0.01) return "Khối lượng tối thiểu là 0.01 lot"
        if (lots > 100.0) return "Khối lượng tối đa là 100 lot"

        val spec = MarketData.spec(symbol)
        val quote = MarketData.quote(symbol)
        val entry = if (side == Side.BUY) quote.ask else quote.bid
        if (entry <= 0.0) return "Chưa có giá cho $symbol"

        if (side == Side.BUY) {
            if (stopLoss > 0.0 && stopLoss >= entry) return "SL phải thấp hơn giá vào"
            if (takeProfit > 0.0 && takeProfit <= entry) return "TP phải cao hơn giá vào"
        } else {
            if (stopLoss > 0.0 && stopLoss <= entry) return "SL phải cao hơn giá vào"
            if (takeProfit > 0.0 && takeProfit >= entry) return "TP phải thấp hơn giá vào"
        }

        val current = _account.value ?: Account()
        val leverage = current.leverage.coerceAtLeast(1)
        val requiredMargin = spec.contractSize * entry * lots / leverage
        if (requiredMargin > current.freeMargin + 1e-9) return "Không đủ ký quỹ"

        val position = Position(
            id = nextId(),
            symbol = symbol,
            side = side,
            lots = lots,
            entryPrice = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            openTime = System.currentTimeMillis(),
            strategyId = strategyId,
            comment = comment,
            currentPrice = entry,
            profit = 0.0,
        )
        _positions.value = _positions.value.orEmpty() + position
        savePositions()
        updateAccount()
        return null
    }

    fun closePosition(positionId: Long) {
        val position = _positions.value.orEmpty().firstOrNull { it.id == positionId } ?: return
        val quote = MarketData.quote(position.symbol)
        val exitPrice = if (position.side == Side.BUY) quote.bid else quote.ask
        _positions.value = _positions.value.orEmpty().filterNot { it.id == positionId }
        bookClose(position, exitPrice, CloseReason.MANUAL)
        savePositions()
        saveHistory()
        saveAccount()
        updateAccount()
        notify("Đã đóng ${position.side.label} ${position.symbol} · ${position.lots} lot")
    }

    fun modifyPosition(positionId: Long, stopLoss: Double, takeProfit: Double): String? {
        val position = _positions.value.orEmpty().firstOrNull { it.id == positionId }
            ?: return "Vị thế không tồn tại"
        val price = if (position.currentPrice > 0.0) position.currentPrice else position.entryPrice

        if (position.side == Side.BUY) {
            if (stopLoss > 0.0 && stopLoss >= price) return "SL phải thấp hơn giá hiện tại"
            if (takeProfit > 0.0 && takeProfit <= price) return "TP phải cao hơn giá hiện tại"
        } else {
            if (stopLoss > 0.0 && stopLoss <= price) return "SL phải cao hơn giá hiện tại"
            if (takeProfit > 0.0 && takeProfit >= price) return "TP phải thấp hơn giá hiện tại"
        }

        _positions.value = _positions.value.orEmpty().map {
            if (it.id == positionId) it.copy(stopLoss = stopLoss, takeProfit = takeProfit) else it
        }
        savePositions()
        updateAccount()
        return null
    }

    /**
     * Explicit demo reset. Login/server identity, strategies and settings are
     * preserved; balance, positions and history are cleared.
     */
    fun resetAccount() {
        val current = _account.value ?: Account()
        val loadedSettings = _settings.value ?: AppSettings()
        val balance = loadedSettings.initialBalance
        _positions.value = emptyList()
        _history.value = emptyList()
        _account.value = current.copy(
            balance = balance,
            equity = balance,
            margin = 0.0,
            freeMargin = balance,
            marginLevel = 0.0,
            floatingPl = 0.0,
            closedPlToday = 0.0,
            leverage = loadedSettings.leverage,
        )
        savePositions()
        saveHistory()
        saveAccount()
        notify("Đã đặt lại tài khoản demo")
    }

    /** Local, clearly labelled MT5 demo session. The password is never stored. */
    fun connectMt5Account(login: String, server: String, broker: String, password: String): String? {
        if (!login.matches(Regex("\\d{4,12}"))) return "Số tài khoản phải gồm 4–12 chữ số"
        if (server.isBlank()) return "Chọn máy chủ giao dịch"
        if (password.length < 4) return "Mật khẩu tối thiểu 4 ký tự"

        val current = _account.value ?: Account()
        _account.value = current.copy(
            login = login.trim(),
            server = server.trim(),
            broker = broker.trim(),
            connected = true,
        )
        saveAccount()
        notify("Đã đăng nhập phiên MT5 demo #${login.trim()}")
        return null
    }

    fun disconnectMt5Account() {
        val current = _account.value ?: Account()
        _account.value = current.copy(
            login = "",
            server = "",
            broker = "",
            connected = false,
        )
        saveAccount()
        notify("Đã đăng xuất phiên MT5 demo")
    }

    // ------------------------------------------------------------------
    // Settings / strategies
    // ------------------------------------------------------------------

    fun saveSettings(updated: AppSettings) {
        _settings.value = updated
        val current = _account.value ?: Account()
        if (current.leverage != updated.leverage) {
            _account.value = current.copy(leverage = updated.leverage)
            saveAccount()
        }
        prefs.edit().putString(KEY_SETTINGS, settingsToJson(updated).toString()).apply()
        updateAccount()
    }

    fun configFor(strategyId: String): StrategyConfig {
        return _configs.value.orEmpty().firstOrNull { it.strategyId == strategyId }
            ?: StrategyCatalog.byId(strategyId)?.let { StrategyCatalog.defaultConfig(it) }
            ?: StrategyConfig(strategyId)
    }

    fun saveStrategy(updated: StrategyConfig) {
        val current = _configs.value.orEmpty()
        val next = if (current.any { it.strategyId == updated.strategyId }) {
            current.map { if (it.strategyId == updated.strategyId) updated else it }
        } else {
            current + updated
        }
        _configs.value = next
        saveConfigs(next)
    }

    fun setStrategyEnabled(strategyId: String, enabled: Boolean) {
        saveStrategy(configFor(strategyId).copy(enabled = enabled))
    }

    fun setAutoTrade(enabled: Boolean) {
        if (_autoTrade.value == enabled) return
        _autoTrade.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_TRADE, enabled).apply()
        if (enabled) lastAutoBar.clear()
        notify(if (enabled) "Đã bật tự động giao dịch demo" else "Đã tắt tự động giao dịch")
    }

    fun saveBacktest(result: BacktestResult) {
        _backtests.value = (_backtests.value.orEmpty() + result).takeLast(20)
    }

    fun selectSymbol(symbol: String) {
        if (!MarketData.contains(symbol)) return
        if (_selectedSymbol.value == symbol) return
        _selectedSymbol.value = symbol
        prefs.edit().putString(KEY_SYMBOL, symbol).apply()
    }

    fun selectTimeframe(timeframe: Timeframe) {
        if (_selectedTimeframe.value == timeframe) return
        _selectedTimeframe.value = timeframe
        prefs.edit().putString(KEY_TIMEFRAME, timeframe.label).apply()
    }

    fun selectChartStrategy(strategyId: String) {
        if (_chartStrategy.value == strategyId) return
        _chartStrategy.value = strategyId
        prefs.edit().putString(KEY_CHART_STRATEGY, strategyId).apply()
    }

    fun notify(text: String) {
        _message.value = Message(text)
    }

    fun reasonLabel(reason: CloseReason): String = when (reason) {
        CloseReason.STOP_LOSS -> "Chạm SL"
        CloseReason.TAKE_PROFIT -> "Chạm TP"
        CloseReason.MANUAL -> "Đóng tay"
        CloseReason.STRATEGY -> "Tín hiệu chiến lược"
        CloseReason.STOP_OUT -> "Stop out"
        CloseReason.END_OF_TEST -> "Kết thúc test"
    }

    // ------------------------------------------------------------------
    // Tick / auto trading internals
    // ------------------------------------------------------------------

    private fun syncPositions(quotesNow: List<Quote>, allowCloses: Boolean) {
        val bySymbol = quotesNow.associateBy { it.symbol }
        val open = _positions.value.orEmpty()
        if (open.isEmpty()) return

        val kept = ArrayList<Position>(open.size)
        val closes = ArrayList<Triple<Position, Double, CloseReason>>()

        for (position in open) {
            val quote = bySymbol[position.symbol]
            if (quote == null) {
                kept.add(position)
                continue
            }
            val exitPrice = if (position.side == Side.BUY) quote.bid else quote.ask
            val updated = position.copy(
                currentPrice = exitPrice,
                profit = profitOf(position, exitPrice),
            )
            val event = if (allowCloses) closeEvent(updated, quote) else null
            if (event != null) {
                closes.add(event)
            } else {
                kept.add(updated)
            }
        }

        _positions.value = kept
        if (closes.isEmpty()) return
        for ((position, exitPrice, reason) in closes) {
            bookClose(position, exitPrice, reason)
        }
        savePositions()
        saveHistory()
        saveAccount()
    }

    private fun closeEvent(
        position: Position,
        quote: Quote,
    ): Triple<Position, Double, CloseReason>? {
        return if (position.side == Side.BUY) {
            when {
                position.stopLoss > 0.0 && quote.bid <= position.stopLoss ->
                    Triple(position, quote.bid, CloseReason.STOP_LOSS)
                position.takeProfit > 0.0 && quote.bid >= position.takeProfit ->
                    Triple(position, quote.bid, CloseReason.TAKE_PROFIT)
                else -> null
            }
        } else {
            when {
                position.stopLoss > 0.0 && quote.ask >= position.stopLoss ->
                    Triple(position, quote.ask, CloseReason.STOP_LOSS)
                position.takeProfit > 0.0 && quote.ask <= position.takeProfit ->
                    Triple(position, quote.ask, CloseReason.TAKE_PROFIT)
                else -> null
            }
        }
    }

    private fun bookClose(position: Position, exitPrice: Double, reason: CloseReason) {
        val gross = profitOf(position, exitPrice)
        val commission = position.lots * (_settings.value?.commissionPerLot ?: 0.0)
        val current = _account.value ?: Account()
        _account.value = current.copy(balance = current.balance + gross - commission)

        val trade = TradeHistory(
            id = position.id,
            symbol = position.symbol,
            side = position.side,
            lots = position.lots,
            entryPrice = position.entryPrice,
            exitPrice = exitPrice,
            openTime = position.openTime,
            closeTime = System.currentTimeMillis(),
            profit = gross,
            commission = commission,
            reason = reason,
            strategyId = position.strategyId,
            comment = position.comment,
        )
        _history.value = (listOf(trade) + _history.value.orEmpty()).take(500)
    }

    private fun updateAccount() {
        val current = _account.value ?: Account()
        val open = _positions.value.orEmpty()
        val floating = open.sumOf { it.profit }
        val equity = current.balance + floating

        var margin = 0.0
        for (position in open) {
            val spec = MarketData.spec(position.symbol)
            val price = if (position.currentPrice > 0.0) position.currentPrice else position.entryPrice
            margin += spec.contractSize * position.lots * price / current.leverage.coerceAtLeast(1)
        }

        val freeMargin = equity - margin
        val marginLevel = if (margin > 0.0) equity / margin * 100.0 else 0.0
        val dayStart = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val closedToday = _history.value.orEmpty()
            .filter { it.closeTime >= dayStart }
            .sumOf { it.profit - it.commission }

        _account.value = current.copy(
            equity = equity,
            margin = margin,
            freeMargin = freeMargin,
            marginLevel = marginLevel,
            floatingPl = floating,
            closedPlToday = closedToday,
        )
    }

    private fun maybeAutoTrade() {
        val symbol = _selectedSymbol.value ?: return
        val timeframe = _selectedTimeframe.value ?: return
        val loadedSettings = _settings.value ?: return
        val enabled = _configs.value.orEmpty().filter { it.enabled }
        if (enabled.isEmpty()) return

        val closed = MarketData.closedCandles(symbol, timeframe, 420)
        if (closed.size < 80) return
        val key = "$symbol:${timeframe.name}"
        val barTime = closed.last().time
        if (lastAutoBar[key] == barTime) return
        lastAutoBar[key] = barTime

        val series = SmcSeries(closed, MarketData.spec(symbol), timeframe)
        if (!series.isReady) return
        val snapshot = series.snapshotAt(series.size - 1)
        val htfTrend: Trend? = if (loadedSettings.autoHtfFilter) {
            MultiTimeframe.context(symbol, timeframe)?.trend
        } else {
            null
        }

        val balance = _account.value?.balance ?: loadedSettings.initialBalance
        val leverage = _account.value?.leverage ?: loadedSettings.leverage

        for (config in enabled) {
            val def = StrategyCatalog.byId(config.strategyId) ?: continue
            val alreadyOpen = _positions.value.orEmpty().any {
                it.symbol == symbol && it.strategyId == def.id
            }
            if (alreadyOpen) continue

            val signal = StrategyEvaluator.evaluate(
                def = def,
                series = series,
                snapshot = snapshot,
                config = config,
                htfTrend = htfTrend,
            ) ?: continue

            val lots = StrategyEvaluator.suggestedLots(
                balance = balance,
                riskPercent = config.riskPercent,
                entry = snapshot.price,
                stopLoss = signal.stopLoss,
                spec = MarketData.spec(symbol),
                leverage = leverage,
            )
            if (lots < 0.01) continue

            val error = openPosition(
                symbol = symbol,
                side = signal.side,
                lots = lots,
                stopLoss = signal.stopLoss,
                takeProfit = signal.takeProfit,
                strategyId = def.id,
                comment = "Auto",
            )
            if (error == null) {
                notify("Tự động · ${signal.side.label} $symbol · ${def.name}")
            }
        }
    }

    private fun profitOf(position: Position, price: Double): Double {
        val spec = MarketData.spec(position.symbol)
        if (spec.pipSize <= 0.0) return 0.0
        val distance = (price - position.entryPrice) * position.side.sign
        return (distance / spec.pipSize) * MarketData.pipValue(spec, position.lots, price)
    }

    private fun nextId(): Long {
        idCounter = max(idCounter + 1, System.currentTimeMillis())
        return idCounter
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching {
            val json = JSONObject(raw)
            AppSettings(
                initialBalance = json.optDouble("initialBalance", 10_000.0),
                leverage = json.optInt("leverage", 200),
                defaultRisk = json.optDouble("defaultRisk", 1.0),
                commissionPerLot = json.optDouble("commissionPerLot", 7.0),
                autoHtfFilter = json.optBoolean("autoHtfFilter", true),
            )
        }.getOrDefault(AppSettings())
    }

    private fun settingsToJson(settings: AppSettings): JSONObject = JSONObject()
        .put("initialBalance", settings.initialBalance)
        .put("leverage", settings.leverage)
        .put("defaultRisk", settings.defaultRisk)
        .put("commissionPerLot", settings.commissionPerLot)
        .put("autoHtfFilter", settings.autoHtfFilter)

    private fun loadConfigs(): List<StrategyConfig> {
        val defaults = StrategyCatalog.all.map { StrategyCatalog.defaultConfig(it) }
        val raw = prefs.getString(KEY_CONFIGS, null) ?: return defaults
        val saved = runCatching { JSONArray(raw) }.getOrNull() ?: return defaults

        val byId = LinkedHashMap<String, StrategyConfig>()
        for (index in 0 until saved.length()) {
            val json = saved.optJSONObject(index) ?: continue
            val id = json.optString("id")
            val def = StrategyCatalog.byId(id) ?: continue
            val params = def.defaults.toMutableMap()
            json.optJSONObject("params")?.let { jsonParams ->
                val keys = jsonParams.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    params[key] = jsonParams.optDouble(key, params[key] ?: 0.0)
                }
            }
            byId[id] = StrategyConfig(
                strategyId = id,
                enabled = json.optBoolean("enabled", def.id == "ob_continuation" || def.id == "fvg_retrace"),
                params = params,
                riskPercent = json.optDouble("riskPercent", def.defaultRisk),
                riskReward = json.optDouble("riskReward", def.defaultRr),
                useHtf = json.optBoolean("useHtf", true),
            )
        }
        return StrategyCatalog.all.map { byId[it.id] ?: StrategyCatalog.defaultConfig(it) }
    }

    private fun saveConfigs(configs: List<StrategyConfig>) {
        val array = JSONArray()
        for (config in configs) {
            val params = JSONObject()
            config.params.forEach { (key, value) -> params.put(key, value) }
            array.put(
                JSONObject()
                    .put("id", config.strategyId)
                    .put("enabled", config.enabled)
                    .put("params", params)
                    .put("riskPercent", config.riskPercent)
                    .put("riskReward", config.riskReward)
                    .put("useHtf", config.useHtf),
            )
        }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()
    }

    private fun loadAccount(loadedSettings: AppSettings): Account {
        val balance = prefs.getString(KEY_BALANCE, null)?.toDoubleOrNull()
            ?: loadedSettings.initialBalance
        return Account(
            balance = balance,
            equity = balance,
            freeMargin = balance,
            leverage = prefs.getInt(KEY_LEVERAGE, loadedSettings.leverage),
            currency = "USD",
            login = prefs.getString(KEY_LOGIN, "").orEmpty(),
            server = prefs.getString(KEY_SERVER, "").orEmpty(),
            broker = prefs.getString(KEY_BROKER, "").orEmpty(),
            connected = prefs.getBoolean(KEY_CONNECTED, false),
        )
    }

    private fun saveAccount() {
        val current = _account.value ?: return
        prefs.edit()
            .putString(KEY_BALANCE, current.balance.toString())
            .putInt(KEY_LEVERAGE, current.leverage)
            .putString(KEY_LOGIN, current.login)
            .putString(KEY_SERVER, current.server)
            .putString(KEY_BROKER, current.broker)
            .putBoolean(KEY_CONNECTED, current.connected)
            .apply()
    }

    private fun loadPositions(): List<Position> {
        val raw = prefs.getString(KEY_POSITIONS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<Position>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val symbol = json.optString("symbol")
            if (!MarketData.contains(symbol)) continue
            val side = runCatching { Side.valueOf(json.optString("side")) }.getOrNull() ?: continue
            result.add(
                Position(
                    id = json.optLong("id"),
                    symbol = symbol,
                    side = side,
                    lots = json.optDouble("lots", 0.0),
                    entryPrice = json.optDouble("entryPrice", 0.0),
                    stopLoss = json.optDouble("stopLoss", 0.0),
                    takeProfit = json.optDouble("takeProfit", 0.0),
                    openTime = json.optLong("openTime"),
                    strategyId = json.optString("strategyId").takeIf { it.isNotBlank() },
                    comment = json.optString("comment"),
                    currentPrice = json.optDouble("entryPrice", 0.0),
                ),
            )
        }
        return result
    }

    private fun savePositions() {
        val array = JSONArray()
        for (position in _positions.value.orEmpty()) {
            array.put(
                JSONObject()
                    .put("id", position.id)
                    .put("symbol", position.symbol)
                    .put("side", position.side.name)
                    .put("lots", position.lots)
                    .put("entryPrice", position.entryPrice)
                    .put("stopLoss", position.stopLoss)
                    .put("takeProfit", position.takeProfit)
                    .put("openTime", position.openTime)
                    .put("strategyId", position.strategyId ?: "")
                    .put("comment", position.comment),
            )
        }
        prefs.edit().putString(KEY_POSITIONS, array.toString()).apply()
    }

    private fun loadHistory(): List<TradeHistory> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<TradeHistory>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val symbol = json.optString("symbol")
            if (!MarketData.contains(symbol)) continue
            val side = runCatching { Side.valueOf(json.optString("side")) }.getOrNull() ?: continue
            val reason = runCatching { CloseReason.valueOf(json.optString("reason")) }.getOrNull()
                ?: CloseReason.MANUAL
            result.add(
                TradeHistory(
                    id = json.optLong("id"),
                    symbol = symbol,
                    side = side,
                    lots = json.optDouble("lots", 0.0),
                    entryPrice = json.optDouble("entryPrice", 0.0),
                    exitPrice = json.optDouble("exitPrice", 0.0),
                    openTime = json.optLong("openTime"),
                    closeTime = json.optLong("closeTime"),
                    profit = json.optDouble("profit", 0.0),
                    commission = json.optDouble("commission", 0.0),
                    reason = reason,
                    strategyId = json.optString("strategyId").takeIf { it.isNotBlank() },
                    comment = json.optString("comment"),
                ),
            )
        }
        return result.take(500)
    }

    private fun saveHistory() {
        val array = JSONArray()
        for (trade in _history.value.orEmpty()) {
            array.put(
                JSONObject()
                    .put("id", trade.id)
                    .put("symbol", trade.symbol)
                    .put("side", trade.side.name)
                    .put("lots", trade.lots)
                    .put("entryPrice", trade.entryPrice)
                    .put("exitPrice", trade.exitPrice)
                    .put("openTime", trade.openTime)
                    .put("closeTime", trade.closeTime)
                    .put("profit", trade.profit)
                    .put("commission", trade.commission)
                    .put("reason", trade.reason.name)
                    .put("strategyId", trade.strategyId ?: "")
                    .put("comment", trade.comment),
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }
}
